// Two-block items (doors, beds, double plants) for the printer and Easy Place, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;

import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * Doors, beds and double plants are placed by one item use that also sets their second block (the upper half, the
 * head). Only the first block is placed, by clicking the top of the block below it like a player does; the item
 * takes its facing from the player's yaw.
 */
public final class MultiBlockPlacement {
    public enum Kind { DOOR, BED, DOUBLE_PLANT }

    private MultiBlockPlacement() {}

    public static Kind kind(Block block) {
        if (block instanceof BlockDoor) return Kind.DOOR;
        if (block instanceof BlockBed) return Kind.BED;
        if (block instanceof BlockDoublePlant) return Kind.DOUBLE_PLANT;
        return null;
    }

    /** The upper half of a door or plant, or the head of a bed: set by placing the other block. */
    public static boolean secondary(IBlockState state) {
        IBlockState stored = BlockGroups.stored(state);
        Block block = stored.getBlock();
        if (block instanceof BlockDoor) return stored.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER;
        if (block instanceof BlockBed) return stored.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD;
        if (block instanceof BlockDoublePlant) return stored.getValue(BlockDoublePlant.HALF) == BlockDoublePlant.EnumBlockHalf.UPPER;
        return false;
    }

    /** The direction from the first block to the second one. */
    public static EnumFacing secondDirection(Kind kind, IBlockState first) {
        return kind == Kind.BED ? first.getValue(BlockBed.FACING) : EnumFacing.UP;
    }

    /** The facing an item use gives for a player yaw (ItemDoor and ItemBed: the horizontal direction looked at). */
    public static EnumFacing facing(float yaw) {
        return EnumFacing.fromAngle(yaw);
    }

    /** The facing the schematic wants of the first block; null for any (double plants). */
    public static EnumFacing wantedFacing(Kind kind, IBlockState first) {
        IBlockState stored = BlockGroups.stored(first);
        switch (kind) {
            case DOOR: return stored.getValue(BlockDoor.FACING);
            case BED: return stored.getValue(BlockBed.FACING);
            default: return null;
        }
    }

    /** One of the four horizontal yaws giving the facing, the one nearest the current yaw first. */
    public static PrinterLook look(float currentYaw, EnumFacing facing) {
        for (PrinterLook candidate : PrinterLook.candidates(currentYaw)) {
            if (candidate.pitch == 0 && facing(candidate.yaw) == facing) return candidate;
        }
        return null;
    }

    /**
     * Whether a world block has the schematic state as far as a placement decides it: a door's open state and hinge
     * and a bed's occupied state cannot be chosen by a placement unless the server applies them (accurate placement).
     */
    public static boolean sameState(Kind kind, IBlockState expected, IBlockState found, boolean accurate) {
        if (expected.getBlock() != found.getBlock()) return false;
        Set<String> ignored = new HashSet<>();
        if (kind == Kind.DOOR && !accurate) ignored.addAll(Arrays.asList("hinge", "open", "powered"));
        if (kind == Kind.BED) ignored.add("occupied");
        return BlockGroups.sameProperties(expected, found, ignored);
    }
}
