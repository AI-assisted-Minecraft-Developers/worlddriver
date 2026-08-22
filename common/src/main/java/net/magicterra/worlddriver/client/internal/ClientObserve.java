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

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.LivingEntity;

/**
 * World/player observation for {@code mc.observe.player} (client fallback),
 * {@code mc.query} (client fallback) and {@code mc.observe.container} (open
 * menu). Stateless; extracted from {@code ClientDriverApiImpl}.
 */
public final class ClientObserve {
    private ClientObserve() {}

    public static Map<String, Object> observePlayer() {
        return runOnClient(() -> {
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
            // Same indexing as Player.getInventory(): 0–8 hotbar, 9–35 main,
            // 36–39 armor, 40 offhand.
            List<Object> inv = new ArrayList<>();
            Inventory pInv = p.getInventory();
            int totalSlots = pInv.items.size() + pInv.armor.size() + pInv.offhand.size();
            for (int i = 0; i < totalSlots; i++) {
                ItemStack st = pInv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("slot", i);
                entry.put("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                entry.put("count", st.getCount());
                ItemSnap.putWear(entry, st);
                inv.add(entry);
            }
            out.put("inventory", inv);
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

    public static Map<String, Object> observeArea(int radius, Double cx, Double cy, Double cz,
                                                  Set<String> filterIds) {
        final int r = Math.max(0, Math.min(16, radius));
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return Map.of("present", false, "error", "no client level");
            }
            double dx, dy, dz;
            if (cx != null && cy != null && cz != null) {
                dx = cx; dy = cy; dz = cz;
            } else if (mc.player != null) {
                dx = mc.player.getX(); dy = mc.player.getY(); dz = mc.player.getZ();
            } else {
                return Map.of("present", false, "error", "no player and no center");
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
            List<Object> blocks = new ArrayList<>();
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
                        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                        Map<String, Object> e = new LinkedHashMap<>();
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("x", pos.getX()); p.put("y", pos.getY()); p.put("z", pos.getZ());
                        e.put("pos", p);
                        e.put("type", id);
                        // Blockstate property map, mirroring the server-side
                        // q='blocks' rows; omitted for property-less states.
                        if (!bs.getProperties().isEmpty()) {
                            Map<String, Object> stateMap = new LinkedHashMap<>();
                            for (var prop : bs.getProperties()) {
                                stateMap.put(prop.getName(), propValue(bs, prop));
                            }
                            e.put("state", stateMap);
                        }
                        blocks.add(e);
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("blocks", blocks);
            out.put("center", Map.of("x", bx, "y", by, "z", bz));
            out.put("radius", r);
            return out;
        });
    }

    public static Map<String, Object> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        final int r = Math.max(0, Math.min(32, radius));
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) return Map.of("present", false, "error", "no client level");
            double dx, dy, dz;
            if (cx != null && cy != null && cz != null) { dx = cx; dy = cy; dz = cz; }
            else if (mc.player != null) { dx = mc.player.getX(); dy = mc.player.getY(); dz = mc.player.getZ(); }
            else return Map.of("present", false, "error", "no player and no center");
            AABB box = new AABB(
                    dx - r, dy - r, dz - r, dx + r, dy + r, dz + r);
            List<Object> entities = new ArrayList<>();
            // entitiesForRendering() returns the loaded ClientLevel entity set —
            // includes mobs, items, projectiles, players. Filter by bbox + class.
            for (Entity e : level.entitiesForRendering()) {
                if (e == mc.player) continue;
                if (!box.intersects(e.getBoundingBox())) continue;
                boolean hostile = e instanceof Enemy;
                if (hostileFilter != null && hostileFilter != hostile) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.getId());
                // uuid for identity parity with the server-side q='entities' rows.
                // (effects stays server-only: the client doesn't receive other
                // entities' MobEffect instances, only its own.)
                row.put("uuid", e.getUUID().toString());
                row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                Map<String, Object> pos = new LinkedHashMap<>();
                pos.put("x", e.getX()); pos.put("y", e.getY()); pos.put("z", e.getZ());
                row.put("pos", pos);
                row.put("hostile", hostile);
                if (e instanceof LivingEntity le) {
                    row.put("health", (double) le.getHealth());
                    row.put("maxHealth", (double) le.getMaxHealth());
                }
                row.put("distance", Math.sqrt(e.distanceToSqr(dx, dy, dz)));
                entities.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entities", entities);
            out.put("radius", r);
            return out;
        });
    }

    public static Map<String, Object> observeThreats(int radius) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return Map.of("threats", List.of(), "incomingProjectiles", List.of());
            }
            return ThreatScanner.toMap(ClientThreatScanner.compute(mc, radius));
        });
    }

    public static Map<String, Object> observeContainerMenu() {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            if (!(s instanceof AbstractContainerScreen<?> acs)) {
                return Map.of("present", false);
            }
            var menu = acs.getMenu();
            List<Object> slots = new ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                ItemStack st = slot.getItem();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", i);
                if (st == null || st.isEmpty()) {
                    entry.put("empty", true);
                } else {
                    entry.put("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
                    entry.put("count", st.getCount());
                    ItemSnap.putWear(entry, st);
                }
                slots.add(entry);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("screen", s.getClass().getSimpleName());
            out.put("slots", slots);
            return out;
        });
    }

    /** Property value as the string a /setblock predicate would use ("true", "north", "3"). */
    private static <T extends Comparable<T>> String propValue(BlockState bs, Property<T> prop) {
        return prop.getName(bs.getValue(prop));
    }
}
