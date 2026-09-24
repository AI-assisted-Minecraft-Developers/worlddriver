package net.magicterra.worlddriver.bot;

import java.util.Set;

/**
 * Which blocks the bot may PLACE, and which it must not spend. Dist-neutral (callable from both
 * client and dedicated-server WorldViews); the {@code BotConfig.is*Block} entry points delegate here
 * so call sites keep their historical spelling. Split out of {@code BotConfig} when that file hit
 * its 3000-line budget — the predicates read {@link BotConfig#buildBlockWhitelist} at call time
 * and touch no class-init state, so the move carries none of the persistence block's clinit trap.
 */
public final class BuildBlocks {
    private BuildBlocks() {}

    /** Whether {@code block} may be used as a PLACED build block (pillar/bridge/parkour
     *  footing). Default heuristic, in body order: not a
     *  {@link net.minecraft.world.level.block.FallingBlock}, not an {@link #isInteractiveBlock}
     *  (a GUI block placed as filler booby-traps every later place-click), motion-blocking, and
     *  — the actual shape test — a STURDY top face. Sturdy, NOT "full collision cube": that
     *  older test rejected mud/soul_sand/soul_soil for being 14/16 tall though the bot stands
     *  on them fine. It still rejects the thin/partial blocks this exists for (bottom slabs,
     *  fences, carpets, bamboo, saplings) that would leave the bot stuck on the path it builds. A non-empty
     *  {@link BotConfig#buildBlockWhitelist} overrides the SHAPE test ONLY — the other three still
     *  apply. */
    public static boolean isUsableBuildBlock(net.minecraft.world.level.block.Block block) {
        if (block instanceof net.minecraft.world.level.block.FallingBlock) return false;
        // Interactive blocks are resources, not dirt. Placing one both spends a
        // crafted station as filler AND booby-traps every later place-click against
        // it: right-click on a menu block OPENS ITS GUI instead of placing, and an
        // open screen swallows all movement input (gap #57/#58 — live death #3:
        // the walker plugged with the bot's fresh furnace, re-clicked it, and the
        // FurnaceScreen paralysed the engine while a zombie chewed). Safety-class
        // rejection: applies even under a buildBlockWhitelist.
        if (isInteractiveBlock(block)) return false;
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        if (!st.blocksMotion()) return false;
        Set<String> wl = BotConfig.buildBlockWhitelist;
        if (!wl.isEmpty()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            return id != null && wl.contains(id.toString());
        }
        // Sturdy top face, not geometric full cube — see the javadoc. The old
        // isCollisionShapeFullBlock rejected mud/soul_sand/soul_soil (14/16 tall) though they
        // are standable: live round69, a bot holding ONLY 17 mud + 9 sand + 37 gravel (sand and
        // gravel fall, rejected above) had no foothold, so the +2 climb-out from water deadlocked.
        return st.isFaceSturdy(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.core.Direction.UP);
    }

    /** A block whose use-click opens a GUI (block-entity holders + the menu-opening
     *  work-station family). Shared by {@link #isUsableBuildBlock} (never place one
     *  as filler) and the walker's place actuator (never CLICK one as a support —
     *  the click opens the GUI instead of placing; gap #57/#58). */
    public static boolean isInteractiveBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.EntityBlock
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.SmithingTableBlock
                || block instanceof net.minecraft.world.level.block.CartographyTableBlock
                || block instanceof net.minecraft.world.level.block.FletchingTableBlock
                || block instanceof net.minecraft.world.level.block.LoomBlock;
    }

    /** Like {@link #isUsableBuildBlock} but ALSO accepts FallingBlocks (sand/gravel) — for a
     *  strictly VERTICAL pillar-up where the placed block rests ON the solid rung directly
     *  below it (supported, so it never falls). {@link #isUsableBuildBlock} excludes falling
     *  blocks because a BRIDGE places them over a gap (unsupported → they drop); that hazard
     *  does not exist for an in-place pillar. A bot carrying ONLY sand/gravel (deserts, beaches,
     *  rivers — very common) otherwise has NO usable foothold and bob-stalls a +2/+3 ascent ram
     *  it could trivially pillar out of (live 2026-06-24 -1987,111: holdPlaceable rejected the
     *  bot's 11 sand + 8 gravel → 332-tick stall). Use ONLY where the placement is provably
     *  supported below (the pillar-recovery actuator); never for bridges/parkour-place. */
    public static boolean isUsablePillarBlock(net.minecraft.world.level.block.Block block) {
        if (isUsableBuildBlock(block)) return true;
        if (!(block instanceof net.minecraft.world.level.block.FallingBlock)) return false;
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        if (!st.blocksMotion()) return false;
        Set<String> wl = BotConfig.buildBlockWhitelist;
        if (!wl.isEmpty()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            return id != null && wl.contains(id.toString());
        }
        return st.isFaceSturdy(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.core.Direction.UP);
    }

    /** Resources the bot deliberately gathered — must not be spent as disposable
     *  pillar/scaffold filler (gap#81). Deliberately NARROW (wood family, the
     *  observed waste); extend by adding tags if a run surfaces another wasted
     *  resource — do not speculate now. */
    public static boolean isValuablePlacementBlock(net.minecraft.world.level.block.Block block) {
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        return st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.PLANKS);
    }

    /** Pure ItemStack-level core of a "throwaway" support block — a usable build block (see
     *  {@link #isUsableBuildBlock}) that is NOT a gathered resource (see
     *  {@link #isValuablePlacementBlock}); gap#81. Hosted here (not in
     *  {@code BotInteract}, the client-facing caller) so it stays dist-neutral: {@code
     *  BotInteract} mixes in unrelated client-only methods (LocalPlayer/Minecraft), and the
     *  NeoForge RuntimeDistCleaner refuses to load THAT class at all on a dedicated server
     *  (confirmed live via GameTestServer — "Attempted to load class LocalPlayer for invalid
     *  dist DEDICATED_SERVER" — even though this predicate itself never touches a client type),
     *  so the gametest matrix calls this dist-neutral entry point instead. {@code
     *  BotInteract.isThrowawaySupportBlock} delegates here for production use. */
    public static boolean isThrowawaySupportBlock(net.minecraft.world.item.ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return false;
        return isUsableBuildBlock(bi.getBlock()) && !isValuablePlacementBlock(bi.getBlock());
    }
}
