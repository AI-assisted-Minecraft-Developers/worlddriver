package net.magicterra.agent.bot.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Resolves a block selector string into a {@link BlockState} predicate.
 *
 * <p>Two forms are supported, mirroring vanilla command syntax:
 * <ul>
 *   <li><b>Exact id</b> — {@code "minecraft:oak_log"} matches that one block.</li>
 *   <li><b>Tag</b> — a {@code '#'} prefix, {@code "#minecraft:logs"}, matches any
 *       block in that block tag (all log species, all wool colours, all ores …).</li>
 * </ul>
 *
 * <p>Shared by the block-search call sites (mc.bot.goto {@code block:}, mc.query
 * {@code filter.type}) so a single {@code #minecraft:logs} search finds every tree
 * variant instead of forcing callers to enumerate oak/acacia/spruce/… by hand.
 * An unknown/unloaded tag simply matches nothing (no block belongs to it yet).
 */
public final class BlockMatch {
    private BlockMatch() {}

    /** True when {@code selector} is a tag selector ({@code '#'}-prefixed). */
    public static boolean isTag(String selector) {
        return selector != null && selector.trim().startsWith("#");
    }

    /** Build a predicate matching {@code selector}. Returns a never-match
     *  predicate for null/blank/malformed selectors. */
    public static Predicate<BlockState> of(String selector) {
        if (selector == null || selector.isBlank()) return s -> false;
        String sel = selector.trim();
        if (sel.startsWith("#")) {
            ResourceLocation rl = ResourceLocation.tryParse(sel.substring(1));
            if (rl == null) return s -> false;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, rl);
            return s -> s.is(tag);
        }
        return s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(sel);
    }
}
