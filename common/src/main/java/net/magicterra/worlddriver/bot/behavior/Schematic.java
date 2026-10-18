package net.magicterra.worlddriver.bot.behavior;

import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;

public final class Schematic {
    public final int w, h, d;
    public final List<Entry> entries;
    public Schematic(int w, int h, int d, List<Entry> entries) {
        this.w = w; this.h = h; this.d = d; this.entries = entries;
    }
    public static final class Entry {
        public final int dx, dy, dz;
        public final String blockId;
        public Entry(int dx, int dy, int dz, String blockId) {
            this.dx = dx; this.dy = dy; this.dz = dz; this.blockId = blockId;
        }
    }

    /**
     * A schematic given as a palette and {@code [dx, dy, dz, paletteIdx]} rows; air entries are dropped.
     * The caller's contract has already bounded the sizes and indices; these checks only keep a direct
     * Java caller from building a schematic that cannot be placed. {@code maxCells} is the caller's volume cap.
     */
    public static Schematic of(int w, int h, int d, List<String> palette, List<List<Integer>> data, int maxCells) {
        if (w <= 0 || h <= 0 || d <= 0) throw new IllegalArgumentException("w/h/d must be > 0");
        if ((long) w * h * d > maxCells) {
            throw new IllegalArgumentException("schematic too large (max " + maxCells + " blocks)");
        }
        List<Entry> entries = new ArrayList<>();
        for (List<Integer> r : data) {
            if (r.size() != 4) throw new IllegalArgumentException("data entry: [dx,dy,dz,paletteIdx]");
            int pi = r.get(3);
            if (pi < 0 || pi >= palette.size()) throw new IllegalArgumentException("paletteIdx out of range: " + pi);
            String id = palette.get(pi);
            if ("minecraft:air".equals(id)) continue;
            entries.add(new Entry(r.get(0), r.get(1), r.get(2), id));
        }
        // Sort bottom-up so each placement has supporting blocks below it.
        entries.sort((a, b) -> Integer.compare(a.dy, b.dy));
        return new Schematic(w, h, d, entries);
    }

    /**
     * Parse a Sponge {@code .schem} payload (v1/v2/v3) into a Schematic.
     * The format is GZIP'd NBT — try compressed first, fall back to raw.
     * v3 wraps the schematic fields under a top-level {@code "Schematic"}
     * compound; v2 (the WorldEdit default through 2023) puts them at the
     * root. We support both by sniffing for the wrapper.
     *
     * Volume cap: {@code maxCells}, the same cap as the palette form — the
     * bot still has to walk to and place each block. Larger schematics would
     * lock the player in for hours; callers should crop or chunk first.
     *
     * BlockEntities (chests, signs, etc.) and biomes are intentionally
     * ignored — BuildProcess only knows how to place vanilla block ids
     * without state, so adding them here would silently lose data.
     */
    public static Schematic fromSpongeSchem(byte[] bytes, int maxCells) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes),
                    NbtAccounter.unlimitedHeap());
        } catch (Exception gzipFail) {
            try {
                root = NbtIo.read(
                        new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            } catch (Exception rawFail) {
                throw new IllegalArgumentException(
                        "not a valid .schem NBT (tried gzip and raw): " + gzipFail.getMessage());
            }
        }
        // v3 nests everything under "Schematic"; v2 is flat. Sniff which.
        CompoundTag s = root.contains("Schematic")
                ? root.getCompound("Schematic")
                : root;
        int w = s.getShort("Width") & 0xFFFF;
        int h = s.getShort("Height") & 0xFFFF;
        int l = s.getShort("Length") & 0xFFFF;
        if (w <= 0 || h <= 0 || l <= 0) throw new IllegalArgumentException(
                "missing or zero Width/Height/Length");
        if ((long) w * h * l > maxCells) throw new IllegalArgumentException(
                "schematic too large (" + (w * h * l) + " > " + maxCells + " blocks; crop first)");
        // v3 path: blocks live under s.Blocks.{Palette,Data}
        CompoundTag blocksHolder = s.contains("Blocks")
                ? s.getCompound("Blocks") : s;
        CompoundTag palette = blocksHolder.contains("Palette")
                ? blocksHolder.getCompound("Palette")
                : (s.contains("Palette") ? s.getCompound("Palette") : null);
        if (palette == null) throw new IllegalArgumentException("Palette compound missing");
        byte[] data = blocksHolder.contains("Data")
                ? blocksHolder.getByteArray("Data")
                : (s.contains("BlockData") ? s.getByteArray("BlockData") : null);
        if (data == null || data.length == 0) throw new IllegalArgumentException("BlockData missing");
        // Palette is keyed by block-state strings → palette idx. Invert.
        int max = -1;
        for (String key : palette.getAllKeys()) max = Math.max(max, palette.getInt(key));
        if (max < 0) throw new IllegalArgumentException("empty Palette");
        String[] idByIdx = new String[max + 1];
        for (String key : palette.getAllKeys()) {
            // Strip block-state suffix "[prop=val,...]" — BuildProcess only
            // places base blocks. Properties get dropped silently.
            int br = key.indexOf('[');
            String baseId = br >= 0 ? key.substring(0, br) : key;
            idByIdx[palette.getInt(key)] = baseId;
        }
        // Decode varint-packed BlockData, X→Z→Y order, into entries.
        List<Entry> entries = new ArrayList<>();
        int i = 0, idx = 0;
        while (i < data.length) {
            int value = 0, shift = 0;
            while (true) {
                if (i >= data.length) throw new IllegalArgumentException(
                        "BlockData varint truncated at idx " + idx);
                byte b = data[i++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IllegalArgumentException("varint too long");
            }
            if (value < 0 || value >= idByIdx.length) throw new IllegalArgumentException(
                    "palette idx " + value + " out of range [0," + (idByIdx.length - 1) + "]");
            String id = idByIdx[value];
            if (id != null && !"minecraft:air".equals(id) && !"minecraft:cave_air".equals(id)
                    && !"minecraft:void_air".equals(id)) {
                int x = idx % w;
                int z = (idx / w) % l;
                int y = idx / (w * l);
                entries.add(new Entry(x, y, z, id));
            }
            idx++;
        }
        if (idx != w * h * l) throw new IllegalArgumentException(
                "BlockData count " + idx + " != W*H*L " + (w * h * l));
        entries.sort((a, b) -> Integer.compare(a.dy, b.dy));
        return new Schematic(w, h, l, entries);
    }
}
