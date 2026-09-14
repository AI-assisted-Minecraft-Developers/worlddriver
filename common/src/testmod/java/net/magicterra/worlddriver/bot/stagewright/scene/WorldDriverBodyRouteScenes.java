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
 * {@code mc.bot.goto}, {@code mc.bot.cancel} and {@code mc.bot.status} on bodies named by
 * {@code body}, called through {@code DriverApi.route} the way every transport calls it: a server
 * player body and an NPC are registered, listed, refused the goal forms only {@code self} takes, walked
 * down a flat course by name, and the NPC's second walk is cancelled by name.
 *
 * <p>Runs where a headless player body may be minted. {@code SceneBody.managed} skips it on a server
 * a client hosts, and an NPC alone would prove the route without the player host.
 */
public final class WorldDriverBodyRouteScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String NAME = "scene-walker";
    private static final int COURSE = 10;

    /** Steps with no input before the first order: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.bodyRoutesWalkAPlayerAndAnNpcByName", 600,
                WorldDriverBodyRouteScenes::walkAPlayerAndAnNpcByName));
    }

    private static void walkAPlayerAndAnNpcByName(SceneContext ctx) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) { ctx.fail("DriverApi not initialized — was the mod loaded?"); return; }
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        BlockPos lo = o.offset(-2, -1, -4), hi = o.offset(COURSE + 2, 3, 4);
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 4), STONE);

        BlockPos playerStart = o.offset(0, 0, -2), playerGoal = o.offset(COURSE, 0, -2);
        BlockPos npcStart = o.offset(0, 0, 2), npcGoal = o.offset(COURSE, 0, 2);
        ServerWorldDriver driver = SceneBody.managed(ctx, playerStart);
        LivingBody npcBody = SceneBody.npc(ctx, npcStart);
        ServerBodyHost player = new ServerBodyHost(NAME, driver);
        NpcBodyHost npc = new NpcBodyHost(NAME, npcBody);
        ctx.cleanup(() -> ServerAvatarManager.unregister(npc));
        for (BodyHost h : List.of(player, npc)) {
            if (!BodyRegistry.register(h)) { ctx.fail(h.id() + " 已被占用：前一条场景没把身体注销掉"); return; }
            ctx.cleanup(() -> BodyRegistry.unregister(h.id()));
        }
        for (int i = 0; i < SETTLE_STEPS; i++) { npcBody.step(); driver.avatar().step(); }

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
            BlockPos playerAt = driver.fakePlayer().blockPosition(), npcAt = npcBody.entity().blockPosition();
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(DriverApi api, String method, Map<String, Object> params) {
        return (Map<String, Object>) api.route(method, params);
    }

    private static List<Object> ids(Map<String, Object> status) {
        List<Object> out = new ArrayList<>();
        if (status.get("bodies") instanceof List<?> bodies) {
            for (Object b : bodies) if (b instanceof Map<?, ?> m) out.add(m.get("id"));
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
