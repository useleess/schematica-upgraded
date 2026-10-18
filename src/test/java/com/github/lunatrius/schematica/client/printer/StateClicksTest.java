package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneRepeater;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class StateClicksTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void repeaterDelayCyclesForward() {
        IBlockState repeater = Blocks.UNPOWERED_REPEATER.getDefaultState().withProperty(BlockRedstoneRepeater.FACING, EnumFacing.EAST);
        assertEquals(2, StateClicks.clicks(repeater, repeater.withProperty(BlockRedstoneRepeater.DELAY, 3)));
        assertEquals(1, StateClicks.clicks(repeater.withProperty(BlockRedstoneRepeater.DELAY, 4), repeater));
        assertEquals(0, StateClicks.clicks(repeater, repeater));
        assertEquals(-1, StateClicks.clicks(repeater, repeater.withProperty(BlockRedstoneRepeater.FACING, EnumFacing.WEST)));
    }

    @Test public void modesAndOpenStatesTakeOneClick() {
        IBlockState comparator = Blocks.UNPOWERED_COMPARATOR.getDefaultState();
        assertEquals(1, StateClicks.clicks(comparator, comparator.withProperty(BlockRedstoneComparator.MODE, BlockRedstoneComparator.Mode.SUBTRACT)));
        IBlockState trapdoor = Blocks.TRAPDOOR.getDefaultState();
        assertEquals(1, StateClicks.clicks(trapdoor, trapdoor.withProperty(BlockTrapDoor.OPEN, true)));
        IBlockState iron = Blocks.IRON_TRAPDOOR.getDefaultState();
        assertEquals(-1, StateClicks.clicks(iron, iron.withProperty(BlockTrapDoor.OPEN, true)));
        IBlockState door = Blocks.OAK_DOOR.getDefaultState();
        assertEquals(1, StateClicks.clicks(door, door.withProperty(BlockDoor.OPEN, true)));
        assertEquals(-1, StateClicks.clicks(Blocks.STONE.getDefaultState(), Blocks.STONE.getStateFromMeta(1)));
    }

    @Test public void placementsAreJudgedWithoutStatesAClickOrTheNeighboursMake() {
        IBlockState repeater = Blocks.UNPOWERED_REPEATER.getDefaultState();
        assertTrue(StateClicks.placedMatches(repeater, repeater.withProperty(BlockRedstoneRepeater.DELAY, 4)));
        assertFalse(StateClicks.placedMatches(repeater, repeater.withProperty(BlockRedstoneRepeater.FACING, EnumFacing.WEST)));
        IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState();
        assertTrue(StateClicks.placedMatches(stairs, stairs.withProperty(BlockStairs.SHAPE, BlockStairs.EnumShape.INNER_LEFT)));
        assertFalse(StateClicks.placedMatches(stairs, stairs.withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP)));
        assertFalse(StateClicks.placedMatches(stairs, Blocks.STONE_STAIRS.getDefaultState()));
    }
}
