// Temporary support blocks for fence gates the schematic has over air or water, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.client.world.SchematicWorld;

/**
 * In 1.12 a fence gate can only be placed on a solid block, but once placed it stays when that block goes; builds with
 * gates over air or water (water stoppers in farms) were made with a temporary block under each gate. The printer does
 * the same: a scaffold block under the gate, the gate, then the scaffold is mined away again.
 */
public final class Scaffold {
    /** Cheap full blocks used as scaffolding, the first one carried wins. */
    static final List<Block> MATERIALS = Arrays.asList(Blocks.COBBLESTONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.STONE, Blocks.PLANKS);

    private Scaffold() {}

    /**
     * Whether the schematic block at pos can only be placed with a scaffold below: a fence gate whose world position is
     * free, over a free position where the schematic has nothing to build (air or fluid).
     */
    static boolean needed(World world, SchematicWorld schematic, BlockPos local, BlockPos pos, IBlockState state) {
        if (!(state.getBlock() instanceof BlockFenceGate)) return false;
        if (!world.getBlockState(pos).getBlock().isReplaceable(world, pos) || state.getBlock().canPlaceBlockAt(world, pos)) return false;
        BlockPos below = pos.down();
        if (below.getY() < 0 || !world.getBlockState(below).getBlock().isReplaceable(world, below)) return false;
        BlockPos localBelow = local.down();
        if (!schematic.getSchematic().containsBlock(localBelow)) return true;
        IBlockState wanted = schematic.getBlockState(localBelow);
        return wanted.getBlock().isAir(wanted, schematic, localBelow) || FluidPrinter.isFluid(wanted.getBlock());
    }

    /** Whether a scaffold under pos has done its job: the gate above is built, or nothing there needs it any more. */
    static boolean done(World world, BlockPos scaffold) {
        return world.getBlockState(scaffold.up()).getBlock() instanceof BlockFenceGate;
    }

    /** Whether the world block is (still) a scaffold block. */
    static boolean isMaterial(IBlockState state) {
        return MATERIALS.contains(state.getBlock());
    }

    /** The scaffold item to place: a carried material, or cobblestone for a creative player; EMPTY when there is none. */
    static ItemStack item(InventoryPlayer inventory, boolean creative) {
        for (Block block : MATERIALS) {
            for (ItemStack stack : inventory.mainInventory) {
                if (!stack.isEmpty() && stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() == block) return stack;
            }
        }
        return creative ? new ItemStack(Blocks.COBBLESTONE) : ItemStack.EMPTY;
    }
}
