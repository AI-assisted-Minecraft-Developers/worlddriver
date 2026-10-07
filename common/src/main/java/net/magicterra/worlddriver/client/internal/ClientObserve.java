package net.magicterra.worlddriver.client.internal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.ClientContracts.ActiveEffect;
import net.magicterra.worlddriver.api.methods.ClientContracts.Attack;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlockHit;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlockRow;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChunkRef;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientBody;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientPlayer;
import net.magicterra.worlddriver.api.methods.ClientContracts.EntityHit;
import net.magicterra.worlddriver.api.methods.ClientContracts.Hit;
import net.magicterra.worlddriver.api.methods.ClientContracts.Inventory;
import net.magicterra.worlddriver.api.methods.ClientContracts.InventorySlot;
import net.magicterra.worlddriver.api.methods.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.ClientContracts.Miss;
import net.magicterra.worlddriver.api.methods.ClientContracts.Stack;
import net.magicterra.worlddriver.api.methods.ClientContracts.Time;
import net.magicterra.worlddriver.api.methods.ClientContracts.Vitals;
import net.magicterra.worlddriver.api.methods.ClientContracts.Wear;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRow;
import net.magicterra.worlddriver.bot.util.RegistryIds;
import net.magicterra.worlddriver.api.methods.ScreenContracts.MenuContainer;
import net.magicterra.worlddriver.api.methods.ScreenContracts.SlotRow;

import net.magicterra.worlddriver.bot.util.ClientHop;
import net.magicterra.worlddriver.api.methods.ObserveContracts;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.magicterra.worlddriver.bot.util.BlockMatch;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.util.AttackSnap;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.magicterra.worlddriver.bot.util.TimeSnap;
import net.minecraft.world.entity.LivingEntity;

/**
 * World/player observation for {@code mc.client.player}, {@code mc.client.blocks},
 * {@code mc.observe.player} (client fallback), {@code mc.query} (client fallback) and
 * {@code mc.observe.container} (open menu). Stateless.
 */
public final class ClientObserve {
    private ClientObserve() {}

