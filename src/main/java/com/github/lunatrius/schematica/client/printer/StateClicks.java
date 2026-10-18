// Fixing a placed block's adjustable state by clicking it, after the behavior of litematica-printer, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneRepeater;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;

import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * Properties a placement cannot choose but a click on the placed block cycles: a repeater's delay, a comparator's mode,
 * whether a wooden door, trapdoor or fence gate is open, and whether a lever is on. The printer clicks the block (without
 * sneaking) as often as needed once the rest of its state is right.
 */
public final class StateClicks {
    private static final Set<String> DELAY = Collections.singleton("delay"), MODE = Collections.singleton("mode"),
        OPEN = Collections.singleton("open"), POWERED = Collections.singleton("powered");

    private StateClicks() {}

    /** The properties of the block that clicks change. */
    public static Set<String> adjustable(Block block) {
        if (block instanceof BlockRedstoneRepeater) return DELAY;
        if (block instanceof BlockRedstoneComparator) return MODE;
        if (block instanceof BlockTrapDoor || block instanceof BlockFenceGate) return clickable(block) ? OPEN : Collections.emptySet();
        if (block instanceof BlockDoor) return clickable(block) ? OPEN : Collections.emptySet();
        if (block instanceof BlockLever) return POWERED;
        return Collections.emptySet();
    }

    /**
     * The rotation for clicking the block, or null for any: a fence gate opens away from the player, turning around when
     * the player faces its back, so it is clicked facing the way the gate should face.
     */
    public static PrinterLook look(IBlockState target) {
        if (!(target.getBlock() instanceof BlockFenceGate)) return null;
        return new PrinterLook(BlockGroups.stored(target).getValue(BlockFenceGate.FACING).getHorizontalAngle(), 0);
    }

    /** Iron doors and trapdoors open by redstone only. */
    @SuppressWarnings("deprecation")
    private static boolean clickable(Block block) {
        return block.getDefaultState().getMaterial() != Material.IRON;
    }

    /** The properties a placement click cannot give the block: its survival-only, adjustable and neighbour-made ones. */
    public static Set<String> unplaceable(Block block) {
        Set<String> names = new HashSet<>(PrinterStates.runtimeProperties(block));
        names.addAll(adjustable(block));
        if (block instanceof BlockDoor) names.addAll(Arrays.asList("hinge", "powered", "open"));
        if (block instanceof BlockTrapDoor || block instanceof BlockFenceGate) names.add("powered");
        // one layer is placed, the rest added by clicks (SnowLayers)
        if (block instanceof BlockSnow) names.add(SnowLayers.PROPERTY);
        return names;
    }

    /** Whether a placed (or simulated) state is the schematic state as far as a placement decides it. */
    public static boolean placedMatches(IBlockState placed, IBlockState target) {
        return (placed.getBlock() == target.getBlock() || BlockGroups.switchedPair(placed.getBlock(), target.getBlock()))
            && BlockGroups.sameProperties(placed, target, unplaceable(target.getBlock()));
    }

    /**
     * The clicks on the world block that give it the schematic state: 0 when nothing is to fix, -1 when clicks cannot
     * fix it (another block, or other properties differ).
     */
    public static int clicks(IBlockState real, IBlockState target) {
        Block block = target.getBlock();
        Set<String> adjustable = adjustable(block);
        if (real.getBlock() != block && !BlockGroups.switchedPair(real.getBlock(), block) || adjustable.isEmpty()) return -1;
        Set<String> ignored = new HashSet<>(PrinterStates.runtimeProperties(block));
        ignored.addAll(adjustable);
        if (block instanceof BlockDoor) ignored.addAll(Arrays.asList("hinge", "powered"));
        if (!BlockGroups.sameProperties(real, target, ignored)) return -1;
        IBlockState stored = BlockGroups.stored(real), wanted = BlockGroups.stored(target);
        if (block instanceof BlockRedstoneRepeater) {
            return Math.floorMod(wanted.getValue(BlockRedstoneRepeater.DELAY) - stored.getValue(BlockRedstoneRepeater.DELAY), 4);
        }
        if (block instanceof BlockRedstoneComparator) {
            return stored.getValue(BlockRedstoneComparator.MODE) == wanted.getValue(BlockRedstoneComparator.MODE) ? 0 : 1;
        }
        if (block instanceof BlockLever) return stored.getValue(BlockLever.POWERED) == wanted.getValue(BlockLever.POWERED) ? 0 : 1;
        if (block instanceof BlockDoor) {
            if (stored.getValue(BlockDoor.HALF) != BlockDoor.EnumDoorHalf.LOWER) return 0;
            return stored.getValue(BlockDoor.OPEN) == wanted.getValue(BlockDoor.OPEN) ? 0 : 1;
        }
        if (block instanceof BlockTrapDoor) return stored.getValue(BlockTrapDoor.OPEN) == wanted.getValue(BlockTrapDoor.OPEN) ? 0 : 1;
        if (block instanceof BlockFenceGate) return stored.getValue(BlockFenceGate.OPEN) == wanted.getValue(BlockFenceGate.OPEN) ? 0 : 1;
        return -1;
    }
}
