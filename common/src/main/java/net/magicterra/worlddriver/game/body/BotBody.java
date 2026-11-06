package net.magicterra.worlddriver.game.body;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.magicterra.worlddriver.config.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A bot's player: joined to the server as a client's would be (see {@link JoinedPlayers}), and able to do what Carpet's
 * fake player can. It is hurt, dies and drops what it carries as any player does; game mode and
 * {@code abilities.invulnerable} are what make it invulnerable, as for any player. Where it differs from Carpet's is
 * death: a dead bot stays dead, on the player list, until {@link #respawnFromDeath()}, as a player stays on the death
 * screen, rather than leaving the game.
 *
 * <p>Until 2026-11-03 this was {@code JoinedPlayers.JoinedBody}, which could not be hurt or die: the scenes and the
 * journey rig of the time assumed a player that could not.
 *
 * <h2>Who ticks it</h2>
 *
 * <p>Once per server tick, as any player is. A driver that steps it ({@link #pump}) is that tick's update; a bot no
 * driver stepped is updated at the end of the tick ({@link #endOfServerTick}), standing still on no input, so it heals,
 * starves, burns and dies with no driver as a Carpet player does. A bot that is {@linkplain #handTicked hand ticked} is
 * moved only by the steps it is given, any number of them in one server tick.
 *
 * <p>Not from the level's entity loop, which runs before the drivers: updated there, the player a brain reads would
 * already be a tick ahead of the one it decided on, and a driver stepping it afterwards would update it twice in that
 * tick. A buried bot crawled out of its head block before its anti-suffocation reflex could see it there.
 */
public final class BotBody extends ServerPlayer {

    private boolean leaving;

    /**
     * Set by the level's entity loop, read and cleared by {@link #pump}.
     *
     * <p>{@code ServerLevel.tickNonPassenger} does {@code setOldPosAndRot()} and {@code tickCount++} itself and then
     * calls {@link #tick()}; neither is in {@code ServerPlayer.tick()} or {@code doTick()}. A player pumped once per
     * server tick gets both from the loop, but scenes pump one player hundreds of times inside a single server tick,
     * and each of those steps needs its own: {@code xo}/{@code zo} feed the body yaw and the walk animation, and every
     * {@code tickCount % N} rule would otherwise fire on all of them or on none. A flag rather than a game-time
     * comparison, because a scene may re-enter {@code level.tick} between steps and a portal scene moves the player to
     * another level mid-loop; the flag answers "did a loop tick this player since the last step" for whichever loop it
     * was.
     */
    private boolean levelTicked;

    /** Whether a driver stepped this player since the last {@link #endOfServerTick}. */
    private boolean stepped;

    /** Whether an end-of-tick update threw; the server stops updating this player then. */
    private boolean updateFailed;

    /** Moved only by the steps it is given, never at the end of a server tick. */
    private boolean handTicked;

    /** This step's movement input, as a client's {@code Input} holds it before any scaling. */
    private float moveLeft;

    private float moveForward;

    private long lastJumpGameTime = -1;

    /** What the server said to this player; its connection's, so the player a respawn makes shares it. */
    private Inbox inbox = new Inbox();

    /** Told when a respawn replaces this player with the next; carried over to that one. */
    private final List<Consumer<BotBody>> onReplaced = new CopyOnWriteArrayList<>();

    BotBody(ServerLevel level, GameProfile profile, ClientInformation client) {
        super(level.getServer(), level, profile, client);
        // As Carpet's: every skin layer shown, the cape among them, as a client with default settings sends.
        entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    /** Whether this player is moved only by the steps it is given; see the class javadoc. */
    public boolean handTicked() {
        return handTicked;
    }

    /** Leave this player's updates to its steps alone, or not; a respawned player keeps the setting. */
    public void handTicked(boolean byHand) {
        handTicked = byHand;
    }

    /**
     * The chat, system and action bar lines the server sent this player since it connected, and, in a test run, the
     * custom payloads mods sent it, configuration included.
     */
    public Inbox inbox() {
        return inbox;
    }

    /** Call {@code listener} with the player that replaces this one at a respawn, and with each one after it. */
    public void onReplaced(Consumer<BotBody> listener) {
        onReplaced.add(listener);
    }

    /**
     * The player a respawn makes in this one's place, from {@code PlayerList.respawn} (the mixin on it): a bot again,
     * where vanilla would make a plain {@code ServerPlayer} that nothing drives. Everything that held this one is
     * pointed at it before vanilla copies this one's state across.
     */
    public BotBody successor(ServerLevel level) {
        BotBody next = new BotBody(level, getGameProfile(), clientInformation());
        next.handTicked = handTicked;
        next.inbox = inbox;
        next.onReplaced.addAll(onReplaced);
        for (Consumer<BotBody> listener : onReplaced) {
            listener.accept(next);
        }
        return next;
    }

    /**
     * Respawn, as a client does from the death screen: the respawn command, through this player's own packet handler,
     * so the respawn point, the loader's respawn event and the game rules are vanilla's. Not {@code Player.respawn},
     * which is the client's.
     *
     * @return the player that replaces this one; this one is gone from the world after
     * @throws IllegalStateException when this player is alive
     */
    public BotBody respawnFromDeath() {
        if (!isDeadOrDying()) {
            throw new IllegalStateException(
                    getGameProfile().getName() + " is alive, so there is nothing to respawn from");
        }
        connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return (BotBody) connection.player;
    }

    /**
     * Leaving the world means leaving the <b>player list</b>, not just the level.
     *
     * <p>Every scene already disposes its player with {@code fp.discard()}, which is enough for a fake player — it was
     * never in a list to begin with. A placed player discarded that way stops ticking but stays in {@code PlayerList},
     * where the next scene's {@code ctx.player()} picks it up as "a connected player". Measured on the first armed
     * Fabric run: 79 joins, 0 departures, and thirteen scenes that should have skipped ran against a stranded corpse
     * instead.
     *
     * <p>Only a discard leaves the list. A change of dimension comes through here too
     * ({@code ServerLevel.removePlayerImmediately} with {@code CHANGED_DIMENSION}, undone by {@code unsetRemoved} once
     * the player is in the new level), and so do a death, twenty ticks in, and a respawn, both {@code KILLED}: a player
     * stays on the list through each. Leaving it on a change of dimension took a bot that went through a portal off the
     * list and saved it where it stood before the move. {@code PlayerList.remove} routes back here, hence the guard —
     * without it this recurses until the stack gives out.
     *
     * <p><b>Why this logs at all.</b> A departure here is REQUIRED to be silent in the vanilla channel, and that
     * silence has already been misread once as a leak. The chat line "X left the game" is broadcast from
     * {@code ServerGamePacketListenerImpl}'s {@code removePlayerFromWorld()}, reached only from {@code onDisconnect} —
     * which a discard does not reach. {@code PlayerList.remove} itself broadcasts a
     * {@code ClientboundPlayerInfoRemovePacket} and logs nothing. Meanwhile the ARRIVAL is announced by vanilla, from
     * {@code PlayerList.placeNewPlayer}, which this player does call. So counting "joined" against "left" in a server
     * log compares two unrelated channels and will report a totally healthy run as 239 joins and 0 departures. The line
     * below is this class's own leave channel, deliberately shaped like the join line, so the comparison is finally
     * between two things that answer the same question.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (reason == RemovalReason.DISCARDED && getServer() != null && claimLeaving()) {
            try {
                getServer().getPlayerList().remove(this);
                logLeft();
                return;
            } catch (RuntimeException e) {
                Mod.LOG.warn(
                        "[realbody] {} could not leave the player list: {}",
                        getGameProfile().getName(),
                        e.toString());
            }
        }
        super.remove(reason);
    }

    /**
     * Whether this call is the one that takes the player off the list. A discard ({@link #remove}) and a disconnect
     * ({@link SilentConnection}) can both come, in either order; only the first leaves, so the logout, the save and the
     * departure line happen once.
     */
    boolean claimLeaving() {
        if (leaving) {
            return false;
        }
        leaving = true;
        return true;
    }

    /** The other half of the join line; a discard and a disconnect both end here ({@link SilentConnection}). */
    void logLeft() {
        Mod.LOG.info(
                "[realbody] {} left {} (players={})",
                getGameProfile().getName(),
                level().dimension().location(),
                getServer().getPlayerList().getPlayerCount());
    }

    /** The level's entity loop. Records that it came and does nothing else; see the class javadoc for who updates. */
    @Override
    public void tick() {
        levelTicked = true;
    }

    /**
     * The end of a server tick, after the drivers: this tick's update with no input, unless a driver stepped the player
     * this tick or it is hand ticked.
     */
    void endOfServerTick() {
        if (!handTicked && !stepped && !updateFailed) {
            try {
                update(0, 0, false, isShiftKeyDown());
            } catch (Throwable t) {
                // As BotTicker drops a crashed driver: logged once, and not updated this way again, so one bot's
                // broken state neither takes the tick down nor writes a stack trace twenty times a second.
                updateFailed = true;
                Mod.LOG.error(
                        "[realbody] {}'s update failed; the server no longer updates it",
                        getGameProfile().getName(),
                        t);
            }
        }
        stepped = false;
    }

    /**
     * One tick of this player, driven the way a connected client drives one; the driver's step, and this tick's update.
     *
     * <p>The order is a real player's. First the input a client would have sent. Then {@code ServerPlayer.tick()} (game
     * mode, menu validity, the invulnerability countdown, advancements) and {@code doTick()}, which enters
     * {@code Player.tick} by {@code invokespecial} and so runs the whole vanilla chain past this class's
     * {@link #tick()}: {@code baseTick}, item use, equipment attributes, {@link #aiStep()} with its jump gate and
     * {@code noJumpDelay}, {@code travel} (whose {@code ServerPlayer} override counts the movement statistics), food,
     * pose. Then the tail of {@code ServerGamePacketListenerImpl.handleMovePlayer}, which is where a server learns that
     * a player moved: fall distance ({@code ServerPlayer.checkFallDamage} is empty, so only {@code doCheckFallDamage}
     * reaches the landing rules), the known movement, the fall-distance reset on upward movement, the impulse-context
     * reset, and {@code ChunkMap.move}. The handler's own {@code checkMovementStatistics} call is the one piece of that
     * tail left out: this player's {@code travel} runs above and already counted the same displacement, so a second
     * call doubles the statistics and the food that swimming and sprinting cost.
     *
     * <p>Nothing here may be called a second time from outside — not {@code baseTick}, {@code travel} nor
     * {@code checkMovementStatistics}; each already runs once inside.
     */
    public void pump(float left, float forward, boolean jump, boolean sneak) {
        stepped = true;
        update(left, forward, jump, sneak);
    }

    private void update(float left, float forward, boolean jump, boolean sneak) {
        // A player twenty ticks dead has left the level; nothing of it moves until it respawns.
        if (isRemoved()) {
            return;
        }
        moveLeft = left;
        moveForward = forward;
        setJumping(jump);
        setShiftKeyDown(sneak);
        if (!levelTicked) {
            setOldPosAndRot();
            tickCount++;
        }
        levelTicked = false;
        double x0 = getX();
        double y0 = getY();
        double z0 = getZ();
        final boolean wasFallFlying = isFallFlying(); // read before the tick changes it
        super.tick();
        doTick();
        Vec3 moved = new Vec3(getX() - x0, getY() - y0, getZ() - z0);
        doCheckFallDamage(moved.x, moved.y, moved.z, onGround());
        setKnownMovement(moved);
        if (moved.y > 0) {
            resetFallDistance();
        }
        if (onGround()
                || hasLandedInLiquid()
                || onClimbable()
                || isSpectator()
                || wasFallFlying
                || isAutoSpinAttack()) {
            tryResetCurrentImpulseContext();
        }
        // Load-bearing guard: ChunkMap.move ends in DistanceManager.removePlayer, which
        // dereferences this player's playersPerChunk entry without a null check, and a player
        // that has left the player list has none. Membership of players() is exactly that question.
        if (!isRemoved() && serverLevel().players().contains(this)) {
            serverLevel().getChunkSource().move(this);
        }
    }

    /**
     * The half of {@code LocalPlayer.aiStep} that turns a client's input into movement, then vanilla's own
     * {@code aiStep}.
     *
     * <p>A server never does this for a player; the client does it and sends the result, so a player that is its own
     * client has to. Ported in {@code LocalPlayer}'s order: the crouch decision and the {@code SNEAKING_SPEED} scale,
     * the 0.2 scale while using an item, the push out of a block a corner of the hitbox is inside, the rules that stop
     * sprinting, and the sink while sneaking in water. The crouch is decided from THIS tick's shift key, not last
     * tick's pose: {@code LocalPlayer.isCrouching()} returns the field computed at the top of its {@code aiStep}, so a
     * client slows on the tick it presses sneak.
     *
     * <p>Not ported, because the driver does these another way or not at all: starting a sprint (the driver sets the
     * flag, as it does on the client player), creative flight, starting an elytra glide from the jump key (the driver
     * calls {@code startFallFlying}), and riding jumps.
     */
    @Override
    public void aiStep() {
        boolean crouching = !getAbilities().flying
                && !isSwimming()
                && !isPassenger()
                && canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)
                && (isShiftKeyDown() || !isSleeping() && !canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING));
        float left = moveLeft;
        float forward = moveForward;
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
            // Ported verbatim from LocalPlayer.aiStep; !(f > x) and f <= x differ when f is NaN.
            @SuppressWarnings("PMD.LogicInversion")
            boolean spent = !(forward > 1.0E-5F)
                    || !(isPassenger() || getFoodData().getFoodLevel() > 6 || getAbilities().mayfly);
            if (isSwimming()) {
                if (!onGround() && !isShiftKeyDown() && spent || !isInWater()) {
                    setSprinting(false);
                }
            } else if (spent || horizontalCollision && !minorHorizontalCollision || isInWater() && !isUnderWater()) {
                setSprinting(false);
            }
        }
        if (isInWater() && isShiftKeyDown() && isAffectedByFluids()) {
            goDownInWater();
        }
        // LocalPlayer.serverAiStep writes these from inside super.aiStep(); nothing between here
        // and there reads them, so writing them first is the same thing.
        xxa = left;
        zza = forward;
        super.aiStep();
    }

    /** {@code LocalPlayer.moveTowardsClosestSpace}: nudge the player out of a suffocating block. */
    private void pushOutOfBlock(double x, double z) {
        BlockPos cell = BlockPos.containing(x, getY(), z);
        if (!suffocatesAt(cell)) {
            return;
        }
        double fx = x - cell.getX();
        double fz = z - cell.getZ();
        Direction nearest = null;
        double gap = Double.MAX_VALUE;
        for (Direction side : new Direction[] {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH}) {
            double along = side.getAxis().choose(fx, 0.0, fz);
            double toSide = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - along : along;
            if (toSide < gap && !suffocatesAt(cell.relative(side))) {
                gap = toSide;
                nearest = side;
            }
        }
        if (nearest == null) {
            return;
        }
        Vec3 dm = getDeltaMovement();
        if (nearest.getAxis() == Direction.Axis.X) {
            setDeltaMovement(0.1 * nearest.getStepX(), dm.y, dm.z);
        } else {
            setDeltaMovement(dm.x, dm.y, 0.1 * nearest.getStepZ());
        }
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
     * <p>{@code Entity} returns false, so on a server {@code minorHorizontalCollision} is always false and the sprint
     * rule in {@link #aiStep()} would stop a sprint on every glancing touch of a wall and every riser. The test is the
     * client's: the angle between the direction the input pushes and the direction the move went, under 8 degrees.
     */
    @Override
    protected boolean isHorizontalCollisionMinor(Vec3 movement) {
        float yaw = getYRot() * ((float) Math.PI / 180F);
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        double ix = xxa * cos - zza * sin;
        double iz = zza * cos + xxa * sin;
        double inputSq = Mth.square(ix) + Mth.square(iz);
        double moveSq = Mth.square(movement.x) + Mth.square(movement.z);
        if (inputSq < 1.0E-5F || moveSq < 1.0E-5F) {
            return false;
        }
        double dot = ix * movement.x + iz * movement.z;
        return Math.acos(dot / Math.sqrt(inputSq * moveSq)) < 0.139_626_34F;
    }

    /** Every jump {@link #aiStep()} takes passes through here, so the driver times its jumps from it. */
    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        lastJumpGameTime = level().getGameTime();
    }

    /** Game time of this player's last jump, or -1 before the first. */
    public long lastJumpGameTime() {
        return lastJumpGameTime;
    }

    /** As Carpet's: the players riding it, or that it rides, get off before it dies. */
    @Override
    public void die(DamageSource cause) {
        if (getVehicle() instanceof Player) {
            stopRiding();
        }
        for (Entity passenger : getIndirectPassengers()) {
            if (passenger instanceof Player) {
                passenger.stopRiding();
            }
        }
        super.die(cause);
    }

    /**
     * A change of dimension, finished as a client finishes it: a client confirms the teleport that ends it, and a bot
     * has no client to. Null is a move that did not happen (removed, or a loader's travel event cancelled it).
     */
    @Override
    public Entity changeDimension(DimensionTransition transition) {
        Entity moved = super.changeDimension(transition);
        if (moved != null && isChangingDimension()) {
            hasChangedDimension();
        }
        return moved;
    }

    /**
     * Leaving the End through its exit portal the first time: vanilla takes the player out of the End and shows its
     * client the credits ({@code EndPortalBlock} goes here instead of through a change of dimension), and the client
     * respawns once they end. This player has no client to watch them, so it respawns on the next server task, as a
     * client's respawn arrives from its connection rather than inside the portal's tick. Carpet's fake player respawns
     * only from {@code changeDimension}, which this exit never reaches, and stays outside every level.
     */
    @Override
    public void showEndCredits() {
        super.showEndCredits();
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (wonGame && connection.player == this) {
                connection.handleClientCommand(
                        new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
        }));
    }

    /**
     * As Carpet's: no equip sound or event for each tick an item in use is swapped back into the hand it was in, which
     * a client never sends.
     */
    @Override
    public void onEquipItem(EquipmentSlot slot, ItemStack previous, ItemStack stack) {
        if (!isUsingItem()) {
            super.onEquipItem(slot, previous, stack);
        }
    }

    /** As Carpet's: the loopback address, for a mod that asks where the player connects from. */
    @Override
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // the loopback address is the answer, not a setting
    public String getIpAddress() {
        return "127.0.0.1";
    }
}
