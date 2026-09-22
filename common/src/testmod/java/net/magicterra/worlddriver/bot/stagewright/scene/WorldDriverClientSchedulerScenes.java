package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.process.SleepProcess;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * The scheduler's contract on the client's real player, where every chain is registered and the
 * status a caller reads is the one {@code mc.bot.status} ships: a process's slot is switched off when
 * the process ends, whatever its kind.
 *
 * <p>Adopts the player through {@link ClientHelm}, so every scene here skips on a dedicated server.
 */
public final class WorldDriverClientSchedulerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientSleepCancelEndsGoto", 600, WorldDriverClientSchedulerScenes::sleepCancelEndsGoto));
    }

    private static final int GROUND = 20;

    private static Map<?, ?> call(SceneContext ctx, String tag, String method, Map<String, Object> params) {
        Object r = WorldDriverCommon.api().route(method, params);
        ctx.record(tag, String.valueOf(r));
        return r instanceof Map<?, ?> m ? m : Map.of();
    }

    /**
     * {@code mc.bot.sleep} reports into the goto slot under the kind {@code sleep}. Cancelled while it
     * walks to the bed, the goto slot must go inactive: a slot left on says the bot is walking, keeps
     * the mouse and focus takeover engaged, and lets the screen watchdog close the player's chests.
     */
    private static void sleepCancelEndsGoto(SceneContext ctx) {
        for (int dx = -4; dx <= 16; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                ctx.setBlock(dx, GROUND, dz, Blocks.STONE);
                for (int dy = 1; dy <= 4; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        // Head first: placing the foot then updates the head against a real foot, and neither half
        // is dropped as an orphan.
        BlockPos foot = ctx.rel(14, GROUND + 1, 0), head = ctx.rel(15, GROUND + 1, 0);
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        ctx.level().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
        ctx.level().setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
        ctx.cleanup(() -> {
            ctx.level().setBlock(foot, Blocks.AIR.defaultBlockState(), 3);
            ctx.level().setBlock(head, Blocks.AIR.defaultBlockState(), 3);
        });
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        ctx.record("布景", "石板 y=" + GROUND + "，床在 " + foot.toShortString() + "（朝东），起点 " + start.toShortString());

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(20, () -> {
            helm.bot().runProcess(new SleepProcess(null, 16));
            final int[] waited = { 0 };
            ctx.await(() -> {
                waited[0]++;
                return Boolean.TRUE.equals(helm.slot("goto").get("active")) && waited[0] >= 15;
            }).within(200).then(() -> {
                double gap = helm.flatDistance(foot);
                ctx.record("取消前", helm.where() + String.format(Locale.ROOT, "，离床 %.2f 格；goto=%s",
                        gap, helm.slot("goto")));
                ctx.check(gap > 2.0).as("布景：取消时身体还在去床的路上（离床 " + gap + " 格）").isTrue();
                call(ctx, "cancel", "mc.bot.cancel", Map.of("process", "sleep"));
                helm.sync(5, () -> {
                    Map<?, ?> status = call(ctx, "取消后.status", "mc.bot.status", Map.of());
                    Map<?, ?> go = helm.slot("goto");
                    ctx.check(Boolean.FALSE.equals(go.get("active")))
                            .as("A 取消睡觉后 goto 槽不再 active：" + go).isTrue();
                    ctx.check(status.get("activeProcess") == null)
                            .as("B 用户任务槽已空：activeProcess=" + status.get("activeProcess")).isTrue();
                    Object end = status.get("lastProcessEnd");
                    ctx.check(end instanceof Map<?, ?> m && "sleep".equals(m.get("kind")))
                            .as("C 最后一次结束记的是 sleep：" + end).isTrue();
                });
            });
        });
    }
}
