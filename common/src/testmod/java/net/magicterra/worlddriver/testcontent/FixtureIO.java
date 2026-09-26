package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The world half of a scene fixture: finding the markers a tester placed, saving the terrain
 * between the corners as a vanilla structure, putting both back. Files live under
 * {@code config/worlddriver/scenes/<name>.{nbt,json}} beside the driver's scripts directory.
 */
public final class FixtureIO {
    private FixtureIO() {}

    /** Markers are found by their block entities, chunk by chunk, this many chunks out from a centre. */
    public static final int SCAN_CHUNKS = 8;

    public static Path scenesDir() {
        String override = System.getProperty("worlddriver.scenesDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of("config", WorldDriverCommon.MOD_ID, "scenes");
    }

    public static Path jsonPath(String name) {
        return scenesDir().resolve(name + ".json");
    }

    public static Path nbtPath(String name) {
        return scenesDir().resolve(name + ".nbt");
    }

    // ------------------------------------------------------------------ markers

    /** Every marker within {@link #SCAN_CHUNKS} loaded chunks of {@code centre}, read off the chunks' entities.
     *  Any level: the anchor screen runs it on the client's copy to detect corner markers. */
    public static List<FixtureBuilder.Placed> scan(Level level, BlockPos centre) {
        List<FixtureBuilder.Placed> out = new ArrayList<>();
        ChunkPos c = new ChunkPos(centre);
        for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
            for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) {
                var chunk = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof MarkerBlockEntity m) {
                        BlockState s = level.getBlockState(m.getBlockPos());
                        if (!(s.getBlock() instanceof MarkerBlock)) continue;
                        out.add(new FixtureBuilder.Placed(m.getBlockPos().immutable(), s.getValue(MarkerBlock.ROLE), m.label(), plain(m.args())));
                    }
                }
            }
        }
        return out;
    }

    /** The scene {@code at} is inside: the markers of the smallest corner box enclosing it ({@link FixtureBuilder#select}). */
    public static List<FixtureBuilder.Placed> sceneAround(ServerLevel level, BlockPos at) {
        return FixtureBuilder.select(scan(level, at), at);
    }

    /** The markers inside the box {@code fixture} spans when its origin is at {@code origin}. */
    public static List<FixtureBuilder.Placed> markersIn(ServerLevel level, SceneFixture fixture, BlockPos origin) {
        BlockPos min = minCorner(fixture, origin);
        FixtureBuilder.Box box = new FixtureBuilder.Box(min,
                min.offset(fixture.size()[0] - 1, fixture.size()[1] - 1, fixture.size()[2] - 1));
        List<FixtureBuilder.Placed> out = new ArrayList<>();
        for (FixtureBuilder.Placed p : scan(level, origin)) if (box.contains(p.pos())) out.add(p);
        return out;
    }

    /** The loaded anchor marker (origin, or the start standing in for it) labelled {@code name}, or null. */
    public static BlockPos anchorNamed(ServerLevel level, String name) {
        for (MarkerBlockEntity be : MarkerBlockEntity.live(level)) {
            if (!be.label().equals(name)) continue;
            BlockState s = level.getBlockState(be.getBlockPos());
            if (!(s.getBlock() instanceof MarkerBlock)) continue;
            MarkerRole role = s.getValue(MarkerBlock.ROLE);
            if (role == MarkerRole.ORIGIN || role == MarkerRole.START) return be.getBlockPos().immutable();
        }
        return null;
    }

    /**
     * Where the scene named {@code name} stands in this world, with the chunks of its whole box
     * loaded so a scan sees every marker: the anchor's declared box when the anchor is loaded,
     * else the box {@code saved} remembers (its {@code placedAt}); null when neither — the caller
     * falls back to the player's feet. Loading only the anchor's chunk is not enough: a box wider
     * than a chunk keeps its goal in a chunk the caller, standing elsewhere, never loaded.
     */
    public static BlockPos locate(ServerLevel level, String name, SceneFixture saved) {
        BlockPos at = anchorNamed(level, name);
        if (at == null && saved != null && saved.placedAt() != null) {
            BlockPos origin = new BlockPos(saved.placedAt()[0], saved.placedAt()[1], saved.placedAt()[2]);
            loadChunks(level, minCorner(saved, origin), saved.size());
            at = anchorNamed(level, name);
        }
        if (at == null) return null;
        if (level.getBlockEntity(at) instanceof MarkerBlockEntity be && be.box() != null) {
            int[] b = be.box();
            loadChunks(level, at.offset(b[0], b[1], b[2]), new int[] { b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1 });
        } else if (saved != null) {
            loadChunks(level, minCorner(saved, at), saved.size());
        }
        return at;
    }

    /** Loads (synchronously) every chunk under the box of {@code size} whose minimum corner is {@code min}. */
    private static void loadChunks(ServerLevel level, BlockPos min, int[] size) {
        for (int cx = min.getX() >> 4; cx <= (min.getX() + size[0] - 1) >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= (min.getZ() + size[2] - 1) >> 4; cz++) level.getChunk(cx, cz);
        }
    }

    /** Puts markers back as {@link #scan} found them: labels, args, and the fluid their cells hold. */
    public static void putAll(ServerLevel level, List<FixtureBuilder.Placed> markers) {
        for (FixtureBuilder.Placed p : markers) put(level, p.pos(), p.role(), p.label(), p.args());
    }

    /** Takes every marker in {@code markers} out of the world: the cell becomes the fluid it held, else air. No drops. */
    public static void remove(ServerLevel level, List<FixtureBuilder.Placed> markers) {
        for (FixtureBuilder.Placed p : markers) {
            BlockState s = level.getBlockState(p.pos());
            if (s.getBlock() instanceof MarkerBlock) level.setBlockAndUpdate(p.pos(), s.getFluidState().createLegacyBlock());
        }
    }

    /** Puts one marker into the world, with its label and args; a water or lava source in the cell is kept. */
    public static void put(ServerLevel level, BlockPos pos, MarkerRole role, String label, Map<String, Object> args) {
        MarkerBlock block = MarkerContent.MARKER.get();
        MarkerFluid held = MarkerFluid.of(level.getFluidState(pos));
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(MarkerBlock.ROLE, role).setValue(MarkerBlock.FLUID, held));
        if (level.getBlockEntity(pos) instanceof MarkerBlockEntity be) {
            be.setLabel(label);
            CompoundTag tag = new CompoundTag();
            if (args != null) {
                for (var e : args.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Number n) tag.putDouble(e.getKey(), n.doubleValue());
                    else if (v instanceof Boolean b) tag.putBoolean(e.getKey(), b);
                    else if (v instanceof List<?> l && l.stream().allMatch(o -> o instanceof Number)) {
                        int[] ints = new int[l.size()];
                        for (int i = 0; i < ints.length; i++) ints[i] = ((Number) l.get(i)).intValue();
                        tag.putIntArray(e.getKey(), ints);
                    } else if (v != null) tag.putString(e.getKey(), String.valueOf(v));
                }
            }
            be.setArgs(tag);
        }
    }

    // ------------------------------------------------------------------ save

    /**
     * Saves the terrain between the corners and the fixture the markers describe. Markers are not
     * part of the structure: they come out of the world for the snapshot, so each cell is saved as
     * what the marker stood in (the water or lava it held, else air), and go back in afterwards so
     * the tester can keep editing. Skipping the marker block in {@code fillFromWorld} instead would
     * leave those cells out of the file, and a forbid marker on a lake would come back as a hole.
     */
    public static SceneFixture save(ServerLevel level, BlockPos centre, String name, String author, String verb, int budget)
            throws IOException {
        List<FixtureBuilder.Placed> markers = sceneAround(level, centre);
        FixtureBuilder.Box box = FixtureBuilder.box(markers);
        SceneFixture fixture = FixtureBuilder.fixture(name, author, java.time.OffsetDateTime.now().toString(), markers, verb, budget,
                pos -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock()).toString());
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(fixture.size()[0], fixture.size()[1], fixture.size()[2]);
        remove(level, markers);
        try {
            template.fillFromWorld(level, box.min(), size, false, null);
        } finally {
            putAll(level, markers);
        }
        // From now on the anchor carries the scene's name, so `save`, `place` and `run <name>`
        // find the scene in the world wherever the caller stands — and the box, so the anchor
        // renders it and the corner markers are no longer needed.
        FixtureBuilder.Placed anchor = FixtureBuilder.anchor(markers);
        if (level.getBlockEntity(anchor.pos()) instanceof MarkerBlockEntity be) {
            if (!be.label().equals(name)) be.setLabel(name);
            be.setBox(box.min().subtract(anchor.pos()), box.max().subtract(anchor.pos()));
        }
        Files.createDirectories(scenesDir());
        NbtIo.writeCompressed(template.save(new CompoundTag()), nbtPath(name));
        Files.writeString(jsonPath(name), fixture.toJson() + "\n", StandardCharsets.UTF_8);
        return fixture;
    }

    // ------------------------------------------------------------------ load / place

    public static SceneFixture loadJson(Path json) throws IOException {
        return SceneFixture.fromJson(Files.readString(json, StandardCharsets.UTF_8));
    }

    public static StructureTemplate loadNbt(ServerLevel level, Path nbt) throws IOException {
        return fromTag(level, NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap()));
    }

    /** {@link #loadNbt(ServerLevel, Path)} off a stream — the suite reads fixtures from the jar. */
    public static StructureTemplate loadNbt(ServerLevel level, java.io.InputStream nbt) throws IOException {
        return fromTag(level, NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap()));
    }

    private static StructureTemplate fromTag(ServerLevel level, CompoundTag tag) {
        StructureTemplate template = new StructureTemplate();
        template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
        return template;
    }

    /** The structure's minimum corner when the fixture's origin cell is at {@code origin}. */
    public static BlockPos minCorner(SceneFixture fixture, BlockPos origin) {
        return origin.offset(-fixture.origin()[0], -fixture.origin()[1], -fixture.origin()[2]);
    }

    /** Puts the terrain down with its origin cell at {@code origin}. Entities in the file are placed too. */
    public static void placeTerrain(ServerLevel level, SceneFixture fixture, StructureTemplate template, BlockPos origin) {
        BlockPos min = minCorner(fixture, origin);
        template.placeInWorld(level, min, min, new StructurePlaceSettings().setKnownShape(true), level.random, 2);
    }

    /**
     * Puts the markers back so the tester can keep editing: origin and corners from the box, the
     * rest from the fixture. The goals of the walks come back as goal markers with their kind as
     * label, and via points in their order.
     */
    public static void placeMarkers(ServerLevel level, SceneFixture fixture, BlockPos origin) {
        BlockPos min = minCorner(fixture, origin);
        // A start at the origin stands in for the origin marker (FixtureBuilder.origin), so the
        // origin marker is only put down when the cell is free for it.
        SceneFixture.Start start = fixture.markers().start();
        boolean startIsOrigin = start != null && start.pos()[0] == 0 && start.pos()[1] == 0 && start.pos()[2] == 0;
        // The anchor gets the box too (offsets from itself), so it renders the outline right away.
        int[] o = fixture.origin(), sz = fixture.size();
        List<Object> boxArg = List.of(-o[0], -o[1], -o[2], sz[0] - 1 - o[0], sz[1] - 1 - o[1], sz[2] - 1 - o[2]);
        if (!startIsOrigin) put(level, origin, MarkerRole.ORIGIN, fixture.name(), Map.of("box", boxArg));
        put(level, min, MarkerRole.CORNER, "", null);
        put(level, min.offset(fixture.size()[0] - 1, fixture.size()[1] - 1, fixture.size()[2] - 1), MarkerRole.CORNER, "", null);
        SceneFixture.Markers mk = fixture.markers();
        if (mk.start() != null) {
            Map<String, Object> startArgs = new LinkedHashMap<>();
            startArgs.put("yaw", (double) mk.start().yaw());
            if (startIsOrigin) startArgs.put("box", boxArg);
            put(level, at(origin, mk.start().pos()), MarkerRole.START, startIsOrigin ? fixture.name() : "", startArgs);
        }
        int n = 0;
        for (SceneFixture.Leg leg : fixture.legs()) {
            if (leg.goal() == null) continue;
            n++;
            String kind = leg.goalKind() == null ? "block" : leg.goalKind();
            put(level, at(origin, leg.goal()), MarkerRole.GOAL, fixture.legs().size() > 1 ? n + " " + kind : kind, null);
        }
        for (SceneFixture.Via v : mk.via()) put(level, at(origin, v.pos()), MarkerRole.VIA, String.valueOf(v.order()), null);
        for (SceneFixture.Pass p : mk.pass()) put(level, at(origin, p.pos()), MarkerRole.PASS, String.valueOf(p.radius()), null);
        for (int[] f : mk.forbid()) put(level, at(origin, f), MarkerRole.FORBID, "", null);
        if (mk.stand() != null) put(level, at(origin, mk.stand()), MarkerRole.STAND, "", null);
        for (SceneFixture.Watch w : mk.watch()) put(level, at(origin, w.pos()), MarkerRole.WATCH, w.want(), null);
    }

    public static BlockPos at(BlockPos origin, int[] rel) {
        return origin.offset(rel[0], rel[1], rel[2]);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> plain(CompoundTag tag) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            Tag t = tag.get(key);
            if (t instanceof net.minecraft.nbt.NumericTag num) out.put(key, num.getAsDouble());
            else if (t instanceof IntArrayTag ints) {
                List<Object> l = new ArrayList<>();
                for (int v : ints.getAsIntArray()) l.add(v);
                out.put(key, l);
            } else if (t != null) out.put(key, t.getAsString());
        }
        return out;
    }
}
