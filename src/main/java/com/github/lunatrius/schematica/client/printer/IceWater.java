// Water sources made from ice when no water bucket is carried, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In 1.12 ice broken by a survival player without Silk Touch turns into a water source when the block below it
 * blocks movement or is a liquid (BlockIce.harvestBlock); in the Nether it just vanishes, and in creative nothing is
 * harvested. The printer places ice where the schematic has a water source and mines it again (printerIceWater).
 */
public final class IceWater {
    private IceWater() {}

    /** Whether ice broken at pos would leave a water source in this world for this player. */
    static boolean works(World world, BlockPos pos, boolean creative) {
        if (creative || world.provider.doesWaterVaporize() || pos.getY() <= 0) return false;
        Material below = world.getBlockState(pos.down()).getMaterial();
        return below.blocksMovement() || below.isLiquid();
    }

    static boolean isIce(IBlockState state) { return state.getBlock() == Blocks.ICE; }

    /** A carried ice stack, or EMPTY. */
    static ItemStack item(InventoryPlayer inventory) {
        for (ItemStack stack : inventory.mainInventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() == Blocks.ICE) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** Whether mining with the stack keeps the ice as an item instead of leaving water. */
    static boolean silk(ItemStack stack) {
        return !stack.isEmpty() && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }
}
