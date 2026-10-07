package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static net.magicterra.worlddriver.api.Positions.position;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.ClearAreaContract;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Box;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Clear;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Fill;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Replace;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Summary;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Tunnel;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.WidthBias;
import net.magicterra.worlddriver.api.methods.ActionContract.RefusalReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.BotTaskCalls;
import net.magicterra.worlddriver.api.methods.BotTaskCallsBinding;
import net.magicterra.worlddriver.api.methods.BuildContracts;
import net.magicterra.worlddriver.api.methods.BuildContracts.BuildParams;
import net.magicterra.worlddriver.api.methods.BuildContracts.BunkerParams;
import net.magicterra.worlddriver.api.methods.BuildContracts.ConstructParams;
import net.magicterra.worlddriver.api.methods.BuildContracts.EscapeParams;
import net.magicterra.worlddriver.api.methods.CombatContract;
import net.magicterra.worlddriver.api.methods.GatherContracts;
import net.magicterra.worlddriver.api.methods.GatherContracts.CraftParams;
import net.magicterra.worlddriver.api.methods.GatherContracts.FarmParams;
import net.magicterra.worlddriver.api.methods.GatherContracts.MineParams;
import net.magicterra.worlddriver.api.methods.GatherContracts.SmeltParams;
import net.magicterra.worlddriver.api.methods.GotoContract;
import net.magicterra.worlddriver.api.methods.MoveContracts;
import net.magicterra.worlddriver.api.methods.MoveContracts.ElytraParams;
import net.magicterra.worlddriver.api.methods.MoveContracts.ExploreParams;
import net.magicterra.worlddriver.api.methods.MoveContracts.FollowParams;
import net.magicterra.worlddriver.api.methods.MoveContracts.RunAwayParams;
import net.magicterra.worlddriver.api.methods.MoveContracts.SleepParams;
import net.magicterra.worlddriver.api.methods.RouteContract;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.GotoOrders;
import net.magicterra.worlddriver.bot.VerbOrders;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.process.BboxFillProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * The typed body-task methods: each decodes its record, checks what it can without the world, and
 * hands {@link ActionService} a planner that reads the body on its own thread.
 */
final class BotActions implements BotTaskCalls {
    private final DriverApplication app;
    private final ActionService actions;

    BotActions(DriverApplication app) {
        this.app = app;
        this.actions = new ActionService(app, ActionRegistry.GLOBAL);
    }

