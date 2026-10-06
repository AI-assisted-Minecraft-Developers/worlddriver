package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.ObserveContracts;
import net.magicterra.worlddriver.api.methods.ObserveContracts.AsciiMap;
import net.magicterra.worlddriver.api.methods.ObserveContracts.BossReading;
import net.magicterra.worlddriver.api.methods.ObserveContracts.FleeStep;
import net.magicterra.worlddriver.api.methods.ObserveContracts.HazardMap;
import net.magicterra.worlddriver.api.methods.ObserveContracts.HazardSummary;
import net.magicterra.worlddriver.api.methods.ObserveContracts.HeightStats;
import net.magicterra.worlddriver.api.methods.ObserveContracts.MapParams;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Overlay;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Plane;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Render;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Scene;
import net.magicterra.worlddriver.api.methods.ObserveContracts.SceneParams;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Threats;
import net.magicterra.worlddriver.api.methods.PlayerContract;
import net.magicterra.worlddriver.api.methods.PlayerContract.ClientView;
import net.magicterra.worlddriver.api.methods.PlayerContract.PlayerParams;
import net.magicterra.worlddriver.api.methods.PlayerContract.PlayerReading;
import net.magicterra.worlddriver.api.methods.RouteContract;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.SceneOverlays;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.magicterra.worlddriver.bot.world.AsciiMapRenderer;
import net.magicterra.worlddriver.bot.world.HazardCell;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.ServerWorldView;
import net.magicterra.worlddriver.bot.world.SurvivalFacts;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code mc.observe.*} handlers, extracted from {@code DriverApplication}. Reads the live
 * {@link ServerLevel} and the shared event ring buffer through an
 * {@link DriverApplication} back-reference; dispatch still flows through
 * {@code DriverApplication.route} (single source of truth).
 */
public final class ObserveApi {
    private final DriverApplication api;
    ObserveApi(DriverApplication api) { this.api = api; }

    List<ApiMethod<?, ?>> methods() {
        return List.of(
                new ApiMethod<>(PlayerContract.SPEC, this::player),
                new ApiMethod<>(ObserveContracts.THREATS, (p, ctx) -> onClient(ctx,
                        c -> c.observeThreats(p.radius()), new Threats(List.of(), List.of()))),
                new ApiMethod<>(ObserveContracts.BOSS_SPEC, (p, ctx) -> onClient(ctx,
                        c -> c.observeBoss(p.radius()), new BossReading(List.of(), Optional.empty()))),
                new ApiMethod<>(ObserveContracts.SCENE, this::scene),
                new ApiMethod<>(ObserveContracts.MAP, this::map));
    }

    public long cursor() {
        api.level(); // require server: pre-fix this returned 0 even with no world, which is misleading
        return api.eventSeq.get();
    }

    public List<DriverEvent> eventsSince(long cursor) {
        return eventsSince(cursor, null, DriverApplication.EVENT_BUFFER_CAP);
    }

