package net.magicterra.worlddriver.application.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ActiveEffect;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Attack;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockHit;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockRow;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChunkRef;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientBody;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientLook;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientPlayer;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.EntityHit;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Hit;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Inventory;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.InventorySlot;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Miss;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Stack;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Time;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Vitals;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Wear;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.EntityRow;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MenuContainer;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotRow;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.application.ItemSnap;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.WorldReadings;
import net.magicterra.worlddriver.game.ClientHop;
import net.magicterra.worlddriver.game.observe.BlockMatch;
import net.magicterra.worlddriver.game.observe.ItemCounts;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.magicterra.worlddriver.game.observe.TimeSnap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * World/player observation for {@code mc.client.player}, {@code mc.client.blocks}, {@code mc.observe.player} (client
 * fallback), {@code mc.query} (client fallback) and {@code mc.observe.container} (open menu). Stateless.
 */
public final class ClientObserve {
    private ClientObserve() {}

    /** What {@code mc.observe.player} adds for the client's own player: the crosshair and the frame rate. */
    public static Optional<ObserveContracts.ClientView> clientView() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            return p == null
                    ? Optional.<ObserveContracts.ClientView>empty()
                    : Optional.of(new ObserveContracts.ClientView(crosshair(mc, p), mc.getFps()));
        });
    }

    /** What the camera is aimed at right now (the client-side raycast). */
    private static ObserveContracts.Crosshair crosshair(Minecraft mc, LocalPlayer p) {
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            var bp = bhr.getBlockPos();
            return new ObserveContracts.BlockHit(
                    Positions.position(bp),
                    bhr.getDirection().getName(),
                    BuiltInRegistries.BLOCK
                            .getKey(p.level().getBlockState(bp).getBlock())
                            .toString(),
                    Math.sqrt(bhr.getLocation().distanceToSqr(p.getEyePosition())));
        }
        if (hr instanceof EntityHitResult ehr && ehr.getType() == HitResult.Type.ENTITY) {
            var ent = ehr.getEntity();
            var ep = ent.position();
            return new ObserveContracts.EntityHit(
                    ent.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString(),
                    new Point(ep.x, ep.y, ep.z),
                    Math.sqrt(ent.distanceToSqr(p)));
        }
        return new ObserveContracts.Miss();
    }

    /**
     * Every non-air cell within Chebyshev {@code radius} (clamped to 0–16) of the centre, the player's feet when any
     * axis is null, kept only if it matches one of {@code filterIds} (ids or {@code #tags}) when given. Names the
     * chunks the client has not received, whose cells read as air.
     */
    public static BlockScan scanBlocks(int radius, Double cx, Double cy, Double cz, Set<String> filterIds) {
        final int r = Math.max(0, Math.min(16, radius));
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
            }
            double dx;
            double dy;
            double dz;
            if (cx != null && cy != null && cz != null) {
                dx = cx;
                dy = cy;
                dz = cz;
            } else if (mc.player != null) {
                dx = mc.player.getX();
                dy = mc.player.getY();
                dz = mc.player.getZ();
            } else {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no player to centre on; pass a center");
            }
            int bx = (int) Math.floor(dx);
            int by = (int) Math.floor(dy);
            int bz = (int) Math.floor(dz);
            // Build matchers once: each filter id may be an exact id or a '#tag'
            // selector (e.g. #minecraft:logs matches every log species).
            List<Predicate<BlockState>> matchers = null;
            if (filterIds != null && !filterIds.isEmpty()) {
                matchers = new ArrayList<>();
                for (String f : filterIds) {
                    matchers.add(BlockMatch.of(f));
                }
            }
            List<BlockRow> blocks = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r; oy++) {
                    for (int oz = -r; oz <= r; oz++) {
                        pos.set(bx + ox, by + oy, bz + oz);
                        var bs = level.getBlockState(pos);
                        if (bs.isAir()) {
                            continue;
                        }
                        if (matchers != null && !matchesAny(matchers, bs)) {
                            continue;
                        }
                        // The same id and property map as the server-side q='blocks' rows.
                        blocks.add(new BlockRow(
                                Positions.position(pos), RegistryIds.blockId(bs), RegistryIds.stateProperties(bs)));
                    }
                }
            }
            // ClientLevel answers air for a chunk it has not received, which the rows cannot tell
            // apart from real air; name those chunks so a caller can refuse the scan.
            List<ChunkRef> unloaded = new ArrayList<>();
            for (int chx = (bx - r) >> 4; chx <= (bx + r) >> 4; chx++) {
                for (int chz = (bz - r) >> 4; chz <= (bz + r) >> 4; chz++) {
                    if (level.getChunkSource().getChunkNow(chx, chz) == null) {
                        unloaded.add(new ChunkRef(chx, chz));
                    }
                }
            }
            return new BlockScan(
                    blocks,
                    new BlockPosition(bx, by, bz),
                    r,
                    unloaded.isEmpty() ? Optional.empty() : Optional.of(unloaded));
        });
    }

    private static boolean matchesAny(List<Predicate<BlockState>> matchers, BlockState bs) {
        for (var m : matchers) {
            if (m.test(bs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entities other than this player whose boxes touch a cube of half-side {@code radius} (clamped to 0–32) around
     * the centre, the player when any axis is null; {@code hostileFilter}, when set, keeps only hostile or only
     * non-hostile ones.
     */
    public static List<EntityRow> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        final int r = Math.max(0, Math.min(32, radius));
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
            }
            double dx;
            double dy;
            double dz;
            if (cx != null && cy != null && cz != null) {
                dx = cx;
                dy = cy;
                dz = cz;
            } else if (mc.player != null) {
                dx = mc.player.getX();
                dy = mc.player.getY();
                dz = mc.player.getZ();
            } else {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no player to centre on; pass a center");
            }
            AABB box = new AABB(dx - r, dy - r, dz - r, dx + r, dy + r, dz + r);
            List<EntityRow> entities = new ArrayList<>();
            // entitiesForRendering() returns the loaded ClientLevel entity set —
            // includes mobs, items, projectiles, players. Filter by bbox + class.
            for (Entity e : level.entitiesForRendering()) {
                if (e == mc.player) {
                    continue;
                }
                if (!box.intersects(e.getBoundingBox())) {
                    continue;
                }
                boolean hostile = e instanceof Enemy;
                if (hostileFilter != null && hostileFilter != hostile) {
                    continue;
                }
                LivingEntity le = e instanceof LivingEntity l ? l : null;
                // Effects stay server-only: the client is told its own MobEffects, not other entities'.
                entities.add(new EntityRow(
                        Optional.of(new Point(e.getX(), e.getY(), e.getZ())),
                        Optional.of(Positions.position(e.blockPosition())),
                        Optional.of(RegistryIds.typeId(e)),
                        Optional.of(e.getUUID().toString()),
                        Optional.of(e.getId()),
                        le == null ? Optional.empty() : Optional.of((double) le.getHealth()),
                        Optional.empty(),
                        Optional.of(hostile),
                        le == null ? Optional.empty() : Optional.of((double) le.getMaxHealth()),
                        Optional.of(Math.sqrt(e.distanceToSqr(dx, dy, dz)))));
            }
            return entities;
        });
    }

    /** The threats within {@code radius} of this player, most dangerous first, and the incoming projectiles. */
    public static ObserveContracts.Threats observeThreats(int radius) {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return new ObserveContracts.Threats(List.of(), List.of());
            }
            // The bot's own scan derives client projectile velocities; with no bot, each is read off the entity.
            BotApi bot = BotHooks.impl();
            return bot != null ? bot.threats(radius) : WorldReadings.threats(mc.level, mc.player, radius, Map.of());
        });
    }

    /** The open container menu's slots, under the screen context they were read in. */
    public static MenuContainer menu() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            String context = ScreenContext.current();
            if (!(s instanceof AbstractContainerScreen<?> acs)) {
                return new MenuContainer(context, false, Optional.empty(), Optional.empty());
            }
            var menu = acs.getMenu();
            List<SlotRow> slots = new ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                slots.add(ItemSnap.slotRow(i, menu.slots.get(i).getItem()));
            }
            return new MenuContainer(context, true, Optional.of(s.getClass().getSimpleName()), Optional.of(slots));
        });
    }

    private static Wear wear(ItemStack st) {
        return ItemSnap.wear(st);
    }

    /** {@code mc.client.player}: this client's own player, grouped as the typed contract has them. */
    public static ClientPlayer player() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) {
                return new ClientPlayer(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        mc.getFps());
            }
            var eye = p.getEyePosition();
            ClientLevel lvl = mc.level;
            Optional<String> eyeBlock = Optional.empty();
            Optional<String> feetBlock = Optional.empty();
            if (lvl != null) {
                eyeBlock = Optional.of(BuiltInRegistries.BLOCK
                        .getKey(lvl.getBlockState(BlockPos.containing(eye)).getBlock())
                        .toString());
                feetBlock = Optional.of(BuiltInRegistries.BLOCK
                        .getKey(lvl.getBlockState(p.blockPosition()).getBlock())
                        .toString());
            }
            ClientBody body = new ClientBody(
                    p.onGround(),
                    new Point(eye.x, eye.y, eye.z),
                    p.getPose().name(),
                    p.isInWall(),
                    p.isInWater(),
                    p.isUnderWater(),
                    p.isCrouching(),
                    eyeBlock,
                    feetBlock);
            List<ActiveEffect> fx = new ArrayList<>();
            for (var inst : p.getActiveEffects()) {
                fx.add(new ActiveEffect(
                        BuiltInRegistries.MOB_EFFECT
                                .getKey(inst.getEffect().value())
                                .toString(),
                        inst.getAmplifier(),
                        inst.getDuration()));
            }
            Vitals vitals = new Vitals(
                    p.getHealth(),
                    p.getMaxHealth(),
                    p.getAirSupply(),
                    p.getMaxAirSupply(),
                    p.getFoodData().getFoodLevel(),
                    p.getFoodData().getSaturationLevel(),
                    p.experienceLevel,
                    fx);
            long dt = p.level().getDayTime();
            Time time = new Time(dt, dt / TimeSnap.DAY_TICKS, TimeSnap.timeOfDay(dt), TimeSnap.phase(dt));
            // AttackSnap's arithmetic, read straight into the record.
            float scale = p.getAttackStrengthScale(0.0f);
            float period = p.getCurrentItemAttackStrengthDelay();
            Attack attack =
                    new Attack(scale, scale >= 1.0f, (int) Math.ceil(period * (1.0f - scale)), (int) Math.ceil(period));
            List<InventorySlot> slots = new ArrayList<>();
            var inv = p.getInventory();
            int totalSlots = inv.items.size() + inv.armor.size() + inv.offhand.size();
            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                slots.add(new InventorySlot(
                        i, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), wear(st)));
            }
            ItemStack held = p.getMainHandItem();
            Optional<Stack> mainHand = held == null || held.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Stack(
                            BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount(), wear(held)));
            Inventory inventory = new Inventory(inv.selected, mainHand, slots, ItemCounts.bag(p));
            return new ClientPlayer(
                    true,
                    Optional.of(p.getGameProfile().getName()),
                    Optional.of(p.getUUID().toString()),
                    Optional.of(p.level().dimension().location().toString()),
                    Optional.of(new Point(p.getX(), p.getY(), p.getZ())),
                    Optional.of(new ClientLook(p.getYRot(), p.getXRot())),
                    Optional.of(body),
                    Optional.of(vitals),
                    Optional.of(time),
                    Optional.of(
                            mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "unknown"),
                    Optional.of(attack),
                    Optional.of(inventory),
                    Optional.of(hit(mc, p)),
                    mc.getFps());
        });
    }

    private static Hit hit(Minecraft mc, LocalPlayer p) {
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            var bp = bhr.getBlockPos();
            return new BlockHit(
                    Positions.position(bp),
                    bhr.getDirection().getName(),
                    BuiltInRegistries.BLOCK
                            .getKey(p.level().getBlockState(bp).getBlock())
                            .toString(),
                    Math.sqrt(bhr.getLocation().distanceToSqr(p.getEyePosition())));
        }
        if (hr instanceof EntityHitResult ehr && hr.getType() == HitResult.Type.ENTITY) {
            var ent = ehr.getEntity();
            var ep = ent.position();
            return new EntityHit(
                    ent.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString(),
                    new Point(ep.x, ep.y, ep.z),
                    Math.sqrt(ent.distanceToSqr(p)));
        }
        return new Miss();
    }
}
