package com.github.lunatrius.schematica.client.renderer;

import net.minecraft.block.state.IBlockState;

import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * Compares the block a schematic wants with the one the world has. States are compared by block and metadata, as the
 * stored world state is: properties derived from neighbors (stair shapes, fence connections) are not stored there.
 * Tolerances (ignoreCropAge, ignoreSurvivalStates) and replaceable groups (enableDifferentBlocks) come from BlockGroups.
 */
public final class BlockCompare {
    public enum Result { SAME, DIFFERENT_BLOCK, WRONG_STATE, WRONG_BLOCK }

    private BlockCompare() {}

    public static Result compare(IBlockState expected, IBlockState found) {
        return Result.valueOf(BlockGroups.compare(expected, found, BlockGroups.enabled).name());
    }
}
