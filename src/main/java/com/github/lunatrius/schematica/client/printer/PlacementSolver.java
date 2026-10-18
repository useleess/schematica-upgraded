// Placement by simulation after the behavior of Buildprint's placement solver, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.client.world.CellState;
import com.github.lunatrius.schematica.reference.Reference;

/**
 * Every click the player could make (a face of a solid neighbor, or the target itself when placing in the air, at a few
 * heights, with the current or one of the six straight rotations) is first simulated: the held item is used in a scratch
 * cell that sees the real neighbors. The first click whose block gets the schematic state is used. When the clicks place
 * the block but none in the right state, nothing is placed yet.
 */
public final class PlacementSolver {
    public static final class Solution {
        public final BlockPos pos;
        public final EnumFacing side;
        public final Vec3d hit;
        /** The rotation to report before the click; null keeps the player's. */
        public final PrinterLook look;
        public final boolean neighbor;
        /** The feet position to report before the click (FakeStand); null keeps the player's. */
        public final Vec3d stand;

        Solution(BlockPos pos, EnumFacing side, Vec3d hit, PrinterLook look, boolean neighbor) {
            this(pos, side, hit, look, neighbor, null);
        }

        Solution(BlockPos pos, EnumFacing side, Vec3d hit, PrinterLook look, boolean neighbor, Vec3d stand) {
            this.pos = pos; this.side = side; this.hit = hit; this.look = look; this.neighbor = neighbor; this.stand = stand;
        }
    }

    /** No click gives the schematic state. */
    public static final Solution REFUSE = new Solution(BlockPos.ORIGIN, EnumFacing.UP, Vec3d.ZERO, null, false);

