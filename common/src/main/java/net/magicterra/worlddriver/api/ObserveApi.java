package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.util.AttackSnap;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.magicterra.worlddriver.bot.world.AsciiMapRenderer;
import net.magicterra.worlddriver.bot.world.HazardCell;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.SceneModel;
import net.magicterra.worlddriver.bot.world.ServerWorldView;
import net.magicterra.worlddriver.bot.world.SurvivalFacts;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.model.AgentEvent;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mc.observe.*} handlers, extracted from {@code AgentApi}. Reads the live
 * {@link ServerLevel} and the shared event ring buffer through an
 * {@link AgentApi} back-reference; dispatch still flows through
 * {@code AgentApi.route} (single source of truth).
 */
public final class ObserveApi {
    private final AgentApi api;
    ObserveApi(AgentApi api) { this.api = api; }

    public long cursor() {
        api.level(); // require server: pre-fix this returned 0 even with no world, which is misleading
        return api.eventSeq.get();
    }

    public List<AgentEvent> eventsSince(long cursor) {
        return eventsSince(cursor, null, AgentApi.EVENT_BUFFER_CAP);
    }

    /**
     * Pull events with {@code seq > cursor}, optionally filtered to a set of
     * type names, capped at {@code limit} entries. Iteration is FIFO so the
     * cap drops the *newest* events when the window is too large — callers
     * should bump cursor to the last returned event's seq and re-call.
     */
    public List<AgentEvent> eventsSince(long cursor, Set<String> types, int limit) {
        api.level(); // require server: same reason as cursor() above
        synchronized (api.eventsLock) {
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent e : api.events) {
                if (e.seq <= cursor) continue;
                if (types != null && !types.contains(e.type)) continue;
                out.add(e);
                if (out.size() >= limit) break;
            }
            return out;
        }
    }

    /**
     * Read a BlockEntity's container contents at {@code pos}. Works for any block
     * with a vanilla {@link Container} (chest, barrel, furnace, hopper, etc.).
     * Returns {@code {present, type, slots:[{id,count}|null...]}} — slot indices
     * are vanilla (furnace: 0=input, 1=fuel, 2=output). Cheap enough to poll from
     * {@code mc.wait.condition} to detect furnace cook completion, chest fill, etc.
     */
    public Map<String, Object> container(Map<String, Object> params) {
        Params p = Params.of(params);
        BlockPos pos = p.getPos("pos");
        if (pos == null) throw new IllegalArgumentException("pos required");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            BlockPos bp = pos;
            BlockEntity be = level.getBlockEntity(bp);
            Map<String, Object> out = new LinkedHashMap<>();
            if (be == null) { out.put("present", false); return out; }
            out.put("present", true);
            out.put("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            if (be instanceof Container c) {
                List<Object> slots = new ArrayList<>();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty()) { slots.add(null); continue; }
                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                    slot.put("count", s.getCount());
                    ItemSnap.putWear(slot, s);
                    slots.add(slot);
                }
                out.put("slots", slots);
            }
            return out;
        });
    }

    /**
     * Snapshot a single player's state. When {@code name} is null/blank, returns
     * the first player on the server (single-player default); otherwise looks up
     * by exact GameProfile name. Returns {present:false} when nobody matches —
     * never throws — so AI scripts can probe before connecting.
     */
    public Map<String, Object> player(String name) {
        api.level(); // assert attached
        MinecraftServer s = api.server;
        return api.onServerThread(() -> {
            ServerPlayer pl = null;
            if (name != null && !name.isBlank()) {
                pl = s.getPlayerList().getPlayerByName(name);
            } else {
                List<ServerPlayer> all = s.getPlayerList().getPlayers();
                if (!all.isEmpty()) pl = all.get(0);
            }
            if (pl == null) return Map.of("present", false);
            return playerSnapshot(pl);
        });
    }

    /**
     * The body of {@link #player(String)}, split out so it can be driven against a
     * {@link ServerPlayer} that is not in the {@code PlayerList} — a FakePlayer-backed
     * avatar, which is exactly the carrier the gametest arenas use. Must be called on
     * the server thread.
     */
    public Map<String, Object> playerSnapshot(ServerPlayer pl) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("name", pl.getGameProfile().getName());
            out.put("uuid", pl.getUUID().toString());
            out.put("dimension", pl.level().dimension().location().toString());
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", pl.getX());
            pos.put("y", pl.getY());
            pos.put("z", pl.getZ());
            out.put("pos", pos);
            out.put("blockPos", new BlockPos(pl.blockPosition().getX(), pl.blockPosition().getY(), pl.blockPosition().getZ()));
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", pl.getYRot());
            look.put("pitch", pl.getXRot());
            out.put("look", look);
            out.put("onGround", pl.onGround());
            out.put("health", pl.getHealth());
            out.put("maxHealth", pl.getMaxHealth());
            // gap#70 (live death #18): air was invisible on both observation paths — a
            // bot sinking toward drowning gave no signal short of the one-shot
            // player.enteredWater event. Mirrors ClientObserve's field of the same name.
            out.put("air", pl.getAirSupply());
            out.put("maxAir", pl.getMaxAirSupply());
            out.put("food", pl.getFoodData().getFoodLevel());
            out.put("xpLevel", pl.experienceLevel);
            // Active MobEffects, mirroring the client-side snapshot
            // (ClientObserve.observePlayer). The client path grew this first;
            // headless dedicated servers — the main external-consumer scenario
            // (docs/feedback/2026-06-04, bug #4) — read the player through HERE,
            // so the server snapshot must carry the same field.
            List<Map<String, Object>> fx = new ArrayList<>();
            for (var inst : pl.getActiveEffects()) {
                Map<String, Object> fe = new LinkedHashMap<>();
                fe.put("id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString());
                fe.put("amplifier", inst.getAmplifier());
                fe.put("durationTicks", inst.getDuration());
                fx.add(fe);
            }
            out.put("effects", fx);
            // World time so the Agent can plan around day/night — gather by day,
            // hole up by night. phase: day | sunset | night | sunrise. Pair with
            // mc.wait.condition{invoke:'mc.observe.player', field:'time.phase',
            // value:'day'} to wait out a night after digging in.
            if (pl.level() != null) {
                long dt = pl.level().getDayTime();
                long tod = ((dt % 24000L) + 24000L) % 24000L;
                Map<String, Object> time = new LinkedHashMap<>();
                time.put("dayTime", dt);
                time.put("dayOfWorld", dt / 24000L);
                time.put("timeOfDay", tod);
                String phase;
                if (tod < 12000) phase = "day";
                else if (tod < 13000) phase = "sunset";
                else if (tod < 23000) phase = "night";
                else phase = "sunrise";
                time.put("phase", phase);
                out.put("time", time);
            }
            out.put("gameMode", pl.gameMode.getGameModeForPlayer().getName());
            ItemStack main = pl.getMainHandItem();
            out.put("mainHand", ApiSupport.itemSnapshot(main));
            ItemStack off = pl.getOffhandItem();
            out.put("offHand", ApiSupport.itemSnapshot(off));
            List<Map<String, Object>> hotbar = new ArrayList<>();
            for (int i = 0; i < 9; i++) hotbar.add(ApiSupport.itemSnapshot(pl.getInventory().getItem(i)));
            out.put("hotbar", hotbar);
            out.put("selectedSlot", pl.getInventory().selected);
            // Melee cooldown of the held weapon. CombatProcess gates every swing on this
            // (scale >= 1.0) but no verb ever reported it, so an agent swinging by hand via
            // mc.bot.attackEntity (which does no cooldown check, by design) hit for a fraction
            // of the weapon's damage with no way to know why. Same shape on the client.
            out.put("attack", AttackSnap.snapshot(pl));
            // Full inventory. Without it the agent saw 9 of 36 slots through THIS verb —
            // three quarters of what the bot owns was invisible, so it could not tell
            // whether it held the food/tool/blocks a decision turned on. The CLIENT
            // snapshot (ClientObserve.observePlayer) has carried `inventory` all along;
            // the server one never grew it — the same parity slip already recorded for
            // `effects` a few lines up. It bites hardest exactly where there is no
            // fallback: a client can also read every slot via mc.observe.container with
            // the inventory screen open, but a server avatar cannot (a FakePlayer cannot
            // open a menu), so for it this verb is the ONLY view of its own bag.
            //
            // Shape is deliberately byte-identical to the client's — same verb, same
            // field, same rows — so an agent never has to know which side answered:
            // non-empty rows only, vanilla Player.getInventory() indexing
            // (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand).
            List<Map<String, Object>> inv = new ArrayList<>();
            Inventory pInv = pl.getInventory();
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
            out.put("items", CraftProcess.inventorySnapshot(pl));
            // Worn armor (head/chest/legs/feet) — lets the agent see its defensive
            // loadout + durability (Phase F equip + Boss prep read this).
            Map<String, Object> armor = new LinkedHashMap<>();
            armor.put("head", ApiSupport.itemSnapshot(pl.getItemBySlot(EquipmentSlot.HEAD)));
            armor.put("chest", ApiSupport.itemSnapshot(pl.getItemBySlot(EquipmentSlot.CHEST)));
            armor.put("legs", ApiSupport.itemSnapshot(pl.getItemBySlot(EquipmentSlot.LEGS)));
            armor.put("feet", ApiSupport.itemSnapshot(pl.getItemBySlot(EquipmentSlot.FEET)));
            out.put("armor", armor);
            return out;
    }

    /**
     * ASCII spatial map around a center (default the first player) — a compact,
     * glanceable alternative to parsing block + threat JSON for tactical
     * decisions ("which way can I flee / where is the lethal drop"). Server-side,
     * so it works headless and in GameTest. North (-z) is up, West (-x) is left.
     *
     * <p>plane="xz" (default): top-down heightmap. Each cell is the local surface
     * within a Y window, classified vs the centre Y: '#' wall (≥2 above), 'v'
     * cliff/drop (≥3 below), '.' walkable, '~' water, '!' lava, ' ' no ground in
     * range (deep void). plane="xy"/"zy": vertical cross-section through the
     * centre — '#' solid, '.' air, '~' water, '!' lava (y-labelled, +y on top).
     * Hostile mobs overlay by initial (C creeper, K skeleton, s spider, Z zombie,
     * E enderman, D drowned, W witch, o slime, B blaze, P phantom, ? other); '@'
     * is the centre.
     *
     * <p>Returns {present, plane, center:{x,y,z}, radius, width, height, threats,
     * legend, map:"&lt;newline-joined grid&gt;"}.
     */
    public Map<String, Object> map(Map<String, Object> params) {
        Params p = Params.of(params);
        String plane = p.getString("plane", "xz").toLowerCase();
        boolean cross = plane.equals("xy") || plane.equals("zy");
        int r = p.getIntClamped("radius", 12, 1, 24);
        int vr = p.getIntClamped("height", 7, 1, 24);
        BlockPos explicit = p.getPos("center");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            BlockPos c = explicit;
            if (c == null) {
                List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
                if (all.isEmpty()) return Map.of("present", false, "error", "no player and no center");
                c = all.get(0).blockPosition();
            }
            AABB box = new AABB(c).inflate(Math.max(r, vr) + 2);
            List<Entity> mobs = new ArrayList<>();
            for (Entity e : level.getEntities((Entity) null, box))
                if (e instanceof Enemy && e.isAlive()) mobs.add(e);

            List<String> lines = cross ? renderCross(level, c, plane, r, vr, mobs)
                                       : renderTopDown(level, c, r, vr, mobs);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("plane", cross ? plane : "xz");
            out.put("center", Map.of("x", c.getX(), "y", c.getY(), "z", c.getZ()));
            out.put("radius", r);
            out.put("width", lines.isEmpty() ? 0 : lines.get(0).length());
            out.put("height", lines.size());
            out.put("threats", mobs.size());
            out.put("legend", cross
                    ? "@=center #=solid .=air ~=water !=lava; mobs C/K/s/Z/E/D/W/o/B/P; up=+y, left=-axis"
                    : "@=me .=walk #=wall(>=2up) v=cliff(>=3down) ~=water !=lava ' '=void; mobs C/K/s/Z/E/D/W/o/B/P; up=N(-z) left=W(-x)");
            out.put("map", String.join("\n", lines));
            return out;
        });
    }

    /** Top-down (x-z) heightmap rows, north (-z) first. */
    private List<String> renderTopDown(ServerLevel level, BlockPos c, int r, int vr, List<Entity> mobs) {
        int cx = c.getX(), cy = c.getY(), cz = c.getZ();
        Map<Long, Character> mobAt = new HashMap<>();
        for (Entity e : mobs) {
            BlockPos mp = e.blockPosition();
            if (Math.abs(mp.getX() - cx) > r || Math.abs(mp.getZ() - cz) > r) continue;
            mobAt.putIfAbsent(pack(mp.getX(), mp.getZ()), mobGlyph(e));
        }
        int up = 4, down = Math.max(12, vr + 4);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<String> rows = new ArrayList<>();
        for (int z = cz - r; z <= cz + r; z++) {
            StringBuilder sb = new StringBuilder();
            for (int x = cx - r; x <= cx + r; x++) {
                if (x == cx && z == cz) { sb.append('@'); continue; }
                Character mob = mobAt.get(pack(x, z));
                if (mob != null) { sb.append(mob.charValue()); continue; }
                int surf = Integer.MIN_VALUE;
                BlockState surfState = null;
                for (int y = cy + up; y >= cy - down; y--) {
                    BlockState st = level.getBlockState(m.set(x, y, z));
                    if (!st.isAir()) { surf = y; surfState = st; break; }
                }
                if (surfState == null) { sb.append(' '); continue; }
                sb.append(terrainGlyph(surfState, surf - cy));
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    /** Vertical cross-section (xy at fixed z, or zy at fixed x), top (+y) first. */
    private List<String> renderCross(ServerLevel level, BlockPos c, String plane, int r, int vr, List<Entity> mobs) {
        boolean xy = plane.equals("xy");
        int cx = c.getX(), cy = c.getY(), cz = c.getZ();
        int fixed = xy ? cz : cx, centerAxis = xy ? cx : cz;
        Map<Long, Character> mobAt = new HashMap<>();
        for (Entity e : mobs) {
            BlockPos mp = e.blockPosition();
            int third = xy ? mp.getZ() : mp.getX();
            int axis = xy ? mp.getX() : mp.getZ();
            if (Math.abs(third - fixed) > 1 || Math.abs(axis - centerAxis) > r || Math.abs(mp.getY() - cy) > vr) continue;
            mobAt.putIfAbsent(pack(axis, mp.getY()), mobGlyph(e));
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<String> rows = new ArrayList<>();
        for (int y = cy + vr; y >= cy - vr; y--) {
            StringBuilder sb = new StringBuilder(String.format("%4d|", y));
            for (int a = centerAxis - r; a <= centerAxis + r; a++) {
                if (a == centerAxis && y == cy) { sb.append('@'); continue; }
                Character mob = mobAt.get(pack(a, y));
                if (mob != null) { sb.append(mob.charValue()); continue; }
                BlockState st = level.getBlockState(m.set(xy ? a : cx, y, xy ? cz : a));
                if (st.isAir()) sb.append('.');
                else if (st.getFluidState().is(FluidTags.WATER)) sb.append('~');
                else if (st.getFluidState().is(FluidTags.LAVA)) sb.append('!');
                else sb.append('#');
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    private static char terrainGlyph(BlockState st, int h) {
        if (st.getFluidState().is(FluidTags.WATER)) return '~';
        if (st.getFluidState().is(FluidTags.LAVA)) return '!';
        if (h >= 2) return '#';
        if (h <= -3) return 'v';
        return '.';
    }

    private static long pack(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

    private static char mobGlyph(Entity e) {
        switch (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()) {
            case "creeper": return 'C';
            case "skeleton": case "stray": case "wither_skeleton": return 'K';
            case "spider": case "cave_spider": return 's';
            case "zombie": case "husk": case "zombie_villager": case "zombified_piglin": return 'Z';
            case "enderman": return 'E';
            case "drowned": return 'D';
            case "witch": return 'W';
            case "slime": case "magma_cube": return 'o';
            case "blaze": return 'B';
            case "phantom": return 'P';
            default: return '?';
        }
    }

    /**
     * Server-side hazard scene: computes a {@link HazardField} over the server
     * world around a center point (default: first player, else test origin),
     * derives survival facts (lethalCount, cornered, safeFleeStep), and
     * optionally renders an ASCII map. Works headless in GameTest.
     *
     * <p>Returns {present, center, radius, hazardSummary:{lethalCount, cornered,
     * safeFleeStep?}, authority:"server"} plus, when {@code render=="map"},
     * {rows:[...], legend:{...}}.
     */
    public Map<String, Object> scene(Map<String, Object> params) {
        Params p = Params.of(params);
        BlockPos explicit = p.getPos("center");
        int requestedRadius = p.getInt("radius", 12);
        int radius = Params.clamp(requestedRadius, 1, BotConfig.sceneQueryMaxRadius);
        String render = p.getString("render", "summary");
        List<String> overlays = p.getStringList("overlays");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            BlockPos center = explicit;
            if (center == null) {
                List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
                if (!all.isEmpty()) {
                    center = all.get(0).blockPosition();
                } else {
                    center = AgentApi.ORIGIN;
                }
            }
            ServerWorldView w = new ServerWorldView(level);
            int survivable = SurvivalMath.survivableFall(20f); // assume full HP server-side
            HazardField f = HazardField.compute(w, center, radius, survivable, BotConfig.deepWaterMax);
            int lethal = SurvivalFacts.lethalCount(f);
            boolean cornered = SurvivalFacts.cornered(f);
            // (0,1) is a placeholder threat direction (south) for the server overview;
            // the real flee direction is derived client-side from actual nearby threats.
            int[] flee = SurvivalFacts.safeFleeStep(f, 0, 1);
            SceneModel sm = new SceneModel(center, radius, f, lethal, cornered, flee);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("center", Map.of("x", center.getX(), "y", center.getY(), "z", center.getZ()));
            out.put("radius", radius);
            // Fix 2: report truncation when the requested radius exceeded the max.
            if (requestedRadius > BotConfig.sceneQueryMaxRadius) {
                out.put("truncated", true);
                out.put("requested", requestedRadius);
            }
            out.put("hazardSummary", sm.summary());
            if ("map".equals(render)) {
                out.put("rows", AsciiMapRenderer.rows(f));
                out.put("legend", AsciiMapRenderer.legend());
            }
            // Fix 3: height overlay — surface-Y statistics over all standable cells.
            if (overlays.contains("height")) {
                int centerY = center.getY();
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        HazardCell c = f.at(dx, dz);
                        if (c.standable()) {
                            int surfaceY = centerY - c.cliffDropDepth();
                            if (surfaceY < minY) minY = surfaceY;
                            if (surfaceY > maxY) maxY = surfaceY;
                        }
                    }
                }
                out.put("centerY", centerY);
                if (minY != Integer.MAX_VALUE) {
                    out.put("minY", minY);
                    out.put("maxY", maxY);
                }
            }
            out.put("authority", "server");
            return out;
        });
    }
}
