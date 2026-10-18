// Supports before the blocks that hang on them, after the behavior of Buildprint's "build prerequisites first", by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockFalling;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;

/**
 * easyPlacePrerequisites: when the aimed schematic block cannot stand yet (a torch, rail or plant without the block
 * it needs, sand over nothing), Easy Place builds its missing support from the schematic instead, the block below
 * first. One step per click: the next click builds the aimed block or the next support.
 */
public final class Prerequisites {
    private static final EnumFacing[] ORDER = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.UP};

    private Prerequisites() {}

    /** The direction to the support to build first, or null when the aimed block can be built (or nothing helps). */
    public static EnumFacing support(World world, SchematicWorld schematic, BlockPos local, BlockPos pos) {
        if (!ConfigurationHandler.easyPlacePrerequisites) return null;
        IBlockState state = schematic.getBlockState(local);
        if (state.getBlock().isAir(state, schematic, local) || !world.getBlockState(pos).getBlock().isReplaceable(world, pos)) return null;
        boolean falls = state.getBlock() instanceof BlockFalling && BlockFalling.canFallThrough(world.getBlockState(pos.down()));
        if (!falls && state.getBlock().canPlaceBlockAt(world, pos)) return null;
        for (EnumFacing side : ORDER) {
            if (falls && side != EnumFacing.DOWN) break;
            BlockPos neededLocal = local.offset(side), neededPos = pos.offset(side);
            if (!schematic.getSchematic().containsBlock(neededLocal) || !schematic.isBlockRendered(neededLocal.getX(), neededLocal.getY(), neededLocal.getZ())) continue;
            IBlockState needed = schematic.getBlockState(neededLocal);
            if (needed.getBlock().isAir(needed, schematic, neededLocal) || FluidPrinter.isFluid(needed.getBlock())) continue;
            if (!world.getBlockState(neededPos).getBlock().isReplaceable(world, neededPos)) continue;
            if (PlacementSolver.solidSides(world, neededPos).length == 0 && !ConfigurationHandler.placeInAir) continue;
            return side;
        }
        return null;
    }
}
