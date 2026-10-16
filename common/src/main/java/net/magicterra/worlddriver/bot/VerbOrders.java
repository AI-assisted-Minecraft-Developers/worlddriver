package net.magicterra.worlddriver.bot;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.magicterra.worlddriver.api.game.Positions.blockPos;
import static net.magicterra.worlddriver.api.game.Positions.position;

import net.magicterra.worlddriver.api.game.Positions;
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
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.ElytraParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.ExploreParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.FollowParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.RunAwayParams;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts.SleepParams;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.bot.behavior.BboxFillProcess;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BridgeProcess;
import net.magicterra.worlddriver.bot.behavior.BuildProcess;
import net.magicterra.worlddriver.bot.behavior.BunkerProcess;
import net.magicterra.worlddriver.bot.behavior.CombatProcess;
import net.magicterra.worlddriver.bot.behavior.CraftProcess;
import net.magicterra.worlddriver.bot.behavior.DescendProcess;
import net.magicterra.worlddriver.bot.behavior.ElytraProcess;
import net.magicterra.worlddriver.bot.behavior.EscapeProcess;
import net.magicterra.worlddriver.bot.behavior.ExploreProcess;
import net.magicterra.worlddriver.bot.behavior.FarmProcess;
import net.magicterra.worlddriver.bot.behavior.FollowProcess;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.LookProcess;
import net.magicterra.worlddriver.bot.behavior.MineProcess;
import net.magicterra.worlddriver.bot.behavior.RunAwayProcess;
import net.magicterra.worlddriver.bot.behavior.Schematic;
import net.magicterra.worlddriver.bot.behavior.SleepProcess;
import net.magicterra.worlddriver.bot.behavior.SmeltProcess;
import net.magicterra.worlddriver.bot.behavior.TowerProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.bot.rules.Goal;

/**
 * What an order to one of the {@code mc.bot.*} verbs that start a process means, apart from which body
 * carries it out: the process built from the decoded params and the body as it is now, and what its
 * success will report. The client's own player and a server bot read an order through the same code.
 *
 * <p>Runs on the body's game thread. Nothing here may name a client class: on a dedicated server this
 * runs with none loaded.
 */
public final class VerbOrders {

    private VerbOrders() {}

    /** Start {@code process}, or refuse with nothing started. */
    public sealed interface Order<R> permits Start, Refuse {}

    /** {@code result} is read on the executor's thread when the process succeeds, so it can count what it did. */
    public record Start<R>(BotProcess process, Supplier<R> result) implements Order<R> {}

    public record Refuse<R>(RefusalReason reason, String message) implements Order<R> {}

    /** The verbs that hold, place, break or use something: a body with no hands (an NPC) cannot take them. */
    private static <R> Refuse<R> handless(String verb, LivingEntity self) {
        return new Refuse<>(RefusalReason.UNSUPPORTED_CAPABILITY, handlessMessage(verb));
    }

    /** Whether {@code self} can hold, place and break: a player can, a mob cannot. */
    public static boolean hasHands(LivingEntity self) { return self instanceof Player; }

    /** The refusal a verb that needs hands answers a body without them. */
    public static String handlessMessage(String verb) { return verb + " needs hands, and this body has none"; }

    public static Order<GatherContracts.Mined> mine(MineParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("mine", self);
        MineProcess m = new MineProcess(p.blocks(), p.quantity(), p.radius());
        return new Start<>(m, () -> new GatherContracts.Mined(p.blocks(), p.quantity(), p.radius(), m.broken()));
    }

    public static Order<BuildContracts.Bunkered> bunker(BunkerParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("bunker", self);
        int depth = p.depth().orElse(Math.max(1, Math.min(5, ArbitrationConfig.bunkerDepth)));
        BunkerProcess b = new BunkerProcess(depth);
        return new Start<>(b, () -> new BuildContracts.Bunkered(depth, b.sealed()));
    }

