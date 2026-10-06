package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
import net.magicterra.worlddriver.api.methods.ActionContract.RefusalReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.GotoContract;
import net.magicterra.worlddriver.api.methods.RouteContract;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.GotoOrders;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.process.BboxFillProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
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
                new ApiMethod<>(GotoContract.SPEC, this::goTo),
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

    CompletionStage<ActionResponse<GotoContract.Result>> goTo(GotoContract.Params p, CallContext ctx) {
        p.route().flatMap(RouteContract.Route::requireTool)
                .ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/route/requireTool", id));
        switch (p.target().orElse(null)) {
            case GotoContract.BlockTarget b when !b.block().startsWith("#") ->
                    requireRegistered(BuiltInRegistries.BLOCK, "block", "/target/block", b.block());
            case GotoContract.EntityType t -> requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
            case null, default -> { }
        }
        boolean self = BodyRegistry.isSelf(p.body());
        if (p.plan().orElse(null) == GotoContract.PlanMode.SCORE) return score(p, self, ctx);
        return actions.start(p.body(), p.awaitMs(), ctx, body -> {
            if (self) return plan(BotHooks.impl().planGoto(p));
            String selfOnly = p.target().orElse(null) instanceof GotoContract.Waypoint ? "a waypoint target"
                    : p.planId().isPresent() ? "planId" : p.plan().isPresent() ? "a preview" : null;
            if (selfOnly != null) {
                return ActionService.unsupported(selfOnly + " is only taken by self: waypoints, previews and plans are the client's");
            }
            GotoOrders.Resolution r = GotoOrders.resolve(p, body, Map.of(), type -> BodyRoutes.nearestOfType(body, type));
            if (r instanceof GotoOrders.Refuse refuse) return plan(refuse);
            return plan(GotoOrders.start((GotoOrders.Resolved) r, body, app::emitExternal));
        });
    }

    /** {@code plan: 'score'}: answered at once on the client thread; starts nothing. */
    private CompletionStage<ActionResponse<GotoContract.Result>> score(GotoContract.Params p, boolean self, CallContext ctx) {
        BotApi bot = BotHooks.impl();
        if (!self || bot == null) {
            return CompletableFuture.completedFuture(new Refused<>(self ? RefusalReason.BODY_UNAVAILABLE : RefusalReason.UNSUPPORTED_CAPABILITY,
                    self ? "self is this client's own player, and there is no client here" : "only self scores a line"));
        }
        return bot.clientHop().submit(() -> {
            if (bot.selfEntity() == null) {
                return new Refused<GotoContract.Result>(RefusalReason.BODY_UNAVAILABLE, "self has no player");
            }
            return bot.scoreGoto(p);
        }, ctx);
    }

    private static ActionService.Plan<GotoContract.Result> plan(GotoOrders.Order order) {
        return switch (order) {
            case GotoOrders.Start s -> new ActionService.Start<>(s.process(), s.trip());
            case GotoOrders.Launch l -> new ActionService.Launch<>(l.launcher());
            case GotoOrders.Refuse r -> new ActionService.Refuse<>(r.reason(), r.message());
        };
    }

    /** {@code id} when the block registry holds it; otherwise invalid_params at {@code path}. */
    private static String requireBlock(String path, String id) {
        return requireRegistered(BuiltInRegistries.BLOCK, "block", path, id);
    }

    private static String requireRegistered(Registry<?> registry, String what, String path, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !registry.containsKey(rl)) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, "no " + what + " " + id + " in this game");
        }
        return rl.toString();
    }

    private static BlockPosition position(BlockPos p) {
        return new BlockPosition(p.getX(), p.getY(), p.getZ());
    }
}
