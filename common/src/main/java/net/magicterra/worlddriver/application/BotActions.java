package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiHandlers;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.methods.WaitResultContract;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.ClearAreaContract;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Box;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Clear;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Direction;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Fill;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Replace;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Summary;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Tunnel;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.WidthBias;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.BboxFillProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The typed body-task methods: each decodes its record, checks what it can without the world, and
 * hands {@link ActionService} a planner that reads the body on its own thread.
 */
final class BotActions {
    private final DriverApplication app;
    private final ActionService actions;

    BotActions(DriverApplication app) {
        this.app = app;
        this.actions = new ActionService(app, ActionRegistry.GLOBAL);
    }

    List<ApiMethod<?, ?>> methods() {
        return List.of(
                new ApiMethod<>(ClearAreaContract.SPEC, this::clearArea),
                new ApiMethod<>(WaitResultContract.SPEC, ApiHandlers.immediate(app.wait::result)));
    }

    CompletionStage<ActionResponse<Summary>> clearArea(ClearAreaContract.Params p, CallContext ctx) {
        ClearAreaGeometry.Cells box = null;
        if (p.target() instanceof Box b) {
            box = ClearAreaGeometry.box(b.from().x(), b.from().y(), b.from().z(), b.to().x(), b.to().y(), b.to().z(),
                    ClearAreaContract.MAX_CELLS);
            if (box == null) {
                throw ApiException.invalidParam("/target", ApiIssue.Code.OUT_OF_RANGE,
                        "the box covers more than " + ClearAreaContract.MAX_CELLS + " cells");
            }
        }
        String fill = null, only = null;
        switch (p.edit()) {
            case Clear c -> { }
            case Fill f -> fill = requireBlock("/edit/block", f.block());
            case Replace r -> {
                only = requireBlock("/edit/from", r.from());
                fill = requireBlock("/edit/to", r.to());
            }
        }
        ClearAreaGeometry.Cells fixedBox = box;
        String fillId = fill, filterFrom = only;
        return actions.start(p.body(), p.awaitMs(), ctx, body -> {
            ClearAreaGeometry.Cells cells = fixedBox;
            Optional<WidthBias> used = Optional.empty();
            if (p.target() instanceof Tunnel t) {
                boolean vertical = t.direction() == Direction.UP || t.direction() == Direction.DOWN;
                WidthBias bias = t.widthBias().orElse("right".equals(BotConfig.tunnelWidthBias) ? WidthBias.RIGHT : WidthBias.LEFT);
                if (!vertical) used = Optional.of(bias);
                cells = ClearAreaGeometry.tunnel(Mth.floor(body.getX()), Mth.floor(body.getY()), Mth.floor(body.getZ()),
                        body.getYRot(), t, bias);
            }
            Level level = body.level();
            if (cells == null || !cells.fitsInt()) {
                return ActionService.invalidTarget("the area lies outside the world's coordinates");
            }
            if (cells.y0() < level.getMinBuildHeight() || cells.y1() >= level.getMaxBuildHeight()) {
                return ActionService.invalidTarget("y " + cells.y0() + ".." + cells.y1() + " is outside this world's build height "
                        + level.getMinBuildHeight() + ".." + (level.getMaxBuildHeight() - 1));
            }
            BlockPos from = new BlockPos((int) cells.x0(), (int) cells.y0(), (int) cells.z0());
            BlockPos to = new BlockPos((int) cells.x1(), (int) cells.y1(), (int) cells.z1());
            return new ActionService.Start<>(new BboxFillProcess(from, to, fillId, filterFrom),
                    new Summary(position(from), position(to), used));
        });
    }

    /** {@code id} when the block registry holds it; otherwise invalid_params at {@code path}. */
    private static String requireBlock(String path, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, "no block " + id + " in this game");
        }
        return rl.toString();
    }

    private static BlockPosition position(BlockPos p) {
        return new BlockPosition(p.getX(), p.getY(), p.getZ());
    }
}