    private static final float[] SIDE_HEIGHTS = {0.5f, 0.25f, 0.75f};
    /** Click order: the block below first, as a player builds. */
    private static final EnumFacing[] ORDER = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, EnumFacing.UP};

    private PlacementSolver() {}

    /** The faces of the neighbors a block can be placed against: not air, fluid or replaceable. */
    public static EnumFacing[] solidSides(World world, BlockPos pos) {
        java.util.List<EnumFacing> sides = new java.util.ArrayList<>();
        for (EnumFacing side : ORDER) {
            BlockPos neighbor = pos.offset(side);
            IBlockState state = world.getBlockState(neighbor);
            if (state.getBlock().isAir(state, world, neighbor) || state.getMaterial().isLiquid()
                || state.getBlock().isReplaceable(world, neighbor)) continue;
            sides.add(side);
        }
        return sides.toArray(new EnumFacing[0]);
    }

    /** A solution, REFUSE when the block can be placed but never in the schematic state, or null when it cannot be simulated. */
    public static Solution solve(World world, EntityPlayer player, ItemStack stack, IBlockState target, BlockPos pos, EnumFacing[] solidSides, boolean inAir) {
        return solve(world, player, stack, target, pos, solidSides, inAir, null, false);
    }

    /** As solve; with fakeStand, positions next to, above or below the block are tried when no rotation fits (FakeStand). */
    public static Solution solve(World world, EntityPlayer player, ItemStack stack, IBlockState target, BlockPos pos, EnumFacing[] solidSides,
                                 boolean inAir, boolean fakeStand) {
        return solve(world, player, stack, target, pos, solidSides, inAir, null, fakeStand);
    }

    /** As solve; with onlySide, only clicks on that face of the neighbor in that direction (doors and beds: the block below). */
    public static Solution solve(World world, EntityPlayer player, ItemStack stack, IBlockState target, BlockPos pos, EnumFacing[] solidSides,
                                 boolean inAir, EnumFacing onlySide, boolean fakeStand) {
        if (stack.isEmpty()) return null;
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        PrinterLook[] rotations = PrinterLook.candidates(yaw);
        PrinterLook[] looks = new PrinterLook[rotations.length + 1];
        System.arraycopy(rotations, 0, looks, 1, rotations.length);
        CellState.Scratch scratch = CellState.Scratch.around(world, pos.getX(), pos.getY(), pos.getZ());
        Set<Integer> results = new HashSet<>();
        double px = player.posX, py = player.posY, pz = player.posZ;
        try {
            Solution found = search(scratch, player, stack, target, looks, pos, solidSides, inAir, onlySide, results);
            if (found != null) return found;
            // Placeable, but no rotation gives the state: up and down facings need the player above or below the block
            if (!results.isEmpty() && fakeStand) {
                for (Vec3d stand : FakeStand.candidates(world, player, pos)) {
                    player.posX = stand.x; player.posY = stand.y; player.posZ = stand.z;
                    found = search(scratch, player, stack, target, looks, pos, solidSides, inAir, onlySide, results);
                    player.posX = px; player.posY = py; player.posZ = pz;
                    if (found != null) return new Solution(found.pos, found.side, found.hit, found.look, found.neighbor, stand);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            Reference.logger.debug("Could not simulate placing {}", target, error);
            return null;
        } finally {
            player.rotationYaw = yaw;
            player.rotationPitch = pitch;
            player.posX = px; player.posY = py; player.posZ = pz;
        }
        // The block can be placed, but never in the schematic state: wait for a neighbour that allows it
        return results.isEmpty() ? null : REFUSE;
    }

    private static Solution search(CellState.Scratch scratch, EntityPlayer player, ItemStack stack, IBlockState target, PrinterLook[] looks,
                                   BlockPos pos, EnumFacing[] solidSides, boolean inAir, EnumFacing onlySide, Set<Integer> results) {
        for (EnumFacing direction : solidSides) {
            if (onlySide != null && direction != onlySide) continue;
            Solution found = probe(scratch, player, stack, target, looks, pos, pos.offset(direction), direction.getOpposite(), true, results);
            if (found != null) return found;
        }
        if (inAir && onlySide == null) {
            for (EnumFacing direction : ORDER) {
                Solution found = probe(scratch, player, stack, target, looks, pos, pos, direction.getOpposite(), false, results);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Solution probe(CellState.Scratch scratch, EntityPlayer player, ItemStack stack, IBlockState target, PrinterLook[] looks,
                                  BlockPos pos, BlockPos clicked, EnumFacing side, boolean neighbor, Set<Integer> results) {
        float[] heights = side.getAxis() != EnumFacing.Axis.Y ? SIDE_HEIGHTS : new float[] {side == EnumFacing.UP ? 1f : 0f};
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        BlockPos local = clicked.subtract(pos);
        for (float height : heights) {
            float hx = side.getFrontOffsetX() == 0 ? 0.5f : side.getFrontOffsetX() > 0 ? 1f : 0f;
            float hz = side.getFrontOffsetZ() == 0 ? 0.5f : side.getFrontOffsetZ() > 0 ? 1f : 0f;
            for (PrinterLook look : looks) {
                player.rotationYaw = look == null ? yaw : look.yaw;
                player.rotationPitch = look == null ? pitch : look.pitch;
                IBlockState placed = place(scratch, player, stack, pos, local, side, hx, height, hz);
                player.rotationYaw = yaw;
                player.rotationPitch = pitch;
                // the lit twin counts: an item places the unpowered repeater where the schematic has the powered one
                if (placed == null || placed.getBlock() != target.getBlock()
                    && !com.github.lunatrius.schematica.util.BlockGroups.switchedPair(placed.getBlock(), target.getBlock())) continue;
                results.add(Block.getStateId(placed));
                if (StateClicks.placedMatches(placed, target)) {
                    return new Solution(clicked, side, new Vec3d(clicked.getX() + hx, clicked.getY() + height, clicked.getZ() + hz), look, neighbor);
                }
            }
        }
        return null;
    }

    /**
     * The state the item use makes in the scratch cell: the held item is used on the clicked position (relative to the
     * cell) with the player moved next to the cell; null when nothing was placed there.
     */
    static IBlockState place(CellState.Scratch scratch, EntityPlayer player, ItemStack stack, BlockPos pos, BlockPos clicked, EnumFacing side,
                             float hx, float hy, float hz) {
        scratch.removeTileEntity(BlockPos.ORIGIN);
        scratch.setBlockState(BlockPos.ORIGIN, Blocks.AIR.getDefaultState(), 3);
        double px = player.posX, py = player.posY, pz = player.posZ;
        // The slot is written directly: setHeldItem would play the equip sound for every simulated click
        int slot = player.inventory.currentItem;
        ItemStack held = player.inventory.mainInventory.get(slot);
        player.inventory.mainInventory.set(slot, stack.copy());
        try {
            player.posX = px - pos.getX(); player.posY = py - pos.getY(); player.posZ = pz - pos.getZ();
            stack.getItem().onItemUse(player, scratch, clicked, EnumHand.MAIN_HAND, side, hx, hy, hz);
            IBlockState placed = scratch.getBlockState(BlockPos.ORIGIN);
            return placed.getBlock() == Blocks.AIR ? null : placed;
        } finally {
            player.posX = px; player.posY = py; player.posZ = pz;
            player.inventory.mainInventory.set(slot, held);
        }
    }
}
