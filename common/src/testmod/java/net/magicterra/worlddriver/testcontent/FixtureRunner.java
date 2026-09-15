package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerTallies;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.DescendProcess;
import net.magicterra.worlddriver.bot.process.ElytraProcess;
import net.magicterra.worlddriver.bot.process.EscapeProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.magicterra.worlddriver.bot.stagewright.LivingBody;
import net.magicterra.worlddriver.bot.stagewright.NpcBodyHost;

/**
 * Runs one hand-built scene: puts a body at the start marker, walks the legs, and judges the
 * markers' hard conditions and the fixture's {@code expect} numbers. The same body serves both
 * homes — the suite ({@link #runAuto}, driven by StageWright's {@code ctx.advance()}) and an
 * in-place run from the chat bar or RPC ({@link #startInPlace}, driven by {@link #tickInPlace}
 * off Architectury's server tick) — so what a tester watched is what the gate later judges.
 *
 * <p>A player body is chosen by the topology, not by the file: a dedicated server mints a headless
 * body ({@link SceneBody#mint}), an integrated server adopts the real player
 * ({@link ClientHelm#adopt}); each refuses on the other and the scene skips saying so. An in-place
 * run may ask for one explicitly and gets the same refusal as an error. An NPC ({@link SceneBody#npc})
 * is legal on every topology, so a file whose {@code body} is {@code npc} or {@code npc:<name>} runs
 * on one, and so does an in-place run that asks for it.
 *
 * <p>Counts come off the process's own {@link Walker#tallies()} — the walker the leg actually
 * ran, on whichever helm — never off the JVM-wide statics, so two bodies in one process cannot
 * pollute each other's numbers. A leg whose process has no walker (escape, elytra) contributes
 * nothing and the auto lines say so.
 */
public final class FixtureRunner {
    private FixtureRunner() {}

    /** How a run ended. {@code auto} holds one entry per hard check and expect bound, {@code observed}
     *  the four quantities {@link FixtureVerdicts#OBSERVED}, {@code lines} the tester-facing report. */
    public record Outcome(String status, String reason, Map<String, Object> auto, Map<String, Object> observed,
                          List<String> lines) {
        public boolean passed() { return "pass".equals(status); }
    }

    // ------------------------------------------------------------------ the suite's entry

    /** The scene body for the suite: terrain at the harness origin, then the shared run. */
    public static void runAuto(SceneContext ctx, SceneFixture fixture, StructureTemplate template) {
        FixtureIO.placeTerrain(ctx.level(), fixture, template, ctx.origin());
        new Run(ctx, fixture, ctx.origin(), bodyFor(fixture, ctx.server()), null, null).start();
    }

    /** The file's NPC when its {@code body} names one, else {@link #bodyForTopology}. */
    public static String bodyFor(SceneFixture fixture, MinecraftServer server) {
        return isNpc(fixture.body()) ? fixture.body().trim() : bodyForTopology(server);
    }

    /** {@code npc}, or {@code npc:<name>} with the mob's name after the colon. */
    static boolean isNpc(String body) {
        String b = body == null ? "" : body.trim();
        return b.equals("npc") || b.startsWith("npc:");
    }

    /** Which body this JVM can drive: the integrated server with the client half present adopts the
     *  real player, anything else mints. The same two facts {@code SceneBody} and {@code ClientHelm}
     *  decide on, asked once here so the two helpers' skips name the same reason. */
    public static String bodyForTopology(MinecraftServer server) {
        boolean integrated = server != null && !server.isDedicatedServer();
        return integrated && BotHooks.isAvailable() ? "self" : "server";
    }

    // ------------------------------------------------------------------ in-place runs

    /** One in-place run, advanced by {@link #tickInPlace} until it resolves. */
    public static final class InPlace {
        public final String name;
        public final SceneFixture fixture;
        public final SceneContext ctx;
        public final CompletableFuture<Outcome> done = new CompletableFuture<>();
        private final Run run;

        private InPlace(String name, SceneFixture fixture, SceneContext ctx, Run run) {
            this.name = name;
            this.fixture = fixture;
            this.ctx = ctx;
            this.run = run;
        }
    }