    public static Map<String, Object> observePlayer() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) {
                return Map.of("present", false);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("name", p.getGameProfile().getName());
            out.put("uuid", p.getUUID().toString());
            out.put("dimension", p.level().dimension().location().toString());
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", p.getX()); pos.put("y", p.getY()); pos.put("z", p.getZ());
            out.put("pos", pos);
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", p.getYRot()); look.put("pitch", p.getXRot());
            out.put("look", look);
            out.put("onGround", p.onGround());
            // Client-physics truth the SERVER-side mc.observe.player can't give:
            // the client LocalPlayer's PREDICTED pose + eye position + the
            // ClientLevel block at the eye/feet cells. pose/eye-height and
            // isInWall are exactly what the client-tick reflexes (autoSwim,
            // antiSuffocate) gate on, so this lets an agent see what the client
            // sees — and diff it against the server when they desync (e.g. a
            // command-placed block the client crawl-evades). Reached explicitly
            // via mc.client.player even when a server is attached.
            var eye = p.getEyePosition();
            out.put("eyePos", Map.of("x", eye.x, "y", eye.y, "z", eye.z));
            out.put("pose", p.getPose().name());
            out.put("inWall", p.isInWall());
            out.put("inWater", p.isInWater());
            out.put("underWater", p.isUnderWater());
            out.put("crouching", p.isCrouching());
            ClientLevel lvl = mc.level;
            if (lvl != null) {
                BlockPos eyeCell = BlockPos.containing(eye);
                BlockPos feetCell = p.blockPosition();
                out.put("eyeBlock", BuiltInRegistries.BLOCK.getKey(
                        lvl.getBlockState(eyeCell).getBlock()).toString());
                out.put("feetBlock", BuiltInRegistries.BLOCK.getKey(
                        lvl.getBlockState(feetCell).getBlock()).toString());
            }
            out.put("health", p.getHealth());
            out.put("maxHealth", p.getMaxHealth());
            // gap#70 (live death #18): air was invisible on both observation paths — a
            // bot sinking toward drowning gave no signal short of the one-shot
            // player.enteredWater event. Mirrors ObserveApi's field of the same name.
            out.put("air", p.getAirSupply());
            out.put("maxAir", p.getMaxAirSupply());
            out.put("food", p.getFoodData().getFoodLevel());
            out.put("saturation", p.getFoodData().getSaturationLevel());
            out.put("xpLevel", p.experienceLevel);
            // Active MobEffects — regen, hunger debuff from rotten flesh,
            // potion effects, etc. Empty when nothing is active. Each entry:
            // {id, amplifier, durationTicks}. Without this a caller can eat
            // rotten flesh and never know the hunger debuff is ticking.
            List<Object> fx = new ArrayList<>();
            for (var inst : p.getActiveEffects()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString());
                e.put("amplifier", inst.getAmplifier());
                e.put("durationTicks", inst.getDuration());
                fx.add(e);
            }
            out.put("effects", fx);
            // World time so the caller can plan around day/night (mobs spawn
            // at night, sleep needs night, sky-light ticks matter for farms).
            if (p.level() != null) {
                out.put("time", TimeSnap.snapshot(p.level().getDayTime()));
            }
            out.put("gameMode", mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "unknown");
            out.put("selectedSlot", p.getInventory().selected);
            // Same field, same shape as the server snapshot (AttackSnap is the single source).
            out.put("attack", AttackSnap.snapshot(p));
            ItemStack held = p.getMainHandItem();
            Map<String, Object> hand = new LinkedHashMap<>();
            if (held == null || held.isEmpty()) {
                hand.put("empty", true);
            } else {
                hand.put("id", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
                hand.put("count", held.getCount());
                ItemSnap.putWear(hand, held);
            }
            out.put("mainHand", hand);
            // Full inventory snapshot — saves an openInventory + screen.tree
            // round-trip every time a caller wants to know "do I have a pickaxe".
            // ItemSnap.inventoryRows is the single enumeration; the server snapshot
            // (ObserveApi.playerSnapshot) emits this field through the same call, so the
            // two sides cannot drift the way gap #41's rows did.
            out.put("inventory", ItemSnap.inventoryRows(p));
            // Aggregated id -> count, the exact shape mc.recipe.resolve / mc.plan.acquire
            // take as `have`. Same helper the craft executor counts with, so the bag the
            // agent plans against is the bag the executor reaches into — and the server
            // snapshot emits this field through the same call (ObserveApi.playerSnapshot).
            out.put("items", CraftProcess.inventorySnapshot(p));
            // HitResult — what the camera is aimed at right now (client-side raycast).
            HitResult hr = mc.hitResult;
            Map<String, Object> hit = new LinkedHashMap<>();
            if (hr == null || hr.getType() == HitResult.Type.MISS) {
                hit.put("type", "miss");
            } else if (hr.getType() == HitResult.Type.BLOCK
                    && hr instanceof BlockHitResult bhr) {
                var bp = bhr.getBlockPos();
                hit.put("type", "block");
                hit.put("blockPos", Map.of("x", bp.getX(), "y", bp.getY(), "z", bp.getZ()));
                hit.put("face", bhr.getDirection().getName());
                var bs = p.level().getBlockState(bp);
                hit.put("block", BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
                hit.put("distance", Math.sqrt(bhr.getLocation().distanceToSqr(p.getEyePosition())));
            } else if (hr.getType() == HitResult.Type.ENTITY
                    && hr instanceof EntityHitResult ehr) {
                var ent = ehr.getEntity();
                hit.put("type", "entity");
                hit.put("entityId", ent.getId());
                hit.put("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString());
                var ep = ent.position();
                hit.put("pos", Map.of("x", ep.x, "y", ep.y, "z", ep.z));
                hit.put("distance", Math.sqrt(ent.distanceToSqr(p)));
            }
            out.put("hit", hit);
            out.put("fps", mc.getFps());
            return out;
        });
    }

    public static BlockScan scanBlocks(int radius, Double cx, Double cy, Double cz, Set<String> filterIds) {
        final int r = Math.max(0, Math.min(16, radius));
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
            }
            double dx, dy, dz;
            if (cx != null && cy != null && cz != null) {
                dx = cx; dy = cy; dz = cz;
            } else if (mc.player != null) {
                dx = mc.player.getX(); dy = mc.player.getY(); dz = mc.player.getZ();
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
                for (String f : filterIds) matchers.add(BlockMatch.of(f));
            }
            List<BlockRow> blocks = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int ox = -r; ox <= r; ox++) {
                for (int oy = -r; oy <= r; oy++) {
                    for (int oz = -r; oz <= r; oz++) {
                        pos.set(bx + ox, by + oy, bz + oz);
                        var bs = level.getBlockState(pos);
                        if (bs.isAir()) continue;
                        if (matchers != null) {
                            boolean ok = false;
                            for (var m : matchers) { if (m.test(bs)) { ok = true; break; } }
                            if (!ok) continue;
                        }
                        // The same id and property map as the server-side q='blocks' rows.
                        blocks.add(new BlockRow(Positions.position(pos), RegistryIds.blockId(bs), RegistryIds.stateProperties(bs)));
                    }
                }
            }
            // ClientLevel answers air for a chunk it has not received, which the rows cannot tell
            // apart from real air; name those chunks so a caller can refuse the scan.
            List<ChunkRef> unloaded = new ArrayList<>();
            for (int chx = (bx - r) >> 4; chx <= (bx + r) >> 4; chx++)
                for (int chz = (bz - r) >> 4; chz <= (bz + r) >> 4; chz++)
                    if (level.getChunkSource().getChunkNow(chx, chz) == null)
                        unloaded.add(new ChunkRef(chx, chz));
            return new BlockScan(blocks, new BlockPosition(bx, by, bz), r,
                    unloaded.isEmpty() ? Optional.empty() : Optional.of(unloaded));
        });
    }

    public static List<EntityRow> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        final int r = Math.max(0, Math.min(32, radius));
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
            }
            double dx, dy, dz;
            if (cx != null && cy != null && cz != null) { dx = cx; dy = cy; dz = cz; }
            else if (mc.player != null) { dx = mc.player.getX(); dy = mc.player.getY(); dz = mc.player.getZ(); }
            else throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no player to centre on; pass a center");
            AABB box = new AABB(
                    dx - r, dy - r, dz - r, dx + r, dy + r, dz + r);
            List<EntityRow> entities = new ArrayList<>();
            // entitiesForRendering() returns the loaded ClientLevel entity set —
            // includes mobs, items, projectiles, players. Filter by bbox + class.
            for (Entity e : level.entitiesForRendering()) {
                if (e == mc.player) continue;
                if (!box.intersects(e.getBoundingBox())) continue;
                boolean hostile = e instanceof Enemy;
                if (hostileFilter != null && hostileFilter != hostile) continue;
                LivingEntity le = e instanceof LivingEntity l ? l : null;
                // Effects stay server-only: the client is told its own MobEffects, not other entities'.
                entities.add(new EntityRow(Optional.of(new Point(e.getX(), e.getY(), e.getZ())),
                        Optional.of(Positions.position(e.blockPosition())), Optional.of(RegistryIds.typeId(e)),
                        Optional.of(e.getUUID().toString()), Optional.of(e.getId()),
                        le == null ? Optional.empty() : Optional.of((double) le.getHealth()), Optional.empty(),
                        Optional.of(hostile), le == null ? Optional.empty() : Optional.of((double) le.getMaxHealth()),
                        Optional.of(Math.sqrt(e.distanceToSqr(dx, dy, dz)))));
            }
            return entities;
        });
    }

    public static ObserveContracts.Threats observeThreats(int radius) {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return new ObserveContracts.Threats(List.of(), List.of());
            return ThreatScanner.reading(ClientThreatScanner.compute(mc, radius));
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

    /**
     * {@code mc.client.player}: the same reads as {@link #observePlayer}, which still answers the
     * untyped {@code mc.observe.player} fallback, grouped as the typed contract has them.
     */
    public static ClientPlayer player() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) {
                return new ClientPlayer(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), mc.getFps());
            }
            var eye = p.getEyePosition();
            ClientLevel lvl = mc.level;
            Optional<String> eyeBlock = Optional.empty(), feetBlock = Optional.empty();
            if (lvl != null) {
                eyeBlock = Optional.of(BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(BlockPos.containing(eye)).getBlock()).toString());
                feetBlock = Optional.of(BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(p.blockPosition()).getBlock()).toString());
            }
            ClientBody body = new ClientBody(p.onGround(), new Point(eye.x, eye.y, eye.z), p.getPose().name(),
                    p.isInWall(), p.isInWater(), p.isUnderWater(), p.isCrouching(), eyeBlock, feetBlock);
            List<ActiveEffect> fx = new ArrayList<>();
            for (var inst : p.getActiveEffects()) {
                fx.add(new ActiveEffect(BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString(),
                        inst.getAmplifier(), inst.getDuration()));
            }
            Vitals vitals = new Vitals(p.getHealth(), p.getMaxHealth(), p.getAirSupply(), p.getMaxAirSupply(),
                    p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.experienceLevel, fx);
            long dt = p.level().getDayTime();
            Time time = new Time(dt, dt / TimeSnap.DAY_TICKS, TimeSnap.timeOfDay(dt), TimeSnap.phase(dt));
            // AttackSnap's arithmetic, read straight into the record.
            float scale = p.getAttackStrengthScale(0.0f);
            float period = p.getCurrentItemAttackStrengthDelay();
            Attack attack = new Attack(scale, scale >= 1.0f, (int) Math.ceil(period * (1.0f - scale)), (int) Math.ceil(period));
            List<InventorySlot> slots = new ArrayList<>();
            var inv = p.getInventory();
            int totalSlots = inv.items.size() + inv.armor.size() + inv.offhand.size();
            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                slots.add(new InventorySlot(i, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), wear(st)));
            }
            ItemStack held = p.getMainHandItem();
            Optional<Stack> mainHand = held == null || held.isEmpty() ? Optional.empty()
                    : Optional.of(new Stack(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount(), wear(held)));
            Inventory inventory = new Inventory(inv.selected, mainHand, slots, CraftProcess.inventorySnapshot(p));
            return new ClientPlayer(true, Optional.of(p.getGameProfile().getName()), Optional.of(p.getUUID().toString()),
                    Optional.of(p.level().dimension().location().toString()), Optional.of(new Point(p.getX(), p.getY(), p.getZ())),
                    Optional.of(new Look(p.getYRot(), p.getXRot())), Optional.of(body), Optional.of(vitals), Optional.of(time),
                    Optional.of(mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "unknown"), Optional.of(attack),
                    Optional.of(inventory), Optional.of(hit(mc, p)), mc.getFps());
        });
    }

    private static Hit hit(Minecraft mc, LocalPlayer p) {
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            var bp = bhr.getBlockPos();
            return new BlockHit(Positions.position(bp), bhr.getDirection().getName(),
                    BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(bp).getBlock()).toString(),
                    Math.sqrt(bhr.getLocation().distanceToSqr(p.getEyePosition())));
        }
        if (hr instanceof EntityHitResult ehr && hr.getType() == HitResult.Type.ENTITY) {
            var ent = ehr.getEntity();
            var ep = ent.position();
            return new EntityHit(ent.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString(),
                    new Point(ep.x, ep.y, ep.z), Math.sqrt(ent.distanceToSqr(p)));
        }
        return new Miss();
    }

    /** Property value as the string a /setblock predicate would use ("true", "north", "3"). */
}
