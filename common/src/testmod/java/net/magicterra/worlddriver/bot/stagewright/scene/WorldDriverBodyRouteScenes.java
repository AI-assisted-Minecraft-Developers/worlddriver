package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerBodyHost;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.LivingBody;
import net.magicterra.worlddriver.bot.stagewright.NpcBodyHost;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@code mc.bot.*} verbs on bodies named by {@code body}, called through {@code DriverApi.route}
 * the way every transport calls it. A server player body and an NPC are registered on a flat stone
 * course and addressed by name:
 *
 * <ul>
 *   <li>{@code goto}, {@code cancel}, {@code status}: listed, refused the goal forms only {@code self}
 *       takes, walked down the course, and the NPC's second walk cancelled;</li>
 *   <li>the verbs that start a process: a bad order refused before anything starts, the NPC's mine
 *       ended by the process for want of hands, the player body run away from where it stands.</li>
 * </ul>
 *
 * <p>Runs where a headless player body may be minted. {@code SceneBody.managed} skips it on a server
 * a client hosts, and an NPC alone would prove the route without the player host.
 */
public final class WorldDriverBodyRouteScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String NAME = "scene-walker";
    private static final int COURSE = 10;
    private static final int RUN_AWAY = 6;

    /** Steps with no input before the first order: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.bodyRoutesWalkAPlayerAndAnNpcByName", 600,
                        WorldDriverBodyRouteScenes::walkAPlayerAndAnNpcByName),
                Scene.of("wd.bodyRoutesStartProcessesByName", 600,
                        WorldDriverBodyRouteScenes::startProcessesByName));
    }

    /** The two bodies on the staged course, registered under {@link #NAME}; null when the scene failed. */
    private record Bodies(ServerWorldDriver driver, ServerBodyHost player, LivingBody npcBody, NpcBodyHost npc) {}

    private static Bodies stage(SceneContext ctx, BlockPos o, BlockPos playerStart, BlockPos npcStart) {
        ServerLevel level = ctx.level();
        BlockPos lo = o.offset(-2, -1, -4), hi = o.offset(COURSE + 2, 3, 4);
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 4), STONE);

        ServerWorldDriver driver = SceneBody.managed(ctx, playerStart);
        LivingBody npcBody = SceneBody.npc(ctx, npcStart);
        ServerBodyHost player = new ServerBodyHost(NAME, driver);
        NpcBodyHost npc = new NpcBodyHost(NAME, npcBody);
        ctx.cleanup(() -> ServerAvatarManager.unregister(npc));
        for (BodyHost h : List.of(player, npc)) {
            if (!BodyRegistry.register(h)) { ctx.fail(h.id() + " 已被占用：前一条场景没把身体注销掉"); return null; }
            ctx.cleanup(() -> BodyRegistry.unregister(h.id()));
        }
        for (int i = 0; i < SETTLE_STEPS; i++) { npcBody.step(); driver.avatar().step(); }
        return new Bodies(driver, player, npcBody, npc);
    }

    private static void walkAPlayerAndAnNpcByName(SceneContext ctx) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) { ctx.fail("DriverApi not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(0, 0, -2), playerGoal = o.offset(COURSE, 0, -2);
        BlockPos npcStart = o.offset(0, 0, 2), npcGoal = o.offset(COURSE, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        if (b == null) return;
        ServerBodyHost player = b.player();
        NpcBodyHost npc = b.npc();

        Map<String, Object> listed = call(api, "mc.bot.status", Map.of());
        ctx.record("status.bodies", String.valueOf(listed.get("bodies")));
        ctx.check(ids(listed).containsAll(List.of(player.id(), npc.id())))
                .as("status 的 bodies 该列出两具注册过的身体：" + listed.get("bodies")).isTrue();

        Map<String, Object> waypoint = call(api, "mc.bot.goto", Map.of("body", npc.id(), "waypoint", "home"));
        Map<String, Object> tool = call(api, "mc.bot.goto", Map.of("body", npc.id(), "pos", pos(npcGoal),
                "route", Map.of("requireTool", "minecraft:iron_pickaxe")));
        ctx.record("npc.refused", waypoint + " / " + tool);
        ctx.check(Boolean.FALSE.equals(waypoint.get("ok")) && Boolean.FALSE.equals(tool.get("ok")) && !npc.busy())
                .as("NPC 该拒掉航点和 requireTool，且没有开工：" + waypoint + " / " + tool).isTrue();

        Map<String, Object> playerGo = call(api, "mc.bot.goto", Map.of("body", player.id(), "pos", pos(playerGoal)));
        Map<String, Object> npcGo = call(api, "mc.bot.goto", Map.of("body", npc.id(), "pos", pos(npcGoal)));
        ctx.record("goto", playerGo + " / " + npcGo);
        if (!Boolean.TRUE.equals(playerGo.get("started")) || !Boolean.TRUE.equals(npcGo.get("started"))) {
            ctx.fail("按名字下的 goto 没有开工：player=" + playerGo + " npc=" + npcGo);
            return;
        }

        ctx.await(() -> !player.busy() && !npc.busy()).within(400).then(() -> {
            BlockPos playerAt = b.driver().fakePlayer().blockPosition(), npcAt = b.npcBody().entity().blockPosition();
            ctx.record("player.goto", String.valueOf(call(api, "mc.bot.status", Map.of("body", player.id())).get("goto")));
            ctx.record("npc.goto", String.valueOf(call(api, "mc.bot.status", Map.of("body", npc.id())).get("goto")));
            ctx.check(playerAt.closerThan(playerGoal, 1.5))
                    .as("服务端玩家身体按名字走到终点：停在 " + playerAt.toShortString()).isTrue();
            ctx.check(npcAt.closerThan(npcGoal, 1.5))
                    .as("NPC 按名字走到终点：停在 " + npcAt.toShortString()).isTrue();

            Map<String, Object> back = call(api, "mc.bot.goto", Map.of("body", npc.id(), "pos", pos(npcStart)));
            Map<String, Object> cancelled = call(api, "mc.bot.cancel", Map.of("body", npc.id(), "process", "goto"));
            Map<String, Object> after = call(api, "mc.bot.status", Map.of("body", npc.id()));
            ctx.record("npc.cancel", back + " / " + cancelled + " / busy=" + after.get("busy"));
            Object slot = after.get("goto");
            ctx.check("goto".equals(cancelled.get("cancelled")) && Boolean.FALSE.equals(after.get("busy"))
                            && slot instanceof Map<?, ?> m && "user-cancel".equals(m.get("lastError")))
                    .as("按名字取消 NPC 的第二段 goto：" + cancelled + "，之后 busy=" + after.get("busy") + " goto=" + slot)
                    .isTrue();
        });
    }

    private static void startProcessesByName(SceneContext ctx) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) { ctx.fail("DriverApi not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(1, 0, -2), npcStart = o.offset(1, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        if (b == null) return;
        ServerBodyHost player = b.player();
        NpcBodyHost npc = b.npc();

        Map<String, Object> empty = call(api, "mc.bot.mine", Map.of("body", player.id(), "blocks", List.of()));
        ctx.record("player.badOrder", String.valueOf(empty));
        ctx.check(Boolean.FALSE.equals(empty.get("ok")) && "blocks list required".equals(empty.get("error")) && !player.busy())
                .as("空 blocks 的 mine 该在开工前被拒，玩家身体保持空闲：" + empty).isTrue();

        Map<String, Object> mine = call(api, "mc.bot.mine", Map.of("body", npc.id(), "blocks", List.of("minecraft:stone"),
                "radius", 4));
        Map<String, Object> run = call(api, "mc.bot.runAway", Map.of("body", player.id(), "from", pos(playerStart),
                "minDist", RUN_AWAY));
        ctx.record("orders", mine + " / " + run);
        if (!Boolean.TRUE.equals(mine.get("started")) || !npc.id().equals(mine.get("body"))
                || !Boolean.TRUE.equals(run.get("started")) || !player.id().equals(run.get("body"))) {
            ctx.fail("按名字下的 mine/runAway 没有开工，或回复没带 body：mine=" + mine + " runAway=" + run);
            return;
        }

        ctx.await(() -> !player.busy() && !npc.busy()).within(400).then(() -> {
            Object mineSlot = call(api, "mc.bot.status", Map.of("body", npc.id())).get("mine");
            ctx.record("npc.mine", String.valueOf(mineSlot));
            ctx.check(mineSlot instanceof Map<?, ?> m && "no_hands".equals(m.get("lastError")))
                    .as("NPC 没有手，mine 该由进程以 no_hands 收工：" + mineSlot).isTrue();

            BlockPos at = b.driver().fakePlayer().blockPosition();
            double away = Math.sqrt(Math.pow(at.getX() - playerStart.getX(), 2) + Math.pow(at.getZ() - playerStart.getZ(), 2));
            ctx.record("player.runAway", at.toShortString() + " away=" + away + " slot="
                    + call(api, "mc.bot.status", Map.of("body", player.id())).get("runAway"));
            ctx.check(away >= RUN_AWAY - 1)
                    .as("服务端玩家身体按名字跑开至少 " + (RUN_AWAY - 1) + " 格：停在 " + at.toShortString()
                            + "，离起点 " + away).isTrue();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(DriverApi api, String method, Map<String, Object> params) {
        return (Map<String, Object>) api.route(method, params);
    }

    private static List<Object> ids(Map<String, Object> status) {
        List<Object> out = new ArrayList<>();
        if (status.get("bodies") instanceof List<?> bodies) {
            for (Object o : bodies) if (o instanceof Map<?, ?> m) out.add(m.get("id"));
        }
        return out;
    }

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }
}
