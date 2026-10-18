package com.github.lunatrius.schematica.client.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.reference.Reference;

public class BlockToItemStack {

    /** The item that places the schematic block (its pick block), or EMPTY when it has none. */
    public static ItemStack getItemStack(final EntityPlayer player, final IBlockState state, final SchematicWorld world, final BlockPos pos) {
        try {
            final ItemStack itemStack = state.getBlock().getPickBlock(state, new RayTraceResult(Vec3d.ZERO, EnumFacing.UP, pos), world, pos, player);
            if (itemStack != null) {
                return itemStack;
            }
        } catch (final Exception e) {
            Reference.logger.debug("Could not get the pick block for: {}", state, e);
        }

        return ItemStack.EMPTY;
    }
}