    /** A target below the feet digs a stair down instead of carving one up. */
    public static Order<BuildContracts.Escaped> escape(EscapeParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("escape", self);
        int feetY = self.blockPosition().getY();
        int targetY = p.targetY().orElse(feetY + 32);
        int min = self.level().getMinBuildHeight(), max = self.level().getMaxBuildHeight() - 1;
        if (targetY < min || targetY > max) {
            return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, "targetY " + targetY + " is outside this world's build height "
                    + min + ".." + max);
        }
        boolean down = targetY < feetY;
        BuildContracts.Vertical way = down ? BuildContracts.Vertical.DOWN : BuildContracts.Vertical.UP;
        return new Start<>(down ? new DescendProcess(targetY) : new EscapeProcess(targetY),
                () -> new BuildContracts.Escaped(way, targetY, self.blockPosition().getY()));
    }

    public static Order<GatherContracts.Crafted> craft(CraftParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("craft", self);
        CraftProcess c = new CraftProcess(p.item(), p.count());
        return new Start<>(c, () -> new GatherContracts.Crafted(p.item(), p.count(), c.crafted()));
    }

    public static Order<GatherContracts.Smelted> smelt(SmeltParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("smelt", self);
        SmeltProcess s = new SmeltProcess(p.item(), p.count(), p.fuel().orElse(null));
        return new Start<>(s, () -> new GatherContracts.Smelted(p.item(), p.count(), Optional.ofNullable(s.fuelChosen())));
    }

    /**
     * {@code mc.bot.combat} as a held process: any body but the client's own player, whose fight belongs
     * to its combat chain. {@code force} has nothing to lift here: the frail-health gate is the chain's.
     */
    public static Order<CombatContracts.Fought> combat(CombatContracts.Params p, LivingEntity self) {
        if (!hasHands(self)) return handless("combat", self);
        return new Start<>(new CombatProcess(mode(p.mode()), entityId(p), entityType(p)),
                () -> new CombatContracts.Fought(p.mode(), p.target()));
    }

    public static CombatProcess.Mode mode(CombatContracts.Mode m) {
        return switch (m) {
            case ENGAGE -> CombatProcess.Mode.ENGAGE;
            case DEFEND -> CombatProcess.Mode.DEFEND;
            case KILL -> CombatProcess.Mode.KILL;
        };
    }

    public static Integer entityId(CombatContracts.Params p) {
        return p.target().orElse(null) instanceof CombatContracts.FoeEntity e ? e.entityId() : null;
    }

    public static String entityType(CombatContracts.Params p) {
        return p.target().orElse(null) instanceof CombatContracts.FoeType t ? t.type() : null;
    }

    public static Order<GatherContracts.Farmed> farm(FarmParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("farm", self);
        int min = self.level().getMinBuildHeight(), max = self.level().getMaxBuildHeight() - 1;
        for (BlockPosition end : new BlockPosition[] {p.from(), p.to()}) {
            if (end.y() < min || end.y() > max) {
                return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, "y " + end.y() + " is outside this world's build height "
                        + min + ".." + max);
            }
        }
        Set<String> crops = new LinkedHashSet<>();
        for (GatherContracts.Crop c : p.crops()) crops.add(c.wireName());
        FarmProcess f = new FarmProcess(blockPos(p.from()), blockPos(p.to()), crops, p.replant());
        return new Start<>(f, () -> new GatherContracts.Farmed(p.from(), p.to(), p.crops(), p.replant(), f.harvested(),
                f.replanted()));
    }

    public static Order<BuildContracts.Constructed> construct(ConstructParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("construct", self);
        switch (p.structure()) {
            case BuildContracts.Tower t -> {
                int startY = (int) Math.floor(self.getY());
                int targetY = t.targetY().orElseGet(() -> startY + t.height().orElseThrow());
                if (targetY <= startY) {
                    return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, "targetY " + targetY + " is not above the feet (y "
                            + startY + ")");
                }
                if (targetY - startY > 256) {
                    return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, "a tower rises at most 256; targetY " + targetY
                            + " is " + (targetY - startY) + " above the feet");
                }
                if (targetY >= self.level().getMaxBuildHeight()) {
                    return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, "targetY " + targetY + " is above this world's build height");
                }
                TowerProcess tower = new TowerProcess(targetY, t.block().orElse(null));
                return new Start<>(tower, () -> new BuildContracts.Towered(startY, targetY, t.block(), tower.placed()));
            }
            case BuildContracts.Bridge b -> {
                Direction face = GoalResolver.snapped(self, CommonTypes.Direction.valueOf(b.direction().name()));
                BridgeProcess bridge = new BridgeProcess(face, b.distance(), b.block().orElse(null));
                BuildContracts.Compass compass = BuildContracts.Compass.valueOf(face.name());
                return new Start<>(bridge, () -> new BuildContracts.Bridged(b.direction(), compass, b.distance(), b.block(),
                        bridge.placed()));
            }
        }
    }

    public static Order<MoveContracts.Slept> sleep(SleepParams p, LivingEntity self) {
        if (!hasHands(self)) return handless("sleep", self);
        SleepProcess s = new SleepProcess(p.pos().map(Positions::blockPos).orElse(null), p.radius());
        return new Start<>(s, () -> new MoveContracts.Slept(position(s.bedPos())));
    }

    /**
     * A build order with its schematic already read: the schematic reads no world, so it is read before
     * the order goes to a body, and bytes that are no schematic this game reads throw here, as whatever
     * the reader trips on.
     */
    public static Function<LivingEntity, Order<BuildContracts.Built>> build(BuildParams p) {
        Schematic s = switch (p.schematic()) {
            case BuildContracts.Blocks b -> Schematic.of(b.w(), b.h(), b.d(), b.palette(), b.data());
            case BuildContracts.Sponge sp -> Schematic.fromSpongeSchem(java.util.Base64.getMimeDecoder().decode(sp.base64()));
        };
        return self -> build(p, s, self);
    }

    private static Order<BuildContracts.Built> build(BuildParams p, Schematic s, LivingEntity self) {
        if (!hasHands(self)) return handless("build", self);
        BuildProcess build = new BuildProcess(blockPos(p.origin()), s);
        BuildContracts.Size size = new BuildContracts.Size(s.w, s.h, s.d);
        int blocks = s.entries.size();
        return new Start<>(build, () -> new BuildContracts.Built(p.origin(), size, blocks, build.placed(), build.skipped()));
    }

    public static Order<MoveContracts.Followed> follow(FollowParams p, LivingEntity self) {
        String type = p.target() instanceof MoveContracts.QuarryType t ? t.type() : null;
        String name = p.target() instanceof MoveContracts.QuarryPlayer pl ? pl.name() : null;
        RouteParams.Parsed route = RouteParams.parse(p.route().orElse(Route.NONE));
        return new Start<>(new FollowProcess(type, name, p.radius(), p.maxIdleTicks(), p.giveUpTicks(), route.profile()),
                () -> new MoveContracts.Followed(p.target(), p.radius()));
    }

    public static Order<MoveContracts.Explored> explore(ExploreParams p, LivingEntity self) {
        ExploreProcess e = new ExploreProcess(p.centerX(), p.centerZ(), p.maxChunks());
        return new Start<>(e, () -> new MoveContracts.Explored(p.centerX(), p.centerZ(), p.maxChunks(), e.visited()));
    }

    public static Order<MoveContracts.RanAway> runAway(RunAwayParams p, LivingEntity self) {
        BlockPos src = p.from().map(Positions::blockPos).orElseGet(() -> BlockPos.containing(self.getX(), self.getY(), self.getZ()));
        BlockPosition from = position(src);
        return new Start<>(new RunAwayProcess(src, p.minDist()),
                () -> new MoveContracts.RanAway(from, p.minDist(), position(self.blockPosition())));
    }

    /** With no usable elytra and {@code groundFallback}, a walk to {@code pos} rather than a refusal. */
    public static Order<MoveContracts.Flown> elytraFly(ElytraParams p, LivingEntity self) {
        BlockPos target = p.pos().map(Positions::blockPos).orElse(null);
        boolean reactive = target != null && p.reactive().orElse(p.pitch().isEmpty());
        boolean fireworks = p.fireworks().orElse(reactive);
        if (!wearsElytra(self)) {
            if (target != null && p.groundFallback()) {
                Goal g = p.near() > 0 ? new Goal.Near(target, p.near()) : new Goal.Block(target);
                return new Start<>(new IntentProcess(new Intent(g)),
                        () -> new MoveContracts.Flown(MoveContracts.FlightMode.GROUND_FALLBACK, false));
            }
            return new Refuse<>(RefusalReason.UNSUPPORTED_CAPABILITY, "no usable elytra in the chest slot"
                    + (target != null ? "; pass groundFallback: true to walk there instead" : ""));
        }
        int maxTicks = p.ticks().orElse(reactive ? 2000 : 200);
        Float yaw = p.yaw().map(Double::floatValue).orElse(null);
        float pitch = p.pitch().map(Double::floatValue).orElse(0f);
        MoveContracts.FlightMode mode = reactive ? MoveContracts.FlightMode.REACTIVE
                : target != null ? MoveContracts.FlightMode.GOAL : MoveContracts.FlightMode.GLIDE;
        return new Start<>(new ElytraProcess(target, yaw, pitch, fireworks, p.fireworkEveryTicks(), maxTicks, p.stopXZDist(), reactive),
                () -> new MoveContracts.Flown(mode, fireworks));
    }

    /** Turn smoothly toward {@code track}, or to {@code yaw}/{@code pitch} when it is null. */
    public static BotProcess look(BlockPos track, float yaw, float pitch) {
        return new LookProcess(track, yaw, pitch);
    }

    /** Break every non-air block of the box {@code from}..{@code to}, or only {@code filterFrom} when that is not
     *  null, and put {@code fillId} in its place when that is not null. */
    public static BotProcess fill(BlockPos from, BlockPos to, String fillId, String filterFrom) {
        return new BboxFillProcess(from, to, fillId, filterFrom);
    }

    /** Whether the chest slot holds an elytra with flight left in it. */
    public static boolean wearsElytra(LivingEntity self) {
        ItemStack chest = self.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) && ElytraItem.isFlyEnabled(chest);   // vanilla's own test before it lets a player glide
    }
}
