package net.magicterra.worlddriver.bot.rules;

import java.util.List;
import net.minecraft.core.BlockPos;

/** What a cell's risk is made of; the preview asks the profile ({@link ProfileRisk}), a test asks a table. */
public interface RiskAt {
    /** The observers seeing the cell, as (id, type). */
    List<ThreatSnapshot.Threat> seeing(BlockPos cell);
    /** Distance to the nearest mob of the mob condition; +∞ without one. */
    double nearestMob(BlockPos cell);
    /** The regions the cell is inside, as {@code avoid#1} / {@code forbid#2}. */
    List<String> regions(BlockPos cell);
}
