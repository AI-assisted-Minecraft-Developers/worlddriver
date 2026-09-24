package net.magicterra.worlddriver.bot.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A body that <b>joins the server</b> instead of pretending to be on it.
 *
 * <h2>Why this exists</h2>
 *
 * Every gap the playthrough ladder found in the headless agent has the same shape: vanilla does
 * the thing in a method this body never runs. Blocks dropped nothing
 * ({@code Level#destroyBlock}'s flag), drops were never picked up (the entity-touch loop in
 * {@code Player.aiStep}), crafting tables would not open ({@code openMenu} returning empty), and
 * no advancement was ever awarded (no listener on {@code inventoryMenu}, and nothing calling
 * {@code broadcastChanges}). Each was fixed by hand-copying one more piece of vanilla into the
 * server body's mirror of {@code Player.tick()} — and that list only grew, because it was a
 * re-implementation maintained by discovering what was missing.
 *
 * <p>A {@code FakePlayer} is a {@code ServerPlayer} that was never <i>placed</i>. The join path —
 * {@code PlayerList.placeNewPlayer} — is what attaches the inventory-menu listener that awards
 * advancements, puts the body in {@code ServerLevel.players()} so the level keeps ticking and mob
 * AI can see it, registers it with the {@code ChunkMap} so it loads the chunks it walks into, and
 * fires the loader's login event that modpack mods hook. None of that is reachable by copying
 * methods; it is reachable by joining.
 *
 * <h2>Joined first, then ticked by vanilla</h2>
 *
 * This landed in two halves, so that no gate had to tell "the body is real now" apart from
 * "movement moved". The first joined the body and left its {@code tick()} empty while the driver
 * still integrated locomotion by hand. The second is {@link JoinedBody#pump}: each step runs
 * vanilla's player tick on the input a client writes, and {@code tick()} stays empty only for the
 * level's entity loop, which must not move a body on a schedule of its own.
 *
 * <h2>The only server body</h2>
 *
 * {@link ServerAvatarBodies} hands out these and nothing else. This body was first armed with
 * {@code -Dworlddriver.realPlayerBodies=true} in place of each loader's fake player, which turned
 * the dogfood scenes and the journey ladder into an A/B harness for the two bodies. Once every gate
 * and every ladder topology ran on this one, the switch and the fake players were deleted.
 *
 * <h2>The trap in the connection</h2>
 *
 * The fake players this replaced swallowed outbound packets by overriding {@code send} on their
 * <i>packet listener</i>. That does not survive here: {@code placeNewPlayer} constructs vanilla's
 * own {@code ServerGamePacketListenerImpl} and installs it, so the listener is not ours to
 * override. The swallow has to move down to the {@link Connection}, and it has to also report
 * {@code isConnected() == true} — a disconnected {@code Connection} does not drop packets, it
 * queues them in {@code pendingActions} forever, which is a leak that looks like nothing at all.
 */
public final class JoinedPlayerBodies {

    /** The name the per-level shared body joins under — one per level, the same per-level sharing
     *  the fake-player factories had, so scene behaviour stayed comparable across the switch. */
    private static final String SHARED_NAME = "worlddriver";

    private final Map<ServerLevel, Map<String, JoinedBody>> byLevel = new ConcurrentHashMap<>();

    /** The per-level shared body: every caller in a level gets the same player. */
    public ServerPlayer shared(ServerLevel level) {
        return body(level, profileFor(SHARED_NAME));
    }

    /** A body of its own for {@code profile}, per level. */
    public ServerPlayer unique(ServerLevel level, GameProfile profile) {
        return body(level, profile);
    }

    /** Drop this level's bodies — they leave the player list rather than linger as ghosts. */
    public void unloadLevel(ServerLevel level) {
        Map<String, JoinedBody> bodies = byLevel.remove(level);
        if (bodies == null) return;
        for (JoinedBody body : bodies.values()) {
            try {
                // discard(), not PlayerList.remove(): the body's own remove() is what leaves the
                // list, and going straight to PlayerList would re-enter it from the outside.
                body.discard();
            } catch (RuntimeException e) {
                WorldDriverCommon.LOG.warn("[realbody] could not remove {} on unload: {}",
                        body.getGameProfile().getName(), e.toString());
            }
        }
    }

    /**
     * The cached body for this profile, re-joining when the last one left.
     *
     * <p>Not {@code computeIfAbsent}: a body that has been discarded is no longer in the player
     * list, and handing it back would drive a corpse. Sweeping the removed ones here, rather than
     * from a removal callback, keeps the cache self-healing — bodies are only ever minted on the
     * server thread, so the sweep-then-put is not racing anything.
     *
     * <p>The sweep is not optional. Scenes mint bodies under unique names ({@code agent-body-N}),
     * so a stale entry is never overwritten by a re-join; it just stays, and the map was the last
     * thing holding each departed {@code ServerPlayer} with its advancements, stats and inventory.
     * Measured on the dedicated Fabric suite: 255 bodies retained after 300 scenes, and the 2 GB
     * server heap ran out around scene 290 in two of three runs.
     */
    private JoinedBody body(ServerLevel level, GameProfile profile) {
        Map<String, JoinedBody> byName = byLevel.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
        byName.values().removeIf(JoinedBody::isRemoved);
        JoinedBody cached = byName.get(profile.getName());
        if (cached != null) return cached;
        JoinedBody fresh = join(level, profile);
        byName.put(profile.getName(), fresh);
        return fresh;
    }

    private static JoinedBody join(ServerLevel level, GameProfile profile) {
        JoinedBody body = new JoinedBody(level, profile);
        // The whole point of the class. Everything a fake player is missing is installed here:
        // the inventory-menu listener (advancements), player-list membership (the level ticks,
        // mobs can see it), ChunkMap registration (it loads what it walks into), and the loader's
        // login event.
        try {
            level.getServer().getPlayerList().placeNewPlayer(
                    body.silentConnection(), body, CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException e) {
            // The join path runs a lot of code that assumes a socket. Log the frame that wanted
            // one — the harness only surfaces an exception's message, and "channel is null" names
            // netty rather than the caller that reached for it.
            WorldDriverCommon.LOG.error("[realbody] placeNewPlayer failed for {}", profile.getName(), e);
            throw e;
        }
        // `players=` is half of a matched pair — see JoinedBody.remove() for the other half and for
        // why the vanilla log cannot answer this question. Stamping the list size on both edges is
        // what makes residue readable at every moment of a run rather than only where a scene
        // happened to ask: peak = max over the joins, leak = the last value at shutdown.
        WorldDriverCommon.LOG.info("[realbody] {} joined {} at {} (players={})", profile.getName(),
                level.dimension().location(), body.blockPosition(),
                level.getServer().getPlayerList().getPlayerCount());
        return body;
    }

    /** A stable offline-style profile, so a body rejoining the same world is the same player. */
    private static GameProfile profileFor(String name) {
        return new GameProfile(
                java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name);
    }

    /**
     * The body itself. Deliberately thin: what made it different from the fake players it replaced
     * was not what it overrides, it is that it was placed.
     */
    public static final class JoinedBody extends ServerPlayer {

        private final SilentConnection wire = new SilentConnection();
        private boolean leaving;

        JoinedBody(ServerLevel level, GameProfile profile) {
            super(level.getServer(), level, profile, ClientInformation.createDefault());
        }

        Connection silentConnection() { return wire; }

        /**
         * Leaving the world means leaving the <b>player list</b>, not just the level.
         *
         * <p>Every scene already disposes its body with {@code fp.discard()}, which is enough for a
         * fake player — it was never in a list to begin with. A placed player discarded that way
         * stops ticking but stays in {@code PlayerList}, where the next scene's
         * {@code ctx.player()} picks it up as "a connected player". Measured on the first armed
         * Fabric run: 79 joins, 0 departures, and thirteen scenes that should have skipped ran
         * against a stranded corpse instead.
         *
         * <p>{@code PlayerList.remove} routes back here through
         * {@code ServerLevel.removePlayerImmediately}, hence the guard — without it this recurses
         * until the stack gives out.
         *
         * <p><b>Why this logs at all.</b> A departure here is REQUIRED to be silent in the vanilla
         * channel, and that silence has already been misread once as a leak. The chat line
         * "X left the game" is broadcast from {@code ServerGamePacketListenerImpl}'s
         * {@code removePlayerFromWorld()}, reached only from {@code onDisconnect} — a socket path
         * this body deliberately never enters. {@code PlayerList.remove} itself broadcasts a
         * {@code ClientboundPlayerInfoRemovePacket} and logs nothing. Meanwhile the ARRIVAL is
         * announced by vanilla, from {@code PlayerList.placeNewPlayer}, which this body does call.
         * So counting "joined" against "left" in a server log compares two unrelated channels and
         * will report a totally healthy run as 239 joins and 0 departures. The line below is this
         * class's own leave channel, deliberately shaped like the join line, so the comparison is
         * finally between two things that answer the same question.
         */
        @Override
        public void remove(RemovalReason reason) {
            if (!leaving && getServer() != null) {
                leaving = true;
                try {
                    getServer().getPlayerList().remove(this);
                    WorldDriverCommon.LOG.info("[realbody] {} left {} (players={})",
                            getGameProfile().getName(), level().dimension().location(),
                            getServer().getPlayerList().getPlayerCount());
                    return;
                } catch (RuntimeException e) {
                    WorldDriverCommon.LOG.warn("[realbody] {} could not leave the player list: {}",
                            getGameProfile().getName(), e.toString());
                }
            }
            super.remove(reason);
        }

        /**
         * Set by the level's entity loop, read and cleared by {@link #pump}.
         *
         * <p>{@code ServerLevel.tickNonPassenger} does {@code setOldPosAndRot()} and {@code tickCount++}
         * itself and then calls {@link #tick()}; neither is in {@code ServerPlayer.tick()} or
         * {@code doTick()}. A body pumped once per server tick gets both from the loop, but scenes
         * pump one body hundreds of times inside a single server tick, and each of those steps needs
         * its own: {@code xo}/{@code zo} feed the body yaw and the walk animation, and every
         * {@code tickCount % N} rule would otherwise fire on all of them or on none. A flag rather
         * than a game-time comparison, because a scene may re-enter {@code level.tick} between steps
         * and a portal scene moves the body to another level mid-loop; the flag answers "did a loop
         * tick this body since the last step" for whichever loop it was.
         */
        private boolean levelTicked;

        /** This step's movement input, as a client's {@code Input} holds it before any scaling. */
        private float moveLeft, moveForward;

        private long lastJumpGameTime = -1;

        /**
         * The level's entity loop. Records that it came and does nothing else: the body moves when its
         * driver pumps it, never on the loop's schedule, so a body no driver steps stands still.
         */
        @Override public void tick() { levelTicked = true; }

        /**
         * One tick of this player, driven the way a connected client drives one.
         *
         * <p>The order is a real player's. First the input a client would have sent. Then
         * {@code ServerPlayer.tick()} (game mode, menu validity, the invulnerability countdown,
         * advancements) and {@code doTick()}, which enters {@code Player.tick} by
         * {@code invokespecial} and so runs the whole vanilla chain past this class's empty
         * {@link #tick()}: {@code baseTick}, item use, equipment attributes, {@link #aiStep()} with
         * its jump gate and {@code noJumpDelay}, {@code travel} (whose {@code ServerPlayer} override
         * counts the movement statistics), food, pose.
         * Then the tail of {@code ServerGamePacketListenerImpl.handleMovePlayer}, which is where a
         * server learns that a player moved: fall distance ({@code ServerPlayer.checkFallDamage} is
         * empty, so only {@code doCheckFallDamage} reaches the landing rules), the known movement,
         * the fall-distance reset on upward movement, the impulse-context reset, and
         * {@code ChunkMap.move}. The handler's own {@code checkMovementStatistics} call is the one
         * piece of that tail left out: this body's {@code travel} runs above and already counted the
         * same displacement, so a second call doubles the statistics and the food that swimming and
         * sprinting cost.
         *
         * <p>Nothing here may be called a second time from outside — not {@code baseTick},
         * {@code travel} nor {@code checkMovementStatistics}; each already runs once inside.
         */
        public void pump(float left, float forward, boolean jump, boolean sneak) {
            moveLeft = left;
            moveForward = forward;
            setJumping(jump);
            setShiftKeyDown(sneak);
            if (!levelTicked) {
                setOldPosAndRot();
                tickCount++;
            }
            levelTicked = false;
            double x0 = getX(), y0 = getY(), z0 = getZ();
            boolean wasFallFlying = isFallFlying();
            super.tick();
            doTick();
            Vec3 moved = new Vec3(getX() - x0, getY() - y0, getZ() - z0);
            doCheckFallDamage(moved.x, moved.y, moved.z, onGround());
            setKnownMovement(moved);
            if (moved.y > 0) resetFallDistance();
            if (onGround() || hasLandedInLiquid() || onClimbable() || isSpectator() || wasFallFlying
                    || isAutoSpinAttack()) {
                tryResetCurrentImpulseContext();
            }
            // Load-bearing guard: ChunkMap.move ends in DistanceManager.removePlayer, which
            // dereferences this player's playersPerChunk entry without a null check, and a body that
            // has left the player list has none. Membership of players() is exactly that question.
            if (!isRemoved() && serverLevel().players().contains(this)) {
                serverLevel().getChunkSource().move(this);
            }
        }

        /**
         * The half of {@code LocalPlayer.aiStep} that turns a client's input into movement, then
         * vanilla's own {@code aiStep}.
         *
         * <p>A server never does this for a player; the client does it and sends the result, so a
         * body that is its own client has to. Ported in {@code LocalPlayer}'s order: the crouch
         * decision and the {@code SNEAKING_SPEED} scale, the 0.2 scale while using an item, the push
         * out of a block a corner of the body is inside, the rules that stop sprinting, and the sink
         * while sneaking in water. The crouch is decided from THIS tick's shift key, not last tick's
         * pose: {@code LocalPlayer.isCrouching()} returns the field computed at the top of its
         * {@code aiStep}, so a client slows on the tick it presses sneak.
         *
         * <p>Not ported, because the driver does these another way or not at all: starting a sprint
         * (the driver sets the flag, as it does on the client body), creative flight, starting an
         * elytra glide from the jump key (the driver calls {@code startFallFlying}), and riding jumps.
         */
        @Override
        public void aiStep() {
            boolean crouching = !getAbilities().flying && !isSwimming() && !isPassenger()
                    && canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)
                    && (isShiftKeyDown() || !isSleeping() && !canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING));
            float left = moveLeft, forward = moveForward;
            if (crouching || isVisuallyCrawling()) {
                float slow = (float) getAttributeValue(Attributes.SNEAKING_SPEED);
                left *= slow;
                forward *= slow;
            }
            if (isUsingItem() && !isPassenger()) {
                left *= 0.2F;
                forward *= 0.2F;
            }
            if (!noPhysics) {
                double reach = getBbWidth() * 0.35;
                pushOutOfBlock(getX() - reach, getZ() + reach);
                pushOutOfBlock(getX() - reach, getZ() - reach);
                pushOutOfBlock(getX() + reach, getZ() - reach);
                pushOutOfBlock(getX() + reach, getZ() + reach);
            }
            if (isSprinting()) {
                boolean spent = !(forward > 1.0E-5F)
                        || !(isPassenger() || getFoodData().getFoodLevel() > 6 || getAbilities().mayfly);
                if (isSwimming()) {
                    if (!onGround() && !isShiftKeyDown() && spent || !isInWater()) setSprinting(false);
                } else if (spent || horizontalCollision && !minorHorizontalCollision
                        || isInWater() && !isUnderWater()) {
                    setSprinting(false);
                }
            }
            if (isInWater() && isShiftKeyDown() && isAffectedByFluids()) goDownInWater();
            // LocalPlayer.serverAiStep writes these from inside super.aiStep(); nothing between here
            // and there reads them, so writing them first is the same thing.
            xxa = left;
            zza = forward;
            super.aiStep();
        }

        /** {@code LocalPlayer.moveTowardsClosestSpace}: nudge a body out of a suffocating block. */
        private void pushOutOfBlock(double x, double z) {
            BlockPos cell = BlockPos.containing(x, getY(), z);
            if (!suffocatesAt(cell)) return;
            double fx = x - cell.getX(), fz = z - cell.getZ();
            Direction nearest = null;
            double gap = Double.MAX_VALUE;
            for (Direction side : new Direction[]{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH}) {
                double along = side.getAxis().choose(fx, 0.0, fz);
                double toSide = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - along : along;
                if (toSide < gap && !suffocatesAt(cell.relative(side))) {
                    gap = toSide;
                    nearest = side;
                }
            }
            if (nearest == null) return;
            Vec3 dm = getDeltaMovement();
            if (nearest.getAxis() == Direction.Axis.X) setDeltaMovement(0.1 * nearest.getStepX(), dm.y, dm.z);
            else setDeltaMovement(dm.x, dm.y, 0.1 * nearest.getStepZ());
        }

        private boolean suffocatesAt(BlockPos cell) {
            AABB box = getBoundingBox();
            AABB column = new AABB(cell.getX(), box.minY, cell.getZ(), cell.getX() + 1.0, box.maxY, cell.getZ() + 1.0)
                    .deflate(1.0E-7);
            return level().collidesWithSuffocatingBlock(this, column);
        }

        /**
         * {@code LocalPlayer}'s answer, which a server player never gives.
         *
         * <p>{@code Entity} returns false, so on a server {@code minorHorizontalCollision} is always
         * false and the sprint rule in {@link #aiStep()} would stop a sprint on every glancing touch
         * of a wall and every riser. The test is the client's: the angle between the direction the
         * input pushes and the direction the move went, under 8 degrees.
         */
        @Override
        protected boolean isHorizontalCollisionMinor(Vec3 movement) {
            float yaw = getYRot() * ((float) Math.PI / 180F);
            double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
            double ix = xxa * cos - zza * sin;
            double iz = zza * cos + xxa * sin;
            double inputSq = Mth.square(ix) + Mth.square(iz);
            double moveSq = Mth.square(movement.x) + Mth.square(movement.z);
            if (inputSq < 1.0E-5F || moveSq < 1.0E-5F) return false;
            double dot = ix * movement.x + iz * movement.z;
            return Math.acos(dot / Math.sqrt(inputSq * moveSq)) < 0.13962634F;
        }

        /** Every jump {@link #aiStep()} takes passes through here, so the driver times its jumps from it. */
        @Override
        public void jumpFromGround() {
            super.jumpFromGround();
            lastJumpGameTime = level().getGameTime();
        }

        /** Game time of this body's last jump, or -1 before the first. */
        public long lastJumpGameTime() { return lastJumpGameTime; }

        /** Scenes and the journey rig both assume a body that cannot die; keeping that here means
         *  the A/B measures the join and nothing else. A survival-fidelity run wants this gone. */
        @Override public boolean isInvulnerableTo(DamageSource source) { return true; }

        @Override public boolean canHarmPlayer(Player other) { return false; }

        @Override public void die(DamageSource cause) { }

        @Override public void displayClientMessage(Component message, boolean actionBar) { }
    }

    /**
     * A connection that is "up" and goes nowhere.
     *
     * <p>{@code isConnected()} must be true: {@code Connection.send} only drops a packet when it
     * believes it is connected — otherwise it appends to {@code pendingActions}, unboundedly, for
     * a client that will never arrive.
     */
    private static final class SilentConnection extends Connection {

        /**
         * A real netty channel that throws its writes away.
         *
         * <p>Vanilla's join path never touches {@code channel()} — every reach for it goes through
         * {@code send}, which this class swallows. NeoForge's does: it stores the connection type as
         * a <b>channel attribute</b>, so {@code placeNewPlayer} dies on
         * {@code Connection.channel().attr(...)} before the body exists. Measured: the whole armed
         * NeoForge suite fell to 76 executed scenes, every avatar scene reporting the same NPE.
         *
         * <p>{@link EmbeddedChannel} supplies attributes for free, but its default tail queues
         * outbound messages forever — a silent leak in place of a loud crash. The handler discards
         * and completes each write instead of letting it reach that queue.
         */
        @SuppressWarnings("unused")     // held so the channel is not collected out from under us
        private final EmbeddedChannel wire;

        SilentConnection() {
            super(PacketFlow.SERVERBOUND);
            // Registering fires channelActive through the pipeline, and Connection.channelActive is
            // what assigns its private `channel` field. That is the only way to fill it without
            // reflection — there is no setter, and `channel()` is NeoForge's accessor rather than a
            // vanilla method, so it cannot be overridden from :common either.
            wire = new EmbeddedChannel(discardOutbound(), this);
        }

        private static ChannelOutboundHandlerAdapter discardOutbound() {
            return new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            };
        }

        @Override public void send(Packet<?> packet) { }
        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { }
        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) { }
        @Override public boolean isConnected() { return true; }
        @Override public void tick() { }
        @Override public void disconnect(Component reason) { }
        @Override public void disconnect(DisconnectionDetails details) { }
        @Override public void setListenerForServerboundHandshake(PacketListener listener) { }
        @Override public void setReadOnly() { }
        @Override public void setupCompression(int threshold, boolean validate) { }

        /** Called every server tick from {@code ServerCommonPacketListenerImpl.resumeFlushing}, and
         *  it reaches for {@code channel.eventLoop()} directly rather than going through send. */
        @Override public void flushChannel() { }
        @Override public void handleDisconnection() { }

        /**
         * The two that a swallowed {@code send} does not cover, and the reason the first attempt
         * still died: a protocol switch is not a packet, it is a pipeline edit, and
         * {@code setupInboundProtocol} writes a marker straight to the channel rather than through
         * {@code send}. The join sequence changes protocol twice (login → configuration → play),
         * so this NPEs before the body is usable and after vanilla has already printed
         * "logged in", which reads as if the join succeeded.
         *
         * <p>Recording the listener is not optional: {@code setupInboundProtocol} is also where
         * vanilla installs the packet listener, and {@code getPacketListener()} is read during the
         * join. Dropping the pipeline work while keeping the assignment is the whole trick.
         */
        @Override
        public <T extends PacketListener> void setupInboundProtocol(
                net.minecraft.network.ProtocolInfo<T> protocol, T listener) {
            this.inbound = listener;
        }

        @Override public void setupOutboundProtocol(net.minecraft.network.ProtocolInfo<?> protocol) { }

        @Override public PacketListener getPacketListener() { return inbound; }

        private PacketListener inbound;
    }
}