    List<ApiMethod<?, ?>> methods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>(BotTaskCallsBinding.methods(this));
        all.addAll(new HandMethods(actions).methods());
        all.addAll(new ControlMethods(app).methods());
        all.addAll(new SettingsMethods(app).methods());
        return all;
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Mined>> mine(MineParams p, CallContext ctx) {
        for (int i = 0; i < p.blocks().size(); i++) requireBlockOrTag("/blocks/" + i, p.blocks().get(i));
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.mine(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Crafted>> craft(CraftParams p, CallContext ctx) {
        requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.craft(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Smelted>> smelt(SmeltParams p, CallContext ctx) {
        requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        p.fuel().ifPresent(f -> requireRegistered(BuiltInRegistries.ITEM, "item", "/fuel", f));
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.smelt(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Farmed>> farm(FarmParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.farm(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Constructed>> construct(ConstructParams p, CallContext ctx) {
        Optional<String> block = switch (p.structure()) {
            case BuildContracts.Tower t -> t.block();
            case BuildContracts.Bridge b -> b.block();
        };
        block.ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/structure/block", id));
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.construct(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Bunkered>> bunker(BunkerParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.bunker(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Escaped>> escape(EscapeParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.escape(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Followed>> follow(FollowParams p, CallContext ctx) {
        if (p.target() instanceof MoveContracts.QuarryType t) {
            requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
        }
        p.route().flatMap(RouteContract.Route::requireTool)
                .ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/route/requireTool", id));
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.follow(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Explored>> explore(ExploreParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.explore(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.RanAway>> runAway(RunAwayParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.runAway(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Slept>> sleep(SleepParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.sleep(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Flown>> elytraFly(ElytraParams p, CallContext ctx) {
        return task(p.body(), p.awaitMs(), ctx, body -> VerbOrders.elytraFly(p, body));
    }

    /**
     * A body task whose process the same {@link VerbOrders} builder makes for every body. The caller has
     * already turned away, before anything starts, the ids this game does not have.
     */
    private <R> CompletionStage<ActionResponse<R>> task(String body, int awaitMs, CallContext ctx,
                                                        Function<LivingEntity, VerbOrders.Order<R>> order) {
        return actions.start(body, awaitMs, ctx, entity -> plan(order.apply(entity)));
    }

    private static <R> ActionService.Plan<R> plan(VerbOrders.Order<R> order) {
        return switch (order) {
            case VerbOrders.Start<R> s -> new ActionService.Start<>(s.process(), s.result());
            case VerbOrders.Refuse<R> r -> new ActionService.Refuse<>(r.reason(), r.message());
        };
    }

    /** The schematic is read here, before any body: bytes that are no schematic are a bad parameter. */
    @Override
    public CompletionStage<ActionResponse<BuildContracts.Built>> build(BuildParams p, CallContext ctx) {
        if (p.schematic() instanceof BuildContracts.Blocks b) {
            for (int i = 0; i < b.palette().size(); i++) requireBlock("/schematic/palette/" + i, b.palette().get(i));
        }
        net.magicterra.worlddriver.bot.process.Schematic schematic;
        try {
            schematic = VerbOrders.schematic(p.schematic());
        } catch (RuntimeException e) {
            // Malformed NBT surfaces as whatever the reader trips on, not only IllegalArgumentException.
            throw ApiException.invalidParam(p.schematic() instanceof BuildContracts.Sponge ? "/schematic/base64" : "/schematic",
                    ApiIssue.Code.INVALID_VALUE, "not a schematic this game reads: " + e.getMessage());
        }
        return actions.start(p.body(), p.awaitMs(), ctx, body -> plan(VerbOrders.build(p, schematic, body)));
    }

    /**
     * {@code self}'s fight belongs to its combat chain, which ends the action itself; any other bot's is
     * its held process.
     */
    @Override
    public CompletionStage<ActionResponse<CombatContract.Fought>> combat(CombatContract.Params p, CallContext ctx) {
        if (p.target().orElse(null) instanceof CombatContract.FoeType t) {
            requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
        }
        boolean self = BodyRegistry.isSelf(p.body());
        return actions.start(p.body(), p.awaitMs(), ctx, body -> self
                ? new ActionService.Launch<>(h -> BotHooks.impl().engage(p, h))
                : plan(VerbOrders.combat(p, body)));
    }

    @Override
    public CompletionStage<ActionResponse<Summary>> clearArea(ClearAreaContract.Params p, CallContext ctx) {
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
                WidthBias bias = t.widthBias().orElse("right".equals(BotConfig.tunnelWidthBias) ? WidthBias.RIGHT : WidthBias.LEFT);
                if (!t.direction().vertical()) used = Optional.of(bias);
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
            return ActionService.Start.reporting(new BboxFillProcess(from, to, fillId, filterFrom),
                    new Summary(position(from), position(to), used));
        });
    }

    @Override
    public CompletionStage<ActionResponse<GotoContract.Result>> goTo(GotoContract.Params p, CallContext ctx) {
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
            GotoOrders.Resolution r = GotoOrders.resolve(p, body, Map.of(), type -> nearestOfType(body, type));
            if (r instanceof GotoOrders.Refuse refuse) return plan(refuse);
            return plan(GotoOrders.start((GotoOrders.Resolved) r, body,
                    (type, pos, data) -> app.emitExternal(type, pos, JsonValue.of(data))));
        });
    }

    /** The nearest loaded entity of {@code typeId} in the bot's level: for another bot, what the
     *  client's scan of rendered entities is for {@code self}. Server thread. */
    private static Entity nearestOfType(Entity self, String typeId) {
        if (!(self.level() instanceof ServerLevel level)) return null;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getAllEntities()) {
            if (e == self || !typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) continue;
            double d = e.distanceToSqr(self);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
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
            case GotoOrders.Start s -> ActionService.Start.reporting(s.process(), (GotoContract.Result) s.trip());
            case GotoOrders.Launch l -> new ActionService.Launch<>(l.launcher());
            case GotoOrders.Refuse r -> new ActionService.Refuse<>(r.reason(), r.message());
        };
    }

    /** {@code id} when the block registry holds it; otherwise invalid_params at {@code path}. */
    private static String requireBlock(String path, String id) {
        return requireRegistered(BuiltInRegistries.BLOCK, "block", path, id);
    }

    /** A block id the registry holds, or a {@code #tag} it has; otherwise invalid_params at {@code path}. */
    private static void requireBlockOrTag(String path, String idOrTag) {
        if (!idOrTag.startsWith("#")) {
            requireBlock(path, idOrTag);
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(idOrTag.substring(1));
        if (rl == null || BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, rl)).isEmpty()) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, "no block tag " + idOrTag + " in this game");
        }
    }

    static String requireRegistered(Registry<?> registry, String what, String path, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !registry.containsKey(rl)) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, "no " + what + " " + id + " in this game");
        }
        return rl.toString();
    }

}
