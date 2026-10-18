package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockObserver;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Observers wait for what they watch, their outputs wait for them, pistons are not set off (RedstoneOrder). */
public class RedstoneOrderTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static SchematicWorld schematic() {
        SchematicWorld schematic = new SchematicWorld(new Schematic(SchematicWorld.DEFAULT_ICON, 6, 4, 6));
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) schematic.getSchematic().setBlockState(x, 0, z, Blocks.STONE.getDefaultState());
        return schematic;
    }

    private static SimWorld world() {
        SimWorld world = new SimWorld();
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) world.setBlockState(new BlockPos(x, 0, z), Blocks.STONE.getDefaultState(), 2);
        return world;
    }

    private static void set(SchematicWorld schematic, BlockPos pos, IBlockState state) {
        schematic.getSchematic().setBlockState(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    private static IBlockState observer(EnumFacing facing) {
        return Blocks.OBSERVER.getDefaultState().withProperty(BlockObserver.FACING, facing);
    }

    private static BlockPos hold(RedstoneOrder order, SimWorld world, SchematicWorld schematic, BlockPos pos, long tick) {
        return order.hold(world, pos, schematic, pos, schematic.getBlockState(pos), tick);
    }

    @Test public void observerWaitsForTheBlockItWatches() {
        SchematicWorld schematic = schematic();
        BlockPos observer = new BlockPos(2, 1, 2), watched = observer.east();
        set(schematic, observer, observer(EnumFacing.EAST));
        set(schematic, watched, Blocks.PLANKS.getDefaultState());
        SimWorld world = world();
        RedstoneOrder order = new RedstoneOrder();
        assertEquals(watched, hold(order, world, schematic, observer, 0));
        world.setBlockState(watched, Blocks.PLANKS.getDefaultState(), 3);
        assertNull(hold(order, world, schematic, observer, 1));
    }

    @Test public void observerGoesFirstWhenTheWatchedBlockStandsOnIt() {
        SchematicWorld schematic = schematic();
        BlockPos observer = new BlockPos(2, 1, 2);
        set(schematic, observer, observer(EnumFacing.UP));
        set(schematic, observer.up(), Blocks.UNPOWERED_REPEATER.getDefaultState());
        assertNull(hold(new RedstoneOrder(), world(), schematic, observer, 0));
    }

    @Test public void observerGoesFirstForWallsAndAttachedButtons() {
        assertTrue(RedstoneOrder.needsObserver(Blocks.COBBLESTONE_WALL.getDefaultState(), EnumFacing.NORTH));
        assertTrue(RedstoneOrder.needsObserver(Blocks.STONE_BUTTON.getDefaultState().withProperty(net.minecraft.block.BlockButton.FACING, EnumFacing.NORTH), EnumFacing.NORTH));
        assertTrue(!RedstoneOrder.needsObserver(Blocks.STONE_BUTTON.getDefaultState().withProperty(net.minecraft.block.BlockButton.FACING, EnumFacing.SOUTH), EnumFacing.NORTH));
    }

    @Test public void pistonAtTheOutputWaitsForTheObserverAndWhatItWatches() {
        SchematicWorld schematic = schematic();
        BlockPos observer = new BlockPos(2, 1, 2), watched = observer.east(), piston = observer.west();
        set(schematic, observer, observer(EnumFacing.EAST));
        set(schematic, watched, Blocks.PLANKS.getDefaultState());
        set(schematic, piston, Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.NORTH));
        SimWorld world = world();
        RedstoneOrder order = new RedstoneOrder();
        assertEquals(observer, hold(order, world, schematic, piston, 0));
        world.setBlockState(observer, observer(EnumFacing.EAST), 3);
        assertEquals(watched, hold(order, world, schematic, piston, 1));
        world.setBlockState(watched, Blocks.PLANKS.getDefaultState(), 3);
        assertNull(hold(order, world, schematic, piston, 2));
    }

    @Test public void retractedPistonWaitsWhilePowered() {
        SchematicWorld schematic = schematic();
        BlockPos piston = new BlockPos(2, 1, 2);
        set(schematic, piston, Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.UP));
        SimWorld world = world();
        world.setBlockState(piston.east(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        RedstoneOrder order = new RedstoneOrder();
        assertEquals(piston.east(), hold(order, world, schematic, piston, 0));
        // patience: placed anyway in the end, unless the wait was left alone for a while (the player walked away)
        for (long tick = 50; tick <= RedstoneOrder.PATIENCE; tick += 50) assertEquals(piston.east(), hold(order, world, schematic, piston, tick));
        assertNull(hold(order, world, schematic, piston, RedstoneOrder.PATIENCE + 50));
        assertEquals(piston.east(), hold(order, world, schematic, piston, RedstoneOrder.PATIENCE + 50 + 2000));
    }

    @Test public void budPistonOfTheSchematicIsNotHeldBack() {
        SchematicWorld schematic = schematic();
        BlockPos piston = new BlockPos(2, 1, 2);
        set(schematic, piston, Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.UP));
        set(schematic, piston.east(), Blocks.REDSTONE_BLOCK.getDefaultState());
        SimWorld world = world();
        world.setBlockState(piston.east(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        assertNull(hold(new RedstoneOrder(), world, schematic, piston, 0));
    }

    @Test public void redstoneBlockWaitsForTheQuasiConnectedPiston() {
        SchematicWorld schematic = schematic();
        BlockPos piston = new BlockPos(2, 1, 2), source = piston.up().east();
        set(schematic, piston, Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.WEST));
        set(schematic, source, Blocks.REDSTONE_BLOCK.getDefaultState());
        set(schematic, piston.east(), Blocks.PLANKS.getDefaultState());
        SimWorld world = world();
        RedstoneOrder order = new RedstoneOrder();
        assertEquals(piston, hold(order, world, schematic, source, 0));
        world.setBlockState(piston, schematic.getBlockState(piston), 3);
        assertEquals(piston.east(), hold(order, world, schematic, source, 1));
        world.setBlockState(piston.east(), Blocks.PLANKS.getDefaultState(), 3);
        assertNull(hold(order, world, schematic, source, 2));
    }

    @Test public void observerInALoopOfWaitsGoesFirstWhenItsOutputIsHarmless() {
        // observer A watches observer B and B watches A: each waits for the other
        SchematicWorld schematic = schematic();
        BlockPos a = new BlockPos(2, 1, 2), b = a.east();
        set(schematic, a, observer(EnumFacing.EAST));
        set(schematic, b, observer(EnumFacing.WEST));
        SimWorld world = world();
        RedstoneOrder order = new RedstoneOrder();
        assertEquals(b, hold(order, world, schematic, a, 0));
        assertNull(hold(order, world, schematic, b, 0));
    }

    @Test public void simWorldCountsTheObserverPulseOnPlacement() {
        SimWorld world = world();
        world.setBlockState(new BlockPos(2, 1, 2), observer(EnumFacing.EAST), 3);
        for (int i = 0; i < 6; i++) world.step();
        assertEquals(1, world.observerPulses);
    }

    @Test public void pulseCellsAreTheObserversOutputSide() {
        SimWorld world = world();
        BlockPos observer = new BlockPos(2, 1, 2);
        java.util.List<BlockPos> cells = RedstoneOrder.pulseCells(world, observer, observer(EnumFacing.EAST));
        assertTrue(cells.contains(observer.west()));
        assertTrue(cells.contains(observer.west(2)));
        assertTrue(!cells.contains(observer));
    }
}
