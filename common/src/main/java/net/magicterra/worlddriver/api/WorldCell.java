package net.magicterra.worlddriver.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** One captured block state and its optional block-entity NBT. */
public record WorldCell(BlockPos pos, BlockState state, CompoundTag beTag) {}
