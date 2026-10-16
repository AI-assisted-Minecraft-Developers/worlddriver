package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static net.magicterra.worlddriver.api.game.Positions.position;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Box;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Clear;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Fill;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Replace;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Summary;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Tunnel;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.WidthBias;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.bot.BotTaskApi;
import net.magicterra.worlddriver.api.methods.bot.BotTaskApiBinding;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts.BuildParams;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts.BunkerParams;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts.ConstructParams;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts.EscapeParams;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts.CraftParams;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts.FarmParams;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts.MineParams;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts.SmeltParams;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.ElytraParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.ExploreParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.FollowParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.RunAwayParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.SleepParams;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.GotoOrders;
import net.magicterra.worlddriver.bot.VerbOrders;
import net.magicterra.worlddriver.bot.BotRegistry;
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
 * hands {@link BodyTaskStarter} a planner that reads the body on its own thread.
 */
final class BotTaskService implements BotTaskApi {
    private final DriverApplication app;
    private final BodyTaskStarter actions;

    BotTaskService(DriverApplication app) {
        this.app = app;
        this.actions = new BodyTaskStarter(app, ActionRegistry.GLOBAL);
    }

    List<ApiMethod<?, ?>> methods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>(BotTaskApiBinding.methods(this));
        all.addAll(new InteractService(actions).methods());
        all.addAll(new ControlService(app).methods());
        all.addAll(new SettingsService(app).methods());
        return all;
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Mined>> mine(MineParams p, CallContext ctx) {
        for (int i = 0; i < p.blocks().size(); i++) requireBlockOrTag("/blocks/" + i, p.blocks().get(i));
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.mine(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Crafted>> craft(CraftParams p, CallContext ctx) {
        requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.craft(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Smelted>> smelt(SmeltParams p, CallContext ctx) {
        requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        p.fuel().ifPresent(f -> requireRegistered(BuiltInRegistries.ITEM, "item", "/fuel", f));
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.smelt(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<GatherContracts.Farmed>> farm(FarmParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.farm(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Constructed>> construct(ConstructParams p, CallContext ctx) {
        Optional<String> block = switch (p.structure()) {
            case BuildContracts.Tower t -> t.block();
            case BuildContracts.Bridge b -> b.block();
        };
        block.ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/structure/block", id));
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.construct(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Bunkered>> bunker(BunkerParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.bunker(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<BuildContracts.Escaped>> escape(EscapeParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.escape(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Followed>> follow(FollowParams p, CallContext ctx) {
        if (p.target() instanceof MoveContracts.QuarryType t) {
            requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
        }
        p.route().flatMap(RouteContracts.Route::requireTool)
                .ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/route/requireTool", id));
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.follow(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Explored>> explore(ExploreParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.explore(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.RanAway>> runAway(RunAwayParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.runAway(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Slept>> sleep(SleepParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.sleep(p, body));
    }

    @Override
    public CompletionStage<ActionResponse<MoveContracts.Flown>> elytraFly(ElytraParams p, CallContext ctx) {
        return task(p.bot(), p.awaitMs(), ctx, body -> VerbOrders.elytraFly(p, body));
    }

    /**
     * A body task whose process the same {@link VerbOrders} builder makes for every body. The caller has
     * already turned away, before anything starts, the ids this game does not have.
     */
    private <R> CompletionStage<ActionResponse<R>> task(String body, int awaitMs, CallContext ctx,
                                                        Function<LivingEntity, VerbOrders.Order<R>> order) {
        return actions.start(body, awaitMs, ctx, entity -> plan(order.apply(entity)));
    }

    private static <R> BodyTaskStarter.Plan<R> plan(VerbOrders.Order<R> order) {
        return switch (order) {
            case VerbOrders.Start<R> s -> new BodyTaskStarter.Start<>(s.process(), s.result());
            case VerbOrders.Refuse<R> r -> new BodyTaskStarter.Refuse<>(r.reason(), r.message());
        };
    }

    /** The schematic is read here, before any body: bytes that are no schematic are a bad parameter. */
    @Override
    public CompletionStage<ActionResponse<BuildContracts.Built>> build(BuildParams p, CallContext ctx) {
        if (p.schematic() instanceof BuildContracts.Blocks b) {
            for (int i = 0; i < b.palette().size(); i++) requireBlock("/schematic/palette/" + i, b.palette().get(i));
        }
        Function<LivingEntity, VerbOrders.Order<BuildContracts.Built>> order;
        try {
            order = VerbOrders.build(p);
        } catch (RuntimeException e) {
            // Malformed NBT surfaces as whatever the reader trips on, not only IllegalArgumentException.
            throw ApiException.invalidParam(p.schematic() instanceof BuildContracts.Sponge ? "/schematic/base64" : "/schematic",
                    ApiIssue.Code.INVALID_VALUE, "not a schematic this game reads: " + e.getMessage());
        }
        return actions.start(p.bot(), p.awaitMs(), ctx, body -> plan(order.apply(body)));
    }

    /**
     * {@code self}'s fight belongs to its combat chain, which ends the action itself; any other bot's is
     * its held process.
     */
    @Override
    public CompletionStage<ActionResponse<CombatContracts.Fought>> combat(CombatContracts.Params p, CallContext ctx) {
        if (p.target().orElse(null) instanceof CombatContracts.FoeType t) {
            requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
        }
        boolean self = BotRegistry.isSelf(p.bot());
        return actions.start(p.bot(), p.awaitMs(), ctx, body -> self
                ? new BodyTaskStarter.Launch<>(h -> BotHooks.impl().engage(p, h))
                : plan(VerbOrders.combat(p, body)));
    }

    @Override
    public CompletionStage<ActionResponse<Summary>> clearArea(ClearAreaContracts.Params p, CallContext ctx) {
        ClearAreaGeometry.Cells box = null;
        if (p.target() instanceof Box b) {
            box = ClearAreaGeometry.box(b.from().x(), b.from().y(), b.from().z(), b.to().x(), b.to().y(), b.to().z(),
                    ClearAreaContracts.MAX_CELLS);
            if (box == null) {
                throw ApiException.invalidParam("/target", ApiIssue.Code.OUT_OF_RANGE,
                        "the box covers more than " + ClearAreaContracts.MAX_CELLS + " cells");
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
        return actions.start(p.bot(), p.awaitMs(), ctx, body -> {
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
                return BodyTaskStarter.invalidTarget("the area lies outside the world's coordinates");
            }
            if (cells.y0() < level.getMinBuildHeight() || cells.y1() >= level.getMaxBuildHeight()) {
                return BodyTaskStarter.invalidTarget("y " + cells.y0() + ".." + cells.y1() + " is outside this world's build height "
                        + level.getMinBuildHeight() + ".." + (level.getMaxBuildHeight() - 1));
            }
            BlockPos from = new BlockPos((int) cells.x0(), (int) cells.y0(), (int) cells.z0());
            BlockPos to = new BlockPos((int) cells.x1(), (int) cells.y1(), (int) cells.z1());
            return BodyTaskStarter.Start.reporting(VerbOrders.fill(from, to, fillId, filterFrom),
                    new Summary(position(from), position(to), used));
        });
    }

    @Override
    public CompletionStage<ActionResponse<GotoContracts.Result>> goTo(GotoContracts.Params p, CallContext ctx) {
        p.route().flatMap(RouteContracts.Route::requireTool)
                .ifPresent(id -> requireRegistered(BuiltInRegistries.ITEM, "item", "/route/requireTool", id));
        switch (p.target().orElse(null)) {
            case GotoContracts.BlockTarget b when !b.block().startsWith("#") ->
                    requireRegistered(BuiltInRegistries.BLOCK, "block", "/target/block", b.block());
            case GotoContracts.EntityType t -> requireRegistered(BuiltInRegistries.ENTITY_TYPE, "entity type", "/target/type", t.type());
            case null, default -> { }
        }
        boolean self = BotRegistry.isSelf(p.bot());
        if (p.plan().orElse(null) == GotoContracts.PlanMode.SCORE) return score(p, self, ctx);
        return actions.start(p.bot(), p.awaitMs(), ctx, body -> {
            if (self) return plan(BotHooks.impl().planGoto(p));
            String selfOnly = p.target().orElse(null) instanceof GotoContracts.Waypoint ? "a waypoint target"
                    : p.planId().isPresent() ? "planId" : p.plan().isPresent() ? "a preview" : null;
            if (selfOnly != null) {
                return BodyTaskStarter.unsupported(selfOnly + " is only taken by self: waypoints, previews and plans are the client's");
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
    private CompletionStage<ActionResponse<GotoContracts.Result>> score(GotoContracts.Params p, boolean self, CallContext ctx) {
        BotApi bot = BotHooks.impl();
        if (!self || bot == null) {
            return CompletableFuture.completedFuture(new Refused<>(self ? RefusalReason.BOT_UNAVAILABLE : RefusalReason.UNSUPPORTED_CAPABILITY,
                    self ? "self is this client's own player, and there is no client here" : "only self scores a line"));
        }
        return bot.clientHop().submit(() -> {
            if (bot.selfEntity() == null) {
                return new Refused<GotoContracts.Result>(RefusalReason.BOT_UNAVAILABLE, "self has no player");
            }
            return bot.scoreGoto(p);
        }, ctx);
    }

    private static BodyTaskStarter.Plan<GotoContracts.Result> plan(GotoOrders.Order order) {
        return switch (order) {
            case GotoOrders.Start s -> BodyTaskStarter.Start.reporting(s.process(), (GotoContracts.Result) s.trip());
            case GotoOrders.Launch l -> new BodyTaskStarter.Launch<>(l.launcher());
            case GotoOrders.Refuse r -> new BodyTaskStarter.Refuse<>(r.reason(), r.message());
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