    private static volatile InPlace active;

    /** The run in progress, or null. */
    public static InPlace activeRun() { return active; }

    /**
     * Starts an in-place run at {@code origin}; the terrain is whatever the world holds there.
     * Server thread only. Refuses while another run is going: two bodies on one tick hook would
     * share the markers and the report.
     *
     * @param body {@code server}, {@code self}, {@code npc} or {@code npc:<name>}; null picks {@link #bodyFor}
     * @param progress where the every-20-ticks line goes when the caller asked to watch, or null
     */
    public static InPlace startInPlace(ServerLevel level, String name, SceneFixture fixture, BlockPos origin,
                                       String body, Consumer<String> progress) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("scene.run: server thread only");
        InPlace going = active;
        if (going != null && !going.done.isDone()) {
            throw new IllegalStateException("scene.run: '" + going.name + "' is still running; wait for it or stop the server");
        }
        String asked = body == null ? "" : body.trim();
        // The name after npc: keeps its case; the three words do not.
        String kind = asked.isEmpty() ? bodyFor(fixture, level.getServer()) : isNpc(asked) ? asked : asked.toLowerCase(Locale.ROOT);
        if (!kind.equals("server") && !kind.equals("self") && !isNpc(kind)) {
            throw new IllegalArgumentException("scene.run: body must be server, self, npc or npc:<name>");
        }
        SceneContext ctx = new SceneContext(level, origin, fixture.chunkRadius());
        Run run = new Run(ctx, fixture, origin, kind, progress, name);
        InPlace ip = new InPlace(name, fixture, ctx, run);
        active = ip;
        try {
            run.start();
        } catch (RuntimeException e) {
            resolve(ip, e);
        }
        return ip;
    }

    /** Architectury {@code TickEvent.SERVER_POST}: one {@code advance()} of the in-place run. */
    public static void tickInPlace(MinecraftServer server) {
        InPlace ip = active;
        if (ip == null || ip.done.isDone()) return;
        try {
            SceneContext.Progress p = ip.ctx.advance();
            if (p == SceneContext.Progress.DONE) resolve(ip, null);
            else if (p == SceneContext.Progress.STEP_TIMEOUT) resolve(ip, new SceneFailure("timeout: " + ip.ctx.failureReason()));
        } catch (RuntimeException e) {
            resolve(ip, e);
        }
    }

    /** Settles an in-place run through every exit the context has: DONE, a soft-violation failure
     *  at drain, a step timeout, a skip, or an exception from a step. Cleanups always run — a pin
     *  left closed keeps {@code BotConfig} in the scene's state for the next thing on the server. */
    private static void resolve(InPlace ip, RuntimeException error) {
        String status;
        String reason;
        if (error == null) { status = "pass"; reason = ""; }
        else if (error instanceof SceneSkipped) { status = "skip"; reason = error.getMessage(); }
        else if (error instanceof SceneFailure) { status = "fail"; reason = error.getMessage(); }
        else { status = "error"; reason = String.valueOf(error); WorldDriverCommon.LOG.error("[scene.run] {} crashed", ip.name, error); }
        ip.ctx.runCleanups(w -> WorldDriverCommon.LOG.warn("[scene.run] {}: {}", ip.name, w));
        Outcome out = ip.run.outcome(status, reason);
        if (active == ip) active = null;
        ip.done.complete(out);
    }

    // ------------------------------------------------------------------ the run

    /** What a leg's process is driven by: the three helms behind one face. */
    private interface Helm {
        void start(BotProcess p);
        boolean busy();
        /** A player for {@code server} and {@code self}, the driven mob for {@code npc}. */
        LivingEntity entity();
        String describe();
        /** Let the body's client catch up with the adoption teleport before the first leg starts; a
         *  server body is where it was put the moment it was put there. */
        default void settle(Runnable then) { then.run(); }
    }

    private static final class Run {
        private final SceneContext ctx;
        private final SceneFixture f;
        private final BlockPos origin;
        private final String bodyKind;
        private final Consumer<String> progress;
        private final String name;

        private Helm helm;
        private final Map<String, Object> auto = new LinkedHashMap<>();
        private final Map<String, Object> observed = new LinkedHashMap<>();
        private final List<String> lines = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        // Watchers' state, absolute cells.
        private final Set<BlockPos> forbid = new HashSet<>();
        private String forbidHit;
        private final List<BlockPos> via = new ArrayList<>();
        private int viaReached;
        private final List<SceneFixture.Pass> pass;
        private final boolean[] passed;
        private int ticks;
        private int searches, hops, digs;
        private boolean tallied = true;

        Run(SceneContext ctx, SceneFixture f, BlockPos origin, String bodyKind, Consumer<String> progress, String name) {
            this.ctx = ctx;
            this.f = f;
            this.origin = origin;
            this.bodyKind = bodyKind;
            this.progress = progress;
            this.name = name == null ? f.name() : name;
            for (int[] c : f.markers().forbid()) forbid.add(FixtureIO.at(origin, c));
            for (SceneFixture.Via v : f.markers().via()) via.add(FixtureIO.at(origin, v.pos()));
            this.pass = f.markers().pass();
            this.passed = new boolean[pass.size()];
        }

        void start() {
            SceneFixture.Start start = f.markers().start();
            if (start == null) throw new SceneFailure("scene '" + f.name() + "' has no start marker");
            if (f.legs().isEmpty()) throw new SceneFailure("scene '" + f.name() + "' has no legs");
            ctx.record("scene", f.name());
            ctx.record("body", bodyKind);

            // The markers a tester left (or `place` put back) are not part of the scene it runs:
            // this scene's — inside its box, so a neighbouring scene keeps its own — come out now
            // and go back when the run ends, whichever way it ends.
            List<FixtureBuilder.Placed> lifted = FixtureIO.markersIn(ctx.level(), f, origin);
            FixtureIO.remove(ctx.level(), lifted);
            // Logged both ways: a scene once lost every marker between two runs and nothing said
            // which run, or whether the lift and the restore had even balanced.
            WorldDriverCommon.LOG.info("[scene.run] {}: lifted {} markers at {}", name, lifted.size(), origin.toShortString());
            ctx.cleanup(() -> {
                FixtureIO.putAll(ctx.level(), lifted);
                WorldDriverCommon.LOG.info("[scene.run] {}: restored {} markers at {}", name, lifted.size(), origin.toShortString());
            });

            var pin = BotConfig.pinnedBaseline();
            ctx.cleanup(pin::close);
            applyConfig(f.config());

            BlockPos foot = FixtureIO.at(origin, start.pos());
            if (isNpc(bodyKind) && (!f.hand().isEmpty() || !f.equip().isEmpty())) {
                throw new SceneFailure("scene '" + f.name() + "' gives hand or equip, and an npc body has no inventory");
            }
            helm = isNpc(bodyKind) ? npcHelm(foot, start.yaw())
                    : bodyKind.equals("self") ? clientHelm(foot, start.yaw()) : serverHelm(foot, start.yaw());
            LivingEntity body = helm.entity();
            body.setHealth(body.getMaxHealth());
            if (body instanceof ServerPlayer player) {
                player.getFoodData().setFoodLevel(20);
                giveHand(player, f.hand());
                equip(player, f.equip());
            }
            ctx.record("helm", helm.describe());
            helm.settle(() -> leg(0));
        }

        // ---- helms

        private Helm serverHelm(BlockPos foot, float yaw) {
            ServerWorldDriver driver = SceneBody.mint(ctx, ctx.level(), foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
            ServerPlayer fp = driver.fakePlayer();
            fp.setYRot(yaw);
            fp.setYHeadRot(yaw);
            fp.getInventory().clearContent();
            ctx.cleanup(() -> ServerAvatarManager.unregister(driver));
            return new Helm() {
                // The manager drops a finished driver, so every leg registers again.
                @Override public void start(BotProcess p) { driver.runProcess(p); ServerAvatarManager.register(driver); }
                @Override public boolean busy() { return !driver.finished(); }
                @Override public LivingEntity entity() { return fp; }
                @Override public String describe() { return "server body " + fp.getGameProfile().getName() + " (ServerAvatarManager)"; }
            };
        }

        private Helm clientHelm(BlockPos foot, float yaw) {
            ClientHelm helm = ClientHelm.adopt(ctx, foot, yaw);
            return new Helm() {
                @Override public void start(BotProcess p) { helm.bot().runProcess(p); }
                @Override public boolean busy() { return Boolean.TRUE.equals(helm.bot().userTaskLeg().get("busy")); }
                // The client trails the teleport by the move packets the server has not consumed
                // (six ticks measured); a walker that snapshots before that plans from the old cell.
                @Override public void settle(Runnable then) { helm.sync(10, then); }
                @Override public LivingEntity entity() { return helm.player(); }
                @Override public String describe() { return "real player " + helm.player().getGameProfile().getName() + " (BotApi.runProcess)"; }
            };
        }

        /**
         * A driven piglin under the {@link NpcBodyHost} the {@code body} param reaches NPCs through, so a
         * leg runs on it the way an order by name does. {@code npc:<name>} names the mob;
         * {@code npc:worlddriver:driven_piglin} is the one type there is. Not put in the registry: a
         * run is not something to send orders to.
         */
        private Helm npcHelm(BlockPos foot, float yaw) {
            String label = bodyKind.equals("npc") ? "" : bodyKind.substring("npc:".length());
            if (label.contains(":")) {
                if (!label.equals("worlddriver:driven_piglin")) {
                    throw new SceneFailure("body " + bodyKind + ": worlddriver:driven_piglin is the one npc type");
                }
                label = "";
            }
            LivingBody npc = SceneBody.npc(ctx, foot);
            LivingEntity mob = npc.entity();
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
            if (!label.isBlank()) mob.setCustomName(Component.literal(label));
            NpcBodyHost host = new NpcBodyHost(label.isBlank() ? name : label, npc);
            ctx.cleanup(() -> ServerAvatarManager.unregister(host));
            return new Helm() {
                @Override public void start(BotProcess p) { host.start(p); }
                @Override public boolean busy() { return host.busy(); }
                // A mob put on a cell's floor takes a few steps with no input before its first move finds it.
                @Override public void settle(Runnable then) { for (int i = 0; i < 5; i++) npc.step(); then.run(); }
                @Override public LivingEntity entity() { return mob; }
                @Override public String describe() { return "npc " + host.id() + " (NpcBodyHost)"; }
            };
        }

        // ---- legs

        private void leg(int i) {
            if (i >= f.legs().size()) { finish(); return; }
            SceneFixture.Leg leg = f.legs().get(i);
            BotProcess process = process(leg, i);
            Walker walker = process instanceof IntentProcess ip ? ip.walker()
                    : process instanceof MineProcess mp ? mp.walker() : null;
            if (walker == null) tallied = false;
            BlockPos goal = leg.goal() == null ? null : FixtureIO.at(origin, leg.goal());
            String tag = "leg." + i;
            helm.start(process);
            ctx.record(tag + ".started", leg.verb() + (goal == null ? "" : " → " + goal.toShortString()));
            final int[] waited = { 0 };
            final boolean[] ended = { false };
            ctx.await(() -> {
                watch(waited[0]);
                if (!helm.busy()) { ended[0] = true; return true; }
                return ++waited[0] >= leg.budget();
            }).within(leg.budget() + 100).then(() -> {
                ticks += waited[0];
                if (walker != null) {
                    WalkerTallies t = walker.tallies();
                    searches += t.searches;
                    hops += t.recoveryHops;
                    digs += t.digs;
                }
                LivingEntity body = helm.entity();
                String where = String.format(Locale.ROOT, "%.2f,%.2f,%.2f", body.getX(), body.getY(), body.getZ());
                boolean arrived = goal != null && arrived(body, goal, leg.goalKind());
                String how = ended[0] ? "ended at tick " + waited[0] : "budget " + leg.budget() + " spent, process still busy";
                ctx.record(tag, how + ", at " + where + (goal == null ? "" : ", arrived=" + arrived));
                if (goal != null) {
                    auto.put("arrive." + i, arrived);
                    line(arrived, "leg " + i + " " + leg.verb() + " arrived at " + goal.toShortString()
                            + " (" + how + ")");
                    if (!arrived) failures.add("leg " + i + " did not arrive at " + goal.toShortString() + ": " + how);
                } else if (!ended[0]) {
                    auto.put("finish." + i, false);
                    line(false, "leg " + i + " " + leg.verb() + " did not finish within " + leg.budget() + " ticks");
                    failures.add("leg " + i + " " + leg.verb() + " did not finish");
                } else {
                    auto.put("finish." + i, true);
                    line(true, "leg " + i + " " + leg.verb() + " finished at tick " + waited[0]);
                }
                leg(i + 1);
            });
        }

        private BotProcess process(SceneFixture.Leg leg, int i) {
            String verb = leg.verb() == null ? "goto" : leg.verb().trim().toLowerCase(Locale.ROOT);
            Params p = Params.of(leg.params() == null ? Map.of() : leg.params());
            switch (verb) {
                case "goto" -> {
                    if (leg.goal() == null) throw new SceneFailure("leg " + i + ": goto needs a goal");
                    Map<String, Object> route = new LinkedHashMap<>(leg.route() == null ? Map.of() : leg.route());
                    List<Goal> targets = new ArrayList<>();
                    if (route.get("via") instanceof List<?> vl) {
                        for (Object o : vl) targets.add(new Goal.Near(FixtureIO.at(origin, SceneFixture.intsOf(o, "route.via[]", 3)), 1));
                        route.remove("via");
                    }
                    RouteParams.Parsed r = RouteParams.parse(route);
                    if (r.fly()) throw new SceneFailure("leg " + i + ": route.mode fly is the elytra verb here");
                    targets.add(goal(FixtureIO.at(origin, leg.goal()), leg.goalKind()));
                    return new IntentProcess(new Intent(targets, r.profile().bias(), r.profile().capability(),
                            r.profile().constraints(), r.entityLeash()));
                }
                case "mine" -> {
                    List<String> ids = p.getStringList("block");
                    if (ids.isEmpty()) ids = p.getStringList("blocks");
                    if (ids.isEmpty()) throw new SceneFailure("leg " + i + ": mine needs params.block");
                    return new MineProcess(ids, Math.max(1, p.getInt("count", 1)), p.getIntClamped("radius", 16, 1, 64));
                }
                case "escape" -> {
                    if (!(p.get("targetY") instanceof Number n)) throw new SceneFailure("leg " + i + ": escape needs params.targetY (origin-relative)");
                    int targetY = origin.getY() + n.intValue();
                    boolean down = targetY < helm.entity().blockPosition().getY();
                    return down ? new DescendProcess(targetY) : new EscapeProcess(targetY);
                }
                case "elytra" -> {
                    BlockPos target = p.get("pos") == null ? null : FixtureIO.at(origin, SceneFixture.intsOf(p.get("pos"), "params.pos", 3));
                    Float yaw = p.get("yaw") instanceof Number n ? n.floatValue() : null;
                    boolean hasPitch = p.get("pitch") instanceof Number;
                    float pitch = (float) p.getDouble("pitch", 0.0);
                    boolean reactive = target != null && p.getBool("reactive", !hasPitch);
                    boolean fireworks = reactive ? !Boolean.FALSE.equals(p.get("fireworks")) : p.getBool("fireworks");
                    return new ElytraProcess(target, yaw, pitch, fireworks, p.getIntClamped("fireworkEveryTicks", 40, 5, 400),
                            p.getIntClamped("ticks", reactive ? 2000 : 200, 1, 20_000), p.getDouble("stopXZDist", 3.0), reactive);
                }
                default -> throw new SceneFailure("leg " + i + ": unknown verb '" + verb + "' (goto|mine|escape|elytra)");
            }
        }

        private static Goal goal(BlockPos abs, String kind) {
            String k = kind == null ? "block" : kind.trim();
            if (k.startsWith("near:")) return new Goal.Near(abs, Integer.parseInt(k.substring(5).trim()));
            if (k.equals("y:") || k.equals("y")) return new Goal.YLevel(abs.getY());
            return new Goal.Block(abs);
        }

        /** Arrival as the marker meant it: a near goal within its radius, a y goal on its level,
         *  a block goal with the feet on the cell (one cell of slack, the walker's own tolerance). */
        private static boolean arrived(LivingEntity body, BlockPos goal, String kind) {
            String k = kind == null ? "block" : kind.trim();
            BlockPos feet = body.blockPosition();
            if (k.equals("y:") || k.equals("y")) return feet.getY() == goal.getY();
            int r = k.startsWith("near:") ? Integer.parseInt(k.substring(5).trim()) : 1;
            return Math.abs(feet.getX() - goal.getX()) <= r && Math.abs(feet.getZ() - goal.getZ()) <= r
                    && Math.abs(feet.getY() - goal.getY()) <= Math.max(1, r);
        }

        // ---- the per-tick watcher

        private void watch(int tick) {
            LivingEntity body = helm.entity();
            BlockPos feet = body.blockPosition();
            BlockPos head = feet.above();
            if (forbidHit == null && (forbid.contains(feet) || forbid.contains(head))) {
                forbidHit = (forbid.contains(feet) ? feet : head).toShortString() + " at tick " + (ticks + tick);
            }
            if (viaReached < via.size() && near(feet, via.get(viaReached), 1)) viaReached++;
            for (int i = 0; i < pass.size(); i++) {
                if (!passed[i] && near(feet, FixtureIO.at(origin, pass.get(i).pos()), pass.get(i).radius())) passed[i] = true;
            }
            if (progress != null && tick % 20 == 0) {
                int done = 0;
                for (boolean b : passed) if (b) done++;
                progress.accept(String.format(Locale.ROOT, "t=%d pos=%.1f,%.1f,%.1f via %d/%d pass %d/%d%s",
                        ticks + tick, body.getX(), body.getY(), body.getZ(), viaReached, via.size(), done, pass.size(),
                        forbidHit == null ? "" : " FORBID " + forbidHit));
            }
        }

        private static boolean near(BlockPos feet, BlockPos cell, int r) {
            return Math.abs(feet.getX() - cell.getX()) <= r && Math.abs(feet.getZ() - cell.getZ()) <= r
                    && Math.abs(feet.getY() - cell.getY()) <= Math.max(1, r);
        }

        // ---- the end

        private void finish() {
            LivingEntity body = helm.entity();
            if (!via.isEmpty()) {
                boolean ok = viaReached == via.size();
                auto.put("via", ok);
                line(ok, "via " + viaReached + "/" + via.size() + " reached in order");
                if (!ok) failures.add("via " + viaReached + "/" + via.size());
            }
            if (!pass.isEmpty()) {
                int done = 0;
                for (boolean b : passed) if (b) done++;
                boolean ok = done == pass.size();
                auto.put("pass", ok);
                line(ok, "pass " + done + "/" + pass.size());
                if (!ok) failures.add("pass " + done + "/" + pass.size());
            }
            if (!forbid.isEmpty()) {
                boolean ok = forbidHit == null;
                auto.put("forbid", ok);
                line(ok, ok ? "never entered a forbid cell" : "entered forbid cell " + forbidHit);
                if (!ok) failures.add("forbid " + forbidHit);
            }
            if (f.markers().stand() != null) {
                BlockPos stand = FixtureIO.at(origin, f.markers().stand());
                boolean ok = body.blockPosition().equals(stand);
                auto.put("stand", ok);
                line(ok, "stands on " + stand.toShortString() + (ok ? "" : ", is on " + body.blockPosition().toShortString()));
                if (!ok) failures.add("stand: on " + body.blockPosition().toShortString() + " not " + stand.toShortString());
            }
            List<SceneFixture.Watch> watches = f.markers().watch();
            for (int i = 0; i < watches.size(); i++) {
                SceneFixture.Watch w = watches.get(i);
                BlockPos cell = FixtureIO.at(origin, w.pos());
                String now = BuiltInRegistries.BLOCK.getKey(ctx.level().getBlockState(cell).getBlock()).toString();
                String want = w.want() == null || w.want().equals("same") ? w.was() : w.want();
                boolean ok = want != null && want.equals(now);
                auto.put("watch." + i, ok);
                line(ok, "watch " + cell.toShortString() + " is " + now + (ok ? "" : ", wanted " + want));
                if (!ok) failures.add("watch " + cell.toShortString() + " is " + now + " not " + want);
            }

            observed.put("ticks", ticks);
            if (tallied) {
                observed.put("repaths", searches);
                observed.put("hops", hops);
                observed.put("digs", digs);
            } else {
                line(true, "repaths/hops/digs not observed: a leg's process has no walker");
            }
            Map<String, String> judged = FixtureVerdicts.judge(f.expect(), observed);
            for (var e : judged.entrySet()) {
                boolean ok = !e.getValue().startsWith("over ");
                auto.put("expect." + e.getKey(), e.getValue());
                line(ok, "expect " + e.getKey() + ": " + e.getValue());
                if (!ok) failures.add("expect " + e.getKey() + " " + e.getValue());
            }
            for (var e : auto.entrySet()) ctx.record("auto." + e.getKey(), e.getValue());
            for (var e : observed.entrySet()) ctx.record("observed." + e.getKey(), e.getValue());
            if (!failures.isEmpty()) ctx.fail("human scene " + name + ": " + String.join("; ", failures));
        }

        private void line(boolean ok, String text) {
            lines.add((ok ? "✓ " : "✗ ") + text);
        }

        Outcome outcome(String status, String reason) {
            return new Outcome(status, reason == null ? "" : reason, new LinkedHashMap<>(auto),
                    new LinkedHashMap<>(observed), List.copyOf(lines));
        }

        // ---- staging helpers

        /** {@code config} keys are {@link BotConfig} field names; the pin restores them all. */
        private static void applyConfig(Map<String, Object> config) {
            for (var e : config.entrySet()) {
                try {
                    var field = BotConfig.class.getField(e.getKey());
                    Object v = e.getValue();
                    Class<?> t = field.getType();
                    if (t == boolean.class) field.setBoolean(null, Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)));
                    else if (t == int.class) field.setInt(null, ((Number) v).intValue());
                    else if (t == long.class) field.setLong(null, ((Number) v).longValue());
                    else if (t == double.class) field.setDouble(null, ((Number) v).doubleValue());
                    else if (t == float.class) field.setFloat(null, ((Number) v).floatValue());
                    else field.set(null, v);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    throw new SceneFailure("config." + e.getKey() + ": " + ex.getMessage());
                }
            }
        }

        /** {@code "minecraft:dirt 16"} per hotbar slot, slot 0 selected. */
        private static void giveHand(ServerPlayer body, List<String> hand) {
            int slot = 0;
            for (String spec : hand) {
                String[] parts = spec.trim().split("\\s+");
                Item item = item(parts[0]);
                int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                if (slot < 9) body.getInventory().items.set(slot++, new ItemStack(item, count));
                else body.getInventory().add(new ItemStack(item, count));
            }
            body.getInventory().selected = 0;
        }

        private static void equip(ServerPlayer body, Map<String, String> equip) {
            for (var e : equip.entrySet()) {
                EquipmentSlot slot = EquipmentSlot.byName(e.getKey().toLowerCase(Locale.ROOT));
                body.setItemSlot(slot, new ItemStack(item(e.getValue())));
            }
        }

        private static Item item(String id) {
            ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) throw new SceneFailure("unknown item '" + id + "'");
            return BuiltInRegistries.ITEM.get(rl);
        }
    }

    // ------------------------------------------------------------------ resources

    /** The suite's copy of a fixture's terrain, read from the testmod jar. */
    public static StructureTemplate templateFromResource(ServerLevel level, String resourcePath) throws IOException {
        try (var in = FixtureRunner.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("resource not found: " + resourcePath);
            return FixtureIO.loadNbt(level, in);
        }
    }
}
