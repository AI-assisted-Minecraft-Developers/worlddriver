package net.magicterra.worlddriver.bot.stagewright;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.sim.JoinedPlayerBodies;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.testcontent.DrivenPiglin;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one place a scene gets a bot player, and the one place that asks whether minting one is legal here.
 *
 * <h2>Why this exists</h2>
 *
 * Ninety call sites across two packages opened with the same four lines — mint an isolated driver,
 * take its player, register a cleanup that unregisters and discards it, empty its inventory — and
 * six files had each grown a private {@code body(ctx, foot)} helper holding a copy of them. That is
 * ordinary duplication right up until the moment a rule has to apply to <b>every</b> mint, and then
 * it is ninety places to edit and no way to prove you found them all.
 *
 * <h2>The rule it enforces</h2>
 *
 * The 2026-08-20 player-entity selection instruction: NeoForge's {@code FakePlayer} is retired,
 * {@code JoinedBody} is for the <b>dedicated test server only</b>, and a topology that has a real
 * client drives that client's {@code LocalPlayer}. The word doing the work is <i>only</i> — it
 * divides by TOPOLOGY, not by what a scene happens to be testing. So on an integrated server with a
 * human in it there are exactly two legal states for a scene: drive the real player, or
 * {@linkplain SceneContext#skip skip and say where its coverage lives}. Minting a headless bot
 * player there is neither.
 *
 * <p>This class implements the second of those. Driving the real player is the larger job — the
 * gate scenes actuate through {@code ServerPlayerBody.step()}, which ticks the player it pumps,
 * and ticking a player whose own client is also moving it and sending movement packets makes the
 * two fight. {@code JourneyRig} already carries the shape of the answer (flip the helm to
 * {@code BotApi.runProcess} and let the client's own task chain drive), and converting a scene
 * family to it is per-family work, not a rename. Until a family is converted, a recorded skip is
 * the honest state: it is accounted for in the results, it names where the coverage actually is, and
 * it cannot be mistaken for a scene that quietly did its job.
 *
 * <h2>What a skip here costs, stated rather than hidden</h2>
 *
 * The integrated gate existed to run the SAME scenes under a different topology — "a topology must
 * vary the RUN, never the subject". Skipping the minting scenes there gives that up: the
 * cross-topology comparison is exactly what stops happening. The trade is deliberate and it is
 * temporary. Coverage for a skipped scene is on {@code stagewrightDedicatedServerFabric} /
 * {@code …Neoforge}, and the reconciliation that has to hold after every change here is:
 * <b>every scene skipped for this reason on the integrated gate is executed on the dedicated one.</b>
 * A skip is not coverage; a skip plus that reconciliation is.
 *
 * <h2>Which topologies this refuses on</h2>
 *
 * Only the integrated one — a game client hosting its own world, with the client half of the driver
 * in the JVM. That is the topology the instruction names, and both halves of that question are
 * settled before the first scene runs, which is the point.
 * {@code dedicatedServerWithClient} keeps minting on purpose: its client lives in the OTHER process
 * and a {@code BotProcess} object cannot cross a socket, which is the same reason the ladder keeps
 * a headless bot player there. If that should change, it is a decision to take deliberately rather than a
 * side effect of this predicate.
 */
public final class SceneBody {

    private SceneBody() {}

    // Three factories, because the call sites really are three shapes. A survey of the eighty-five
    // sites in `scene/` before this class existed: fifty follow `mint` exactly, a dozen (the six
    // copied private helpers) follow `managed`, and the rest own their cleanup because they mint two
    // bot players at once and tear both down together. Collapsing them onto one factory would have
    // changed what the DEDICATED server does — adding an `unregister` where there was none, or
    // clearing an inventory a scene had just filled — and that server's behaviour has to stay
    // byte-identical through this change, because it is the arm every skipped scene's coverage is
    // being handed to. So the shapes are preserved and only the gate is shared.

    /**
     * A bot player at {@code (x, y, z)}, discarded when the scene resolves. <b>The common case.</b>
     *
     * <p>Exactly {@code createIsolated} + {@code ctx.cleanup(() -> driver.fakePlayer().discard())},
     * which is what fifty sites wrote by hand.
     *
     * @throws net.magicterra.stagewright.contract.SceneSkipped when this topology may not mint one
     */
    public static ServerWorldDriver mint(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        ServerWorldDriver driver = bare(ctx, level, x, y, z);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        return driver;
    }

    /** {@link #mint} in this scene's own level. */
    public static ServerWorldDriver mint(SceneContext ctx, double x, double y, double z) {
        return mint(ctx, ctx.level(), x, y, z);
    }

    /** {@link #mint} standing in the centre of {@code foot}, in this scene's own level. */
    public static ServerWorldDriver mint(SceneContext ctx, BlockPos foot) {
        return mint(ctx, foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
    }

    /**
     * A bot player that is also UNREGISTERED from {@link ServerAvatarManager}, with an empty inventory.
     *
     * <p>The shape the six copied {@code body(ctx, foot)} helpers had grown. The extra
     * {@code unregister} matters for a bot that was handed a {@code BotProcess}: discarding the
     * entity does not take it off the manager's tick list, and a process still ticking against a
     * discarded player is a leak that poisons the next scene rather than failing this one.
     */
    public static ServerWorldDriver managed(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        ServerWorldDriver driver = bare(ctx, level, x, y, z);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        return driver;
    }

    /** {@link #managed} in this scene's own level. */
    public static ServerWorldDriver managed(SceneContext ctx, double x, double y, double z) {
        return managed(ctx, ctx.level(), x, y, z);
    }

    /** {@link #managed} standing in the centre of {@code foot}, in this scene's own level. */
    public static ServerWorldDriver managed(SceneContext ctx, BlockPos foot) {
        return managed(ctx, foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
    }

    /**
     * The gate and the mint, and nothing else — <b>the caller owes a cleanup.</b>
     *
     * <p>For the sites that mint two bot players and discard both in one lambda. They cannot use
     * {@link #mint} without changing how many cleanups run in what order, which is a behaviour
     * change on the dedicated server for no gain. What they must NOT do is call
     * {@code ServerWorldDriver.createIsolated} directly — that is the one path that bypasses the
     * rule, and the reason this method exists is to leave no excuse for taking it.
     */
    public static ServerWorldDriver bare(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        refuseWhereAClientShouldDrive(ctx);
        return ServerWorldDriver.createIsolated(level, x, y, z);
    }

    /** {@link #bare} in this scene's own level. */
    public static ServerWorldDriver bare(SceneContext ctx, double x, double y, double z) {
        return bare(ctx, ctx.level(), x, y, z);
    }

    /**
     * A bare {@link ServerPlayerBody}, for the scenes that never wanted a driver around it.
     *
     * <p><b>This is the other half of the rule, and forgetting it would have made the first half a
     * lie.</b> {@code ServerWorldDriver.createIsolated} is only one of two ways a scene mints a bot player:
     * ninety sites take that one, and NINETY-TWO more call {@code ServerPlayerBody.createUnique}
     * directly because they want to pose and step a player without a driver wrapped around it. Both
     * bottom out in {@code ServerAvatarBodies.unique}, so both produce a {@code JoinedBody} — a
     * gate that covered only the first would have left the integrated topology
     * minting roughly half as many bot players as before and reported the rule as enforced.
     *
     * <p>Deliberately nothing but the gate and the mint: the call sites downstream differ too much
     * to share a tail, and the value of this method is that substituting it for
     * {@code ServerPlayerBody.createUnique} at a call site cannot change what that site does.
     */
    public static ServerPlayerBody avatar(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        refuseWhereAClientShouldDrive(ctx);
        return ServerPlayerBody.createUnique(level, x, y, z);
    }

    /** {@link #avatar} in this scene's own level. */
    public static ServerPlayerBody avatar(SceneContext ctx, double x, double y, double z) {
        return avatar(ctx, ctx.level(), x, y, z);
    }

    // A `mintingIsLegal(ctx)` used to sit here, for "the scene that must BRANCH rather than skip".
    // Its javadoc named its one caller, `wd.bodyParityCensus`, and that scene stopped calling it:
    // it mints DIRECTLY (`new JoinedPlayerBodies()`; its fake-player column now records unavailable)
    // precisely so the census is taken on every run rather than branching on the run's shape. A
    // predicate whose only documented caller no longer asks it reads, on the next pass, like a
    // guard that is being honoured somewhere. Deleted rather than left: the live gate is
    // `refuseWhereAClientShouldDrive`, and `bare` / `avatar` — the two that touch the world, and
    // therefore every path `mint` reaches — already call it.

    /**
     * A driven {@link DrivenPiglin} standing in the centre of {@code foot}, discarded when the scene
     * resolves.
     *
     * <p>Legal on every topology, unlike {@link #mint}: the 2026-08-20 rule is about headless bot
     * PLAYERS on a server a client hosts, and a mob is not one; a client in the world just sees it.
     * Invulnerable like the bot players, so a scene measures the walks, not whether a fall or a pool
     * kills the NPC.
     */
    public static LivingBody npc(SceneContext ctx, BlockPos foot) {
        ServerLevel level = ctx.level();
        DrivenPiglin mob = NpcContent.DRIVEN_PIGLIN.get().create(level);
        if (mob == null) throw new IllegalStateException("worlddriver:driven_piglin created no entity");
        mob.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 0f, 0f);
        mob.setInvulnerable(true);
        level.addFreshEntity(mob);
        mob.setDriven(true);
        ctx.cleanup(mob::discard);
        return new LivingBody(mob);
    }

    /**
     * The predicate — <b>deliberately time-invariant, and deliberately NOT the same as
     * {@code JourneyRig.realPlayerHelm}.</b>
     *
     * <p>That one also asks "is a human in the player list right now", because it is about to ADOPT
     * one and would get a {@link NullPointerException} otherwise. This one is about to REFUSE, and
     * asking the same question here would be a bug: the suite arms at {@code SERVER_STARTED}, which
     * on an integrated server is before the local player has been placed, so the answer CHANGES
     * PART-WAY THROUGH A RUN. The scenes that happened to run before the player joined would mint,
     * the ones after would skip, and which scenes fell on which side would be decided by how fast
     * the machine booted. A gate whose coverage depends on boot timing reports a different set of
     * skips every run and none of them mean anything.
     *
     * <p>So the question asked here is only about the SHAPE OF THE JVM, and both halves of it are
     * settled before the first scene: is this a client hosting its own world, and is the client half
     * of the driver in this process. Whether a player has actually arrived is a separate concern,
     * and it belongs to the run configuration — {@code stagewrightIntegratedServer} sets
     * {@code stagewright.awaitPlayer} so a run cannot start without one. {@link #hasHumanPlayer}
     * stays as a reading for whoever wants it, and is not part of the decision.
     */
    private static boolean aClientShouldDrive(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null || server.isDedicatedServer()) return false;
        return BotHooks.isAvailable();
    }

    private static void refuseWhereAClientShouldDrive(SceneContext ctx) {
        if (!aClientShouldDrive(ctx)) return;
        ctx.skip("The integrated server has a real client. Under the 2026-08-20 player-entity selection rule, "
                + "a headless player entity must not be created here (JoinedBody is reserved for the dedicated "
                + "test server). This scene's execution has not yet moved to the client driver (BotApi.runProcess); "
                + "its coverage is counted on the stagewrightDedicatedServer* gates.");
    }

    /** Whether anyone in the player list is a person rather than one of our own bot players. A reading,
     *  not a gate — see {@link #aClientShouldDrive} for why it must not decide anything here.
     *
     *  <p>It asks {@code instanceof JoinedBody}, which is now exactly {@link #isDriverMinted}: the
     *  other minted type it used to miss was deleted. */
    public static boolean hasHumanPlayer(SceneContext ctx) {
        for (ServerPlayer p : ctx.players()) {
            if (!(p instanceof JoinedPlayerBodies.JoinedBody)) return true;
        }
        return false;
    }

    /**
     * Whether this player is one the driver minted, rather than a person's.
     *
     * <p><b>A named method, not a copied {@code instanceof}.</b> While a second minted type existed
     * ({@code AvatarFakePlayer}, since deleted with the other fake player type), three of four places
     * asked for only one of them, and {@code WorldDriverActuatorSplitScenes} disagreed with itself
     * twenty lines apart: its {@code humanPlayers()} admitted a fake player and its own next check failed the
     * scene with "picked the wrong player entity". If a minted player type is ever added again, this is the one place
     * to add it.
     *
     * <p>Asked STRUCTURALLY. {@code agent-body-N} is a naming convention
     * ({@code JoinedPlayerBodies.profileFor}) and conventions get changed by people who do not know
     * a test reads them; the class is what the player types actually differ by.
     */
    public static boolean isDriverMinted(ServerPlayer p) {
        return p instanceof JoinedPlayerBodies.JoinedBody;
    }

    /** Everyone on this server who is not one of the driver's own bot players, by {@link #isDriverMinted}.
     *  Server scope, not level scope: a bot player in another dimension is still not a person, and a scene
     *  that scoped this to its own level would report "no real player" the moment the client walked into
     *  the nether. */
    public static List<ServerPlayer> humanPlayers(SceneContext ctx) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : ctx.players()) {
            if (!isDriverMinted(p)) out.add(p);
        }
        return out;
    }
}
