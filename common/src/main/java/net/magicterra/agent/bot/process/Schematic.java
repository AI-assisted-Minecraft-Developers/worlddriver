package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;

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

    public static Schematic parse(Map<?, ?> m) {
        int w = readInt(m.get("w"));
        int h = readInt(m.get("h"));
        int d = readInt(m.get("d"));
        if (w <= 0 || h <= 0 || d <= 0) throw new IllegalArgumentException("w/h/d must be > 0");
        if ((long) w * h * d > 4096) throw new IllegalArgumentException("schematic too large (max 4096 blocks)");
        Object paletteObj = m.get("palette");
        if (!(paletteObj instanceof List<?> palette))
            throw new IllegalArgumentException("palette[] required");
        List<String> ids = new ArrayList<>();
        for (Object o : palette) {
            if (!(o instanceof String s)) throw new IllegalArgumentException("palette entries must be strings");
            ids.add(s);
        }
        Object dataObj = m.get("data");
        if (!(dataObj instanceof List<?> data)) throw new IllegalArgumentException("data[] required");
        List<Entry> entries = new ArrayList<>();
        for (Object row : data) {
            if (!(row instanceof List<?> r) || r.size() < 4) throw new IllegalArgumentException("data entry: [dx,dy,dz,paletteIdx]");
            int dx = ((Number) r.get(0)).intValue();
            int dy = ((Number) r.get(1)).intValue();
            int dz = ((Number) r.get(2)).intValue();
            int pi = ((Number) r.get(3)).intValue();
            if (pi < 0 || pi >= ids.size()) throw new IllegalArgumentException("paletteIdx out of range: " + pi);
            String id = ids.get(pi);
            if ("minecraft:air".equals(id)) continue; // skip air entries
            entries.add(new Entry(dx, dy, dz, id));
        }
        // Sort bottom-up so each placement has supporting blocks below it.
        entries.sort((a, b) -> Integer.compare(a.dy, b.dy));
        return new Schematic(w, h, d, entries);
    }
    private static int readInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("expected number, got " + (o == null ? "null" : o.getClass().getSimpleName()));
    }

    /**
     * Parse a Sponge {@code .schem} payload (v1/v2/v3) into a Schematic.
     * The format is GZIP'd NBT — try compressed first, fall back to raw.
     * v3 wraps the schematic fields under a top-level {@code "Schematic"}
     * compound; v2 (the WorldEdit default through 2023) puts them at the
     * root. We support both by sniffing for the wrapper.
     *
     * Volume cap: 4096 blocks like the procedural path — the bot still
     * has to walk to and place each block. Larger schematics would lock
     * the player in for hours; callers should crop or chunk first.
     *
     * BlockEntities (chests, signs, etc.) and biomes are intentionally
     * ignored — BuildProcess only knows how to place vanilla block ids
     * without state, so adding them here would silently lose data.
     */
    public static Schematic fromSpongeSchem(byte[] bytes) {
        net.minecraft.nbt.CompoundTag root;
        try {
            root = net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        } catch (Exception gzipFail) {
            try {
                root = net.minecraft.nbt.NbtIo.read(
                        new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            } catch (Exception rawFail) {
                throw new IllegalArgumentException(
                        "not a valid .schem NBT (tried gzip and raw): " + gzipFail.getMessage());
            }
        }
        // v3 nests everything under "Schematic"; v2 is flat. Sniff which.
        net.minecraft.nbt.CompoundTag s = root.contains("Schematic")
                ? root.getCompound("Schematic")
                : root;
        int w = s.getShort("Width") & 0xFFFF;
        int h = s.getShort("Height") & 0xFFFF;
        int l = s.getShort("Length") & 0xFFFF;
        if (w <= 0 || h <= 0 || l <= 0) throw new IllegalArgumentException(
                "missing or zero Width/Height/Length");
        if ((long) w * h * l > 4096) throw new IllegalArgumentException(
                "schematic too large (" + (w * h * l) + " > 4096 blocks; crop first)");
        // v3 path: blocks live under s.Blocks.{Palette,Data}
        net.minecraft.nbt.CompoundTag blocksHolder = s.contains("Blocks")
                ? s.getCompound("Blocks") : s;
        net.minecraft.nbt.CompoundTag palette = blocksHolder.contains("Palette")
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