    /**
     * Pull events with {@code seq > cursor}, optionally filtered to a set of
     * type names, capped at {@code limit} entries. Iteration is FIFO so the
     * cap drops the *newest* events when the window is too large — callers
     * should bump cursor to the last returned event's seq and re-call.
     */
    public List<DriverEvent> eventsSince(long cursor, Set<String> types, int limit) {
        api.level(); // require server: same reason as cursor() above
        synchronized (api.eventsLock) {
            List<DriverEvent> out = new ArrayList<>();
            for (DriverEvent e : api.events) {
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
     * The named player, or the first on the server. A client JVM with no server attached (joined to a remote
     * server) knows one player, its own, and reads it on the client thread.
     */
    CompletionStage<PlayerReading> player(PlayerParams p, CallContext ctx) {
        if (api.server == null) {
            BotApi bot = BotHooks.impl();
            ClientDriverApi c = ClientHooks.impl();
            if (bot != null && c != null) return bot.clientHop().submit(() -> clientPlayer(bot, c, p.name()), ctx);
        }
        api.level(); // no server and no client: unavailable here
        return serverHop().submit(() -> {
            ServerPlayer pl = null;
            if (p.name().isPresent()) {
                pl = api.server.getPlayerList().getPlayerByName(p.name().get());
            } else {
                List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
                if (!all.isEmpty()) pl = all.get(0);
            }
            return new PlayerReading(pl == null ? Optional.empty() : Optional.of(PlayerSnapshots.of(pl, Optional.empty())));
        }, ctx);
    }

    /** Client thread. The crosshair and the frame rate are the client's alone, read from its own snapshot. */
    private static PlayerReading clientPlayer(BotApi bot, ClientDriverApi c, Optional<String> name) {
        if (!(bot.selfEntity() instanceof Player self)) return new PlayerReading(Optional.empty());
        if (name.isPresent() && !name.get().equals(self.getGameProfile().getName())) return new PlayerReading(Optional.empty());
        Map<String, Object> own = c.observePlayer();
        ClientView view = new ClientView(PlayerContract.CROSSHAIR.decode(own.get("hit")), ((Number) own.get("fps")).intValue());
        return new PlayerReading(Optional.of(PlayerSnapshots.of(self, Optional.of(view))));
    }

    /**
     * A reading only a client can take, on the client thread; {@code none} where there is no client. The
     * client's own scan helpers run inline there rather than queueing behind themselves.
     */
    private static <T> CompletionStage<T> onClient(CallContext ctx, java.util.function.Function<ClientDriverApi, T> read, T none) {
        BotApi bot = BotHooks.impl();
        ClientDriverApi c = ClientHooks.impl();
        if (bot == null || c == null) return CompletableFuture.completedFuture(none);
        return bot.clientHop().submit(() -> read.apply(c), ctx);
    }

    private ServerThreadHop serverHop() {
        ServerThreadHop hop = api.serverHop();
        if (hop == null) throw new IllegalStateException("DriverApplication not attached to a server");
        return hop;
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
     */
    CompletionStage<AsciiMap> map(MapParams p, CallContext ctx) {
        boolean cross = p.plane() != Plane.XZ;
        String plane = p.plane().wireName();
        int r = p.radius();
        int vr = p.height();
        ServerLevel level = api.level();
        return serverHop().submit(() -> {
            BlockPos c;
            if (p.center().isPresent()) {
                c = pos(p.center().get());
            } else {
                List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
                if (all.isEmpty()) throw ApiException.invalidParam("/center", ApiIssue.Code.REQUIRED, "no player is on to center on; give center");
                c = all.get(0).blockPosition();
            }
            AABB box = new AABB(c).inflate(Math.max(r, vr) + 2);
            List<Entity> mobs = new ArrayList<>();
            for (Entity e : level.getEntities((Entity) null, box))
                if (e instanceof Enemy && e.isAlive()) mobs.add(e);

            List<String> lines = cross ? renderCross(level, c, plane, r, vr, mobs)
                                       : renderTopDown(level, c, r, vr, mobs);
            String legend = cross
                    ? "@=center #=solid .=air ~=water !=lava; mobs C/K/s/Z/E/D/W/o/B/P; up=+y, left=-axis"
                    : "@=me .=walk #=wall(>=2up) v=cliff(>=3down) ~=water !=lava ' '=void; mobs C/K/s/Z/E/D/W/o/B/P; up=N(-z) left=W(-x)";
            return new AsciiMap(p.plane(), position(c), r, lines.isEmpty() ? 0 : lines.get(0).length(), lines.size(),
                    mobs.size(), legend, String.join("\n", lines));
        }, ctx);
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
     * optionally renders an ASCII map and the overlays. Works headless.
     */
    CompletionStage<Scene> scene(SceneParams p, CallContext ctx) {
        int radius = Math.min(p.radius(), BotConfig.sceneQueryMaxRadius);
        Optional<Integer> requested = p.radius() > radius ? Optional.of(p.radius()) : Optional.empty();
        ServerLevel level = api.level();
        return serverHop().submit(() -> {
            BlockPos center = p.center().map(ObserveApi::pos).orElse(null);
            int selfId = -1;
            if (center == null) {
                List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
                if (!all.isEmpty()) {
                    center = all.get(0).blockPosition();
                    selfId = all.get(0).getId();
                } else {
                    center = DriverApplication.ORIGIN;
                }
            }
            ServerWorldView w = new ServerWorldView(level);
            int survivable = SurvivalMath.survivableFall(20f); // assume full HP server-side
            HazardField f = HazardField.compute(w, center, radius, survivable, BotConfig.deepWaterMax);
            // (0,1) is a placeholder threat direction (south) for the server overview;
            // the real flee direction is derived client-side from actual nearby threats.
            int[] flee = SurvivalFacts.safeFleeStep(f, 0, 1);
            HazardSummary summary = new HazardSummary(SurvivalFacts.lethalCount(f), SurvivalFacts.cornered(f),
                    flee == null ? Optional.empty() : Optional.of(new FleeStep(flee[0], flee[1])));
            Optional<HazardMap> map = p.render() == Render.MAP
                    ? Optional.of(new HazardMap(AsciiMapRenderer.rows(f), AsciiMapRenderer.legend()))
                    : Optional.empty();
            Optional<HeightStats> height = p.overlays().contains(Overlay.HEIGHT)
                    ? Optional.of(heightStats(f, center, radius)) : Optional.empty();
            // sight / mobDensity: the route planner's own components over the same grid.
            SceneOverlays.Result overlays = SceneOverlays.apply(level, center, radius, SceneOverlays.of(f, center),
                    p.overlays().contains(Overlay.SIGHT), p.overlays().contains(Overlay.MOB_DENSITY),
                    p.route().orElse(RouteContract.Route.NONE), selfId);
            return new Scene(position(center), radius, requested, summary, map, height, overlays.sight(),
                    overlays.mobDensity());
        }, ctx);
    }

    /** Surface-Y range over every standable cell of the grid. */
    private static HeightStats heightStats(HazardField f, BlockPos center, int radius) {
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
        boolean any = minY != Integer.MAX_VALUE;
        return new HeightStats(centerY, any ? Optional.of(minY) : Optional.empty(), any ? Optional.of(maxY) : Optional.empty());
    }

    static BlockPos pos(BlockPosition p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    static BlockPosition position(BlockPos p) {
        return new BlockPosition(p.getX(), p.getY(), p.getZ());
    }
}
