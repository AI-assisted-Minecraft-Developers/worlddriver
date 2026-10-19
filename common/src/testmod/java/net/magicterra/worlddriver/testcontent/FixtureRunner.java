package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Legs;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Runs one hand-built scene: puts a bot at the start marker, runs each task in {@code legs}, and
 * judges the markers' hard conditions and the fixture's {@code expect} numbers. The same code
 * serves both homes — the suite ({@link #runAuto}, driven by StageWright's {@code ctx.advance()}) and an
 * in-place run from the chat bar or RPC ({@link #startInPlace}, driven by {@link #tickInPlace}
 * off Architectury's server tick) — so what a tester watched is what the gate later judges.
 *
 * <p>The kind of player is chosen by the topology, not by the file: a dedicated server spawns a
 * server-side player ({@link SceneBot#spawn}), an integrated server adopts the real player
 * ({@link ClientHelm#adopt}); each refuses on the other and the scene skips saying so. An in-place
 * run may ask for one explicitly and gets the same refusal as an error. An NPC (a driven piglin the
 * roster adopts) is legal on every topology, so a file whose {@code body} is {@code npc} or
 * {@code npc:<name>} runs on one, and so does an in-place run that asks for it.
 *
 * <p>Each task is the {@code mc.bot.*} verb its {@code verb} names, sent to the bot by name, so a task
 * runs exactly as an order from a client would. Counts come off {@code mc.debug.navigator} for that bot
 * after each task: the navigator the task actually ran, so two bots in one process cannot pollute each
 * other's numbers. A task whose process has no navigator (escape, elytra) contributes nothing and the
 * auto lines say so.
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

    /** Which kind of bot this JVM can drive: an integrated server adopts the real player, a dedicated one
     *  spawns. The fact {@code SceneBot} and {@code ClientHelm} decide on, asked once here so the two
     *  helpers' skips name the same reason. */
    public static String bodyForTopology(MinecraftServer server) {
        return server != null && !server.isDedicatedServer() ? "self" : "server";
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
     * Server thread only. Refuses while another run is going: two bots on one tick hook would
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
     *  left closed keeps the settings in the scene's state for the next thing on the server. */
    private static void resolve(InPlace ip, RuntimeException error) {
        String status;
        String reason;
        if (error == null) { status = "pass"; reason = ""; }
        else if (error instanceof SceneSkipped) { status = "skip"; reason = error.getMessage(); }
        else if (error instanceof SceneFailure) { status = "fail"; reason = error.getMessage(); }
        else { status = "error"; reason = String.valueOf(error); TestMod.LOG.error("[scene.run] {} crashed", ip.name, error); }
        ip.ctx.runCleanups(w -> TestMod.LOG.warn("[scene.run] {}: {}", ip.name, w));
        Outcome out = ip.run.outcome(status, reason);
        if (active == ip) active = null;
        ip.done.complete(out);
    }

    // ------------------------------------------------------------------ the run

    /** How long a reading may take to answer, in server ticks. */
    private static final int REPLY_TICKS = 40;

    /** The bot the tasks are sent to, and what differs between the three kinds. */
    private interface Helm {
        /** What every order names. */
        String ref();
        /** A player for {@code server} and {@code self}, the driven mob for {@code npc}. */
        LivingEntity entity();
        String describe();
        /** Let the bot's client catch up with the adoption teleport before the first task starts; a
         *  server-side player is where it was put the moment it was put there. */
        default void settle(Runnable then) { then.run(); }
        /** Let the server read where the bot stopped before a task is judged. A client player reaches
         *  the server a move packet late, so its navigator can say arrived while the server still has it
         *  a step short; a server-side player and an NPC are read where they are. */
        default void caughtUp(Runnable then) { then.run(); }
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
        private long searches, hops, digs;
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
            if (f.legs().isEmpty()) throw new SceneFailure("scene '" + f.name() + "' has an empty legs list");
            ctx.record("scene", f.name());
            ctx.record("body", bodyKind);

            // The markers a tester left (or `place` put back) are not part of the scene it runs:
            // this scene's — inside its box, so a neighbouring scene keeps its own — come out now
            // and go back when the run ends, whichever way it ends.
            List<FixtureBuilder.Placed> lifted = FixtureIO.markersIn(ctx.level(), f, origin);
            FixtureIO.remove(ctx.level(), lifted);
            // Logged both ways: a scene once lost every marker between two runs and nothing said
            // which run, or whether the lift and the restore had even balanced.
            TestMod.LOG.info("[scene.run] {}: lifted {} markers at {}", name, lifted.size(), origin.toShortString());
            ctx.cleanup(() -> {
                FixtureIO.putAll(ctx.level(), lifted);
                TestMod.LOG.info("[scene.run] {}: restored {} markers at {}", name, lifted.size(), origin.toShortString());
            });

            Harness.pin(ctx);

            BlockPos foot = FixtureIO.at(origin, start.pos());
            if (isNpc(bodyKind) && (!f.hand().isEmpty() || !f.equip().isEmpty())) {
                throw new SceneFailure("scene '" + f.name() + "' gives hand or equip, and an npc has no inventory");
            }
            helm = isNpc(bodyKind) ? npcHelm(foot, start.yaw())
                    : bodyKind.equals("self") ? clientHelm(foot, start.yaw()) : serverHelm(foot, start.yaw());
            // After the helm: adopting the real player pins the baseline again, so settings applied
            // before it never reached the navigator that player's tasks run on.
            Harness.set(f.config());
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
            SceneBot bot = SceneBot.spawn(ctx, foot);
            ServerPlayer fp = bot.player();
            fp.setYRot(yaw);
            fp.setYHeadRot(yaw);
            fp.getInventory().clearContent();
            return new Helm() {
                @Override public String ref() { return bot.ref(); }
                @Override public LivingEntity entity() { return fp; }
                @Override public String describe() { return "server-side player " + fp.getGameProfile().getName() + " (" + bot.ref() + ")"; }
            };
        }

        private Helm clientHelm(BlockPos foot, float yaw) {
            ClientHelm helm = ClientHelm.adopt(ctx, foot, yaw);
            return new Helm() {
                @Override public String ref() { return helm.ref(); }
                // The client trails the teleport by the move packets the server has not consumed
                // (six ticks measured); a navigator that snapshots before that plans from the old cell.
                @Override public void settle(Runnable then) { helm.sync(10, then); }
                // The same packets trail a task's last steps; ten ticks outlast the six measured.
                @Override public void caughtUp(Runnable then) { helm.sync(10, then); }
                @Override public LivingEntity entity() { return helm.player(); }
                @Override public String describe() { return "real player " + helm.player().getGameProfile().getName() + " (self)"; }
            };
        }

        /**
         * A driven piglin the roster adopts, so a task runs on it the way an order by name does.
         * {@code npc:<name>} names the mob; {@code npc:worlddriver:driven_piglin} is the one type there is.
         */
        private Helm npcHelm(BlockPos foot, float yaw) {
            String label = bodyKind.equals("npc") ? "" : bodyKind.substring("npc:".length());
            if (label.contains(":")) {
                if (!label.equals("worlddriver:driven_piglin")) {
                    throw new SceneFailure("body " + bodyKind + ": worlddriver:driven_piglin is the one npc type");
                }
                label = "";
            }
            ServerLevel level = ctx.level();
            DrivenPiglin mob = NpcContent.DRIVEN_PIGLIN.get().create(level);
            if (mob == null) throw new SceneFailure("worlddriver:driven_piglin created no entity");
            mob.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, yaw, 0f);
            mob.setYHeadRot(yaw);
            // Invulnerable, so the run measures the walk and not whether a fall or a pool kills the NPC.
            mob.setInvulnerable(true);
            level.addFreshEntity(mob);
            ctx.cleanup(mob::discard);
            if (!label.isBlank()) mob.setCustomName(Component.literal(label));
            String rosterName = rosterName(label.isBlank() ? name : label);
            Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).roster(
                    new ControlContracts.Adopt(mob.getId(), Optional.of(rosterName))));
            if (!(r instanceof Responses.Rostered rostered)) throw new SceneFailure("mc.bot.roster adopt answered " + r);
            String ref = rostered.bot();
            ctx.cleanup(() -> Calls.now(ControlApi.of(Calls.api()).roster(new ControlContracts.Remove(ref))));
            return new Helm() {
                @Override public String ref() { return ref; }
                // A mob put on a cell's floor takes a few steps with no input before its first move finds it.
                @Override public void settle(Runnable then) { Harness.step(ref, 5); then.run(); }
                @Override public LivingEntity entity() { return mob; }
                @Override public String describe() { return "npc " + ref; }
            };
        }

        /** A roster name from a free label: letters, digits and underscores, sixteen at most. */
        private static String rosterName(String label) {
            String n = label.replaceAll("[^A-Za-z0-9_]", "_");
            return n.isEmpty() ? "npc" : n.substring(0, Math.min(16, n.length()));
        }

        // ---- tasks

        private void leg(int i) {
            if (i >= f.legs().size()) { finish(); return; }
            SceneFixture.Leg leg = f.legs().get(i);
            String verb = verb(leg);
            BlockPos goal = leg.goal() == null ? null : FixtureIO.at(origin, leg.goal());
            String tag = "leg." + i;
            ctx.record(tag + ".order", leg.verb() + (goal == null ? "" : " → " + goal.toShortString()));
            Legs.run(ctx, tag, helm.ref(), order(verb, leg, i), leg.budget(), this::watch, end -> {
                // Where the server had the bot the tick the task ended, before a client's last move
                // packets land: the reading this runner judged by until it waited for them.
                LivingEntity seen = helm.entity();
                String endedAt = String.format(Locale.ROOT, "%.2f,%.2f,%.2f", seen.getX(), seen.getY(), seen.getZ());
                helm.caughtUp(() -> read(DebugApi.of(Calls.api()).navigator(BotDebugContracts.NavigatorParams.of(helm.ref())),
                        nav -> judgeLeg(i, leg, verb, goal, end, endedAt, nav)));
            });
        }

        private void judgeLeg(int i, SceneFixture.Leg leg, String verb, BlockPos goal, Legs.End end, String endedAt,
                              BotDebugContracts.NavigatorReading nav) {
            String tag = "leg." + i;
            ticks += end.ticks();
            if (nav.counters().containsKey("searches")) {
                searches += nav.counter("searches");
                hops += nav.counter("recoveryHops");
                digs += nav.counter("digs");
            } else {
                tallied = false;
            }
            LivingEntity body = helm.entity();
            String where = String.format(Locale.ROOT, "%.2f,%.2f,%.2f", body.getX(), body.getY(), body.getZ());
            boolean arrived = goal != null && arrived(body, goal, leg.goalKind());
            String how = end.ended() ? "ended at tick " + end.ticks() : "budget " + leg.budget() + " spent, task still running";
            // The task's own end beside the run's: arrived here allows a cell of slack, and a walk that
            // stopped a cell short reads arrived=true with the task saying why it stopped.
            String endReason = end.slot(slotOf(verb)).flatMap(ControlContracts.Slot::endReason).orElse("unavailable");
            ctx.record(tag, how + ", at " + where + (where.equals(endedAt) ? "" : " (" + endedAt + " as it ended)")
                    + (goal == null ? "" : ", arrived=" + arrived) + ", task ended " + endReason);
            // What the fixture's settings read when the task ends, not when they were applied.
            if (!f.config().isEmpty()) {
                Map<String, Object> now = new LinkedHashMap<>();
                for (String k : f.config().keySet()) {
                    try { now.put(k, Harness.get(k)); }
                    catch (RuntimeException e) { now.put(k, "unreadable"); }
                }
                ctx.record(tag + ".config", String.valueOf(now));
            }
            if (goal != null) {
                auto.put("arrive." + i, arrived);
                line(arrived, "task " + i + " " + leg.verb() + " arrived at " + goal.toShortString() + " (" + how + ")");
                if (!arrived) failures.add("task " + i + " did not arrive at " + goal.toShortString() + ": " + how);
            } else if (!end.ended()) {
                auto.put("finish." + i, false);
                line(false, "task " + i + " " + leg.verb() + " did not finish within " + leg.budget() + " ticks");
                failures.add("task " + i + " " + leg.verb() + " did not finish");
            } else {
                auto.put("finish." + i, true);
                line(true, "task " + i + " " + leg.verb() + " finished at tick " + end.ticks());
            }
            leg(i + 1);
        }

        /** Continues with {@code call}'s answer; a call that fails fails the scene. */
        private <R> void read(CompletionStage<R> call, Consumer<R> then) {
            CompletableFuture<R> reply = call.toCompletableFuture();
            ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> then.accept(Calls.now(reply)));
        }

        private static String verb(SceneFixture.Leg leg) {
            return leg.verb() == null ? "goto" : leg.verb().trim().toLowerCase(Locale.ROOT);
        }

        private static Function<ControlContracts.Slots, ControlContracts.Slot> slotOf(String verb) {
            return switch (verb) {
                case "mine" -> ControlContracts.Slots::mine;
                case "escape" -> ControlContracts.Slots::escape;
                case "elytra" -> ControlContracts.Slots::elytra;
                default -> ControlContracts.Slots::goTo;
            };
        }

        /** The {@code mc.bot.*} order a leg names, sent to the helm's bot. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private CompletionStage<Responses.ActionResponse<Object>> order(String verb, SceneFixture.Leg leg, int i) {
            String ref = helm.ref();
            LegParams p = new LegParams(leg.params() == null ? Map.of() : leg.params());
            CompletionStage<? extends Responses.ActionResponse<?>> order = switch (verb) {
                case "goto" -> {
                    if (leg.goal() == null) throw new SceneFailure("task " + i + ": goto needs a goal");
                    Map<String, Object> route = new LinkedHashMap<>(leg.route() == null ? Map.of() : leg.route());
                    List<BlockPos> legVia = new ArrayList<>();
                    if (route.remove("via") instanceof List<?> vl) {
                        for (Object o : vl) legVia.add(FixtureIO.at(origin, SceneFixture.intsOf(o, "route.via[]", 3)));
                    }
                    RouteContracts.Route r = RouteContracts.ROUTE.decode(SceneJson.params(route));
                    if (r.fly()) throw new SceneFailure("task " + i + ": route.mode fly is the elytra verb here");
                    r = Routes.of(r).via(legVia.toArray(BlockPos[]::new)).build();
                    yield SceneBot.tasks().goTo(new GotoContracts.Params(ref,
                            Optional.of(target(FixtureIO.at(origin, leg.goal()), leg.goalKind())), false, Optional.of(r),
                            Optional.empty(), false, Optional.empty(), 0));
                }
                case "mine" -> {
                    List<String> ids = p.getStringList("block");
                    if (ids.isEmpty()) ids = p.getStringList("blocks");
                    if (ids.isEmpty()) throw new SceneFailure("task " + i + ": mine needs params.block");
                    yield SceneBot.tasks().mine(new GatherContracts.MineParams(ref, ids, Optional.empty(),
                            Math.max(1, p.getInt("count", 1)), p.getIntClamped("radius", 16, 1, 64), 0));
                }
                case "escape" -> {
                    if (!(p.get("targetY") instanceof Number n)) throw new SceneFailure("task " + i + ": escape needs params.targetY (origin-relative)");
                    yield SceneBot.tasks().escape(new BuildContracts.EscapeParams(ref, Optional.of(origin.getY() + n.intValue()), 0));
                }
                case "elytra" -> {
                    // The file's params are the verb's own, with pos origin-relative.
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bot", ref);
                    if (p.get("pos") != null) {
                        BlockPos target = FixtureIO.at(origin, SceneFixture.intsOf(p.get("pos"), "params.pos", 3));
                        m.put("pos", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()));
                    }
                    for (String k : List.of("yaw", "pitch", "reactive", "fireworks", "fireworkEveryTicks", "ticks", "stopXZDist")) {
                        if (p.get(k) != null) m.put(k, p.get(k));
                    }
                    yield SceneBot.tasks().elytraFly(MoveContracts.ELYTRA_PARAMS.decode(SceneJson.params(m)));
                }
                default -> throw new SceneFailure("task " + i + ": unknown verb '" + verb + "' (goto|mine|escape|elytra)");
            };
            return (CompletionStage) order;
        }

        /** A leg's {@code params} as the fixture file wrote them: a missing or mistyped field reads as its default. */
        private record LegParams(Map<String, Object> m) {
            Object get(String key) { return m.get(key); }
            int getInt(String key, int dflt) { return m.get(key) instanceof Number n ? n.intValue() : dflt; }
            int getIntClamped(String key, int dflt, int lo, int hi) { return Math.max(lo, Math.min(hi, getInt(key, dflt))); }

            /** A list's strings, or a lone string as a list of one. */
            List<String> getStringList(String key) {
                List<String> out = new ArrayList<>();
                if (m.get(key) instanceof List<?> l) {
                    for (Object e : l) if (e instanceof String s) out.add(s);
                } else if (m.get(key) instanceof String s) {
                    out.add(s);
                }
                return out;
            }
        }

        private static GotoContracts.Target target(BlockPos abs, String kind) {
            String k = kind == null ? "block" : kind.trim();
            if (k.startsWith("near:")) return Targets.near(abs, Integer.parseInt(k.substring(5).trim()));
            if (k.equals("y:") || k.equals("y")) return Targets.level(abs.getY());
            return Targets.block(abs);
        }

        /** Arrival as the marker meant it: a near goal within its radius, a y goal on its level,
         *  a block goal with the feet on the cell (one cell of slack, the navigator's own tolerance). */
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
                observed.put("repaths", (int) searches);
                observed.put("hops", (int) hops);
                observed.put("digs", (int) digs);
            } else {
                line(true, "repaths/hops/digs not observed: a task's process has no navigator");
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
            ResourceLocation key = ResourceLocation.tryParse(id);
            Optional<Item> item = key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
            return item.orElseThrow(() -> new SceneFailure("unknown item '" + id + "'"));
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
