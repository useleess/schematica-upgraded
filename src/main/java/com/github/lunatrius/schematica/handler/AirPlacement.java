// Server side of printer air placement for blocks that stay without support once placed, 2026.
package com.github.lunatrius.schematica.handler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import com.github.lunatrius.schematica.world.schematic.BlockStates;

/**
 * In 1.12 a fence gate needs a solid block below to be placed, but stays once placed. Instead of a scaffold block, a
 * Plus client asks a Plus server to place the gate in the air. The server checks what a normal placement would: edit
 * rights, reach, the held item, a free position, no entity in the way, and fires the place event so protection mods
 * can cancel it.
 */
public final class AirPlacement {
    private AirPlacement() {}

    /** Whether a block may be placed in the air: only fence gates. */
    public static boolean supports(IBlockState state) {
        return state != null && state.getBlock() instanceof BlockFenceGate;
    }

    /** Places the held block at pos as the wanted state (name[properties]); true when it was placed. */
    public static boolean place(EntityPlayer player, BlockPos pos, String wantedState) {
        if (!ConfigurationHandler.airPlacementEnabled || player == null || player.isSpectator() || !player.capabilities.allowEdit) return false;
        World world = player.world;
        IBlockState wanted = BlockStates.parse(wantedState);
        if (!supports(wanted) || !world.isBlockLoaded(pos) || !world.isBlockModifiable(player, pos)) return false;
        double reach = ExtendedReach.placementReach(player);
        if (player.getDistanceSqToCenter(pos) > reach * reach) return false;
        if (!world.getBlockState(pos).getBlock().isReplaceable(world, pos)) return false;
        ItemStack stack = player.getHeldItemMainhand();
        Block block = wanted.getBlock();
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock) || ((ItemBlock) stack.getItem()).getBlock() != block) return false;
        AxisAlignedBB box = wanted.getCollisionBoundingBox(world, pos);
        if (box != null && box != Block.NULL_AABB && !world.checkNoEntityCollision(box.offset(pos))) return false;

        IBlockState placed = block.getStateForPlacement(world, pos, EnumFacing.UP, 0.5F, 1F, 0.5F, stack.getMetadata(), player, EnumHand.MAIN_HAND);
        if (ConfigurationHandler.accuratePlacementEnabled) {
            IBlockState merged = AccuratePlacement.merge(placed, wanted);
            if (merged != null) placed = merged;
        }
        BlockSnapshot snapshot = BlockSnapshot.getBlockSnapshot(world, pos);
        if (!((ItemBlock) stack.getItem()).placeBlockAt(stack, player, world, pos, EnumFacing.UP, 0.5F, 1F, 0.5F, placed)) return false;
        if (ForgeEventFactory.onPlayerBlockPlace(player, snapshot, EnumFacing.UP, EnumHand.MAIN_HAND).isCanceled()) {
            snapshot.restore(true, false);
            return false;
        }
        IBlockState now = world.getBlockState(pos);
        SoundType sound = now.getBlock().getSoundType(now, world, pos, player);
        world.playSound(null, pos, sound.getPlaceSound(), SoundCategory.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        if (!player.capabilities.isCreativeMode) stack.shrink(1);
        return true;
    }
}
