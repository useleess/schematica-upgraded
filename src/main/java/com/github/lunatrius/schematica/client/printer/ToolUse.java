// Blocks built with a tool instead of an item, after the behavior of Buildprint's assist tools, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockDirt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemFlintAndSteel;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.handler.ConfigurationHandler;

/**
 * printUseTools: farmland is tilled from the dirt or grass already there with a hoe, and a nether portal is lit with
 * flint and steel on the obsidian below its lowest blocks (a portal has no item). Farmland on nothing gets its dirt
 * placed first as usual.
 */
public final class ToolUse {
    /** A click on a block's top face. */
    public static final class Click {
        final BlockPos pos;
        Click(BlockPos pos) { this.pos = pos; }
    }

    private ToolUse() {}

    /** The click on the world block that builds the schematic block with a tool; null when none applies. */
    public static Click click(World world, IBlockState state, IBlockState real, BlockPos pos) {
        if (!ConfigurationHandler.printUseTools) return null;
        if (state.getBlock() == Blocks.FARMLAND && tillable(world, real, pos)) return new Click(pos);
        if (state.getBlock() == Blocks.PORTAL && world.isAirBlock(pos) && world.getBlockState(pos.down()).getBlock() == Blocks.OBSIDIAN) {
            return new Click(pos.down());
        }
        return null;
    }

    /** Dirt, grass or a grass path with room above, which a hoe turns into farmland (ItemHoe.onItemUse). */
    public static boolean tillable(World world, IBlockState real, BlockPos pos) {
        return (real.getBlock() == Blocks.GRASS || real.getBlock() == Blocks.GRASS_PATH
            || real.getBlock() == Blocks.DIRT && real.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.DIRT) && world.isAirBlock(pos.up());
    }

    /** Whether a mismatch is one that a tool fixes, so that the world block is not mined. */
    public static boolean fixable(World world, IBlockState state, IBlockState real, BlockPos pos) {
        return ConfigurationHandler.printUseTools && state.getBlock() == Blocks.FARMLAND && tillable(world, real, pos);
    }

    static boolean accepts(IBlockState state, ItemStack stack) {
        if (stack.isEmpty()) return false;
        return state.getBlock() == Blocks.FARMLAND ? stack.getItem() instanceof ItemHoe : stack.getItem() instanceof ItemFlintAndSteel;
    }

    /** The item recorded as missing when no tool is carried. */
    static ItemStack missing(IBlockState state) { return new ItemStack(state.getBlock() == Blocks.FARMLAND ? Items.IRON_HOE : Items.FLINT_AND_STEEL); }

    /** Holds the tool and uses it; false when there is none or the click failed. */
    static boolean use(SchematicPrinter printer, World world, EntityPlayerSP player, IBlockState state, Click click) {
        if (!printer.swapToMatching(player.inventory, stack -> accepts(state, stack))) {
            PrinterMissingMaterials.record(missing(state));
            return false;
        }
        if (!accepts(state, player.getHeldItemMainhand())) return false;
        return printer.placeBlock(player, click.pos, EnumFacing.UP, new Vec3d(click.pos.getX() + 0.5, click.pos.getY() + 1, click.pos.getZ() + 0.5));
    }
}
