// Fluid placement for the printer and Easy Place: buckets aimed past a neighbor's face, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.UniversalBucket;

/**
 * A fluid source of the schematic is placed with a bucket (vanilla or Forge's universal bucket) holding it: the player
 * turns toward the face of a neighbor next to the target, as the bucket places its fluid in front of the block it is
 * aimed at, and uses the bucket.
 */
final class FluidPrinter {
    private static final int BUCKET = Fluid.BUCKET_VOLUME;

    private FluidPrinter() {}

    static boolean isFluid(Block block) { return block instanceof BlockLiquid || block instanceof IFluidBlock; }

    static Fluid fluid(Block block) {
        return block instanceof BlockLiquid || block instanceof IFluidBlock ? FluidRegistry.lookupFluidForBlock(block) : null;
    }

    static boolean sameFluid(Block first, Block second) {
        Fluid fluid = fluid(first);
        return fluid != null && fluid == fluid(second);
    }

    /** Whether the block at the position is a full source block of its fluid. */
    static boolean source(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof IFluidBlock) return ((IFluidBlock) state.getBlock()).canDrain(world, pos);
        return state.getBlock() instanceof BlockLiquid && state.getValue(BlockLiquid.LEVEL) == 0;
    }

    /** Whether the world already has the schematic's fluid: the same fluid, and a source where the schematic has one. */
    static boolean matches(World schematic, BlockPos local, World world, BlockPos pos) {
        Fluid expected = fluid(schematic.getBlockState(local).getBlock());
        if (expected == null || expected != fluid(world.getBlockState(pos).getBlock())) return false;
        return !source(schematic, local) || source(world, pos);
    }

    /** The filled bucket of a fluid, for the missing material list. */
    static ItemStack bucketOf(Fluid fluid) {
        if (fluid == FluidRegistry.WATER) return new ItemStack(Items.WATER_BUCKET);
        if (fluid == FluidRegistry.LAVA) return new ItemStack(Items.LAVA_BUCKET);
        return fluid == null ? ItemStack.EMPTY : FluidUtil.getFilledBucket(new FluidStack(fluid, BUCKET));
    }

    private static boolean isContainer(ItemStack stack, Fluid fluid) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemBucket || stack.getItem() instanceof UniversalBucket)) return false;
        FluidStack contained = FluidUtil.getFluidContained(stack);
        return contained != null && contained.amount >= BUCKET && contained.getFluid() == fluid;
    }

    private static List<ItemStack> findContainers(EntityPlayerSP player, Fluid fluid) {
        List<ItemStack> containers = new ArrayList<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            if (isContainer(stack, fluid)) containers.add(stack.copy());
        }
        if (containers.isEmpty() && player.capabilities.isCreativeMode) {
            ItemStack bucket = bucketOf(fluid);
            if (isContainer(bucket, fluid)) containers.add(bucket);
        }
        return containers;
    }

    /** Places the fluid with a container the selector puts into the main hand; false when no aim or container works. */
    static boolean place(Minecraft minecraft, Fluid fluid, BlockPos pos, Predicate<ItemStack> selectContainer) {
        if (fluid == null || !fluid.canBePlacedInWorld()) return false;
        EntityPlayerSP player = minecraft.player;
        World world = minecraft.world;
        if (fluid == FluidRegistry.WATER && world.provider.doesWaterVaporize()) return false;
        List<ItemStack> containers = findContainers(player, fluid);
        if (containers.isEmpty()) return false;
        Vec3d start = player.getPositionEyes(1f);
        double reach = Math.min(5.0, minecraft.playerController.getBlockReachDistance());
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        boolean sentLook = false;
        try {
            for (EnumFacing neighbor : EnumFacing.values()) {
                double hitX = pos.getX() + 0.5 + neighbor.getFrontOffsetX() * 0.55;
                double hitY = pos.getY() + 0.5 + neighbor.getFrontOffsetY() * 0.55;
                double hitZ = pos.getZ() + 0.5 + neighbor.getFrontOffsetZ() * 0.55;
                PlacementAim aim = new PlacementAim(hitX - start.x, hitY - start.y, hitZ - start.z);
                player.rotationYaw = aim.yaw;
                player.rotationPitch = aim.pitch;
                Vec3d look = player.getLook(1.0f);
                RayTraceResult hit = world.rayTraceBlocks(start, start.addVector(look.x * reach, look.y * reach, look.z * reach), false, true, false);
                if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) continue;
                if (!hit.getBlockPos().offset(hit.sideHit).equals(pos)) continue;
                IBlockState clicked = world.getBlockState(hit.getBlockPos());
                if (isFluid(clicked.getBlock()) || clicked.getBlock().isReplaceable(world, hit.getBlockPos())) continue;
                for (ItemStack container : containers) {
                    if (!selectContainer.test(container)) continue;
                    player.connection.sendPacket(new CPacketPlayer.Rotation(aim.yaw, aim.pitch, player.onGround));
                    sentLook = true;
                    minecraft.playerController.processRightClick(player, world, EnumHand.MAIN_HAND);
                    player.swingArm(EnumHand.MAIN_HAND);
                    return true;
                }
                return false;
            }
            return false;
        } finally {
            player.rotationYaw = yaw;
            player.rotationPitch = pitch;
            if (sentLook) player.connection.sendPacket(new CPacketPlayer.Rotation(yaw, pitch, player.onGround));
        }
    }
}
