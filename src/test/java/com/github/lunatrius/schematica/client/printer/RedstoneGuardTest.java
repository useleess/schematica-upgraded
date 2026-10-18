package com.github.lunatrius.schematica.client.printer;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Wire and power sources wait until the wire they power has its finished shape (no dot powering the wrong blocks). */
public class RedstoneGuardTest {
    private static final BlockPos SOURCE = new BlockPos(1, 1, 1), WIRE = new BlockPos(2, 1, 1), TURN = new BlockPos(2, 1, 2);

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    /** The schematic: a source west of a wire that turns south (a corner, which points into no block). */
    private static SchematicWorld schematic(IBlockState source) {
        SchematicWorld schematic = new SchematicWorld(new Schematic(SchematicWorld.DEFAULT_ICON, 4, 2, 4));
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) schematic.getSchematic().setBlockState(x, 0, z, Blocks.STONE.getDefaultState());
        schematic.getSchematic().setBlockState(SOURCE.getX(), SOURCE.getY(), SOURCE.getZ(), source);
        schematic.getSchematic().setBlockState(WIRE.getX(), WIRE.getY(), WIRE.getZ(), Blocks.REDSTONE_WIRE.getDefaultState());
        schematic.getSchematic().setBlockState(TURN.getX(), TURN.getY(), TURN.getZ(), Blocks.REDSTONE_WIRE.getDefaultState());
        return schematic;
    }

    private static SimWorld world() {
        SimWorld world = new SimWorld();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) world.setBlockState(new BlockPos(x, 0, z), Blocks.STONE.getDefaultState(), 2);
        return world;
    }

    @Test public void pointedFollowsGetWeakPower() {
        IBlockState dot = Blocks.REDSTONE_WIRE.getDefaultState();
        assertEquals(4, PrinterStates.pointed(dot).size());
        SimWorld world = world();
        world.setBlockState(SOURCE, Blocks.REDSTONE_BLOCK.getDefaultState(), 2);
        // connected to the west only: a line that points east
        assertEquals(java.util.EnumSet.of(EnumFacing.EAST), PrinterStates.pointed(dot.getActualState(world, WIRE)));
    }

    @Test public void poweredWireWaitsForTheWireItTurnsTo() {
        SchematicWorld schematic = schematic(Blocks.REDSTONE_BLOCK.getDefaultState());
        SimWorld world = world();
        world.setBlockState(SOURCE, Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        IBlockState wire = schematic.getBlockState(WIRE);
        assertTrue(PrinterStates.wireWaits(world, WIRE, schematic, WIRE, wire));
        world.setBlockState(TURN, Blocks.REDSTONE_WIRE.getDefaultState(), 3);
        assertFalse(PrinterStates.wireWaits(world, WIRE, schematic, WIRE, wire));
    }

    @Test public void unpoweredWireIsPlacedRightAway() {
        SchematicWorld schematic = schematic(Blocks.REDSTONE_BLOCK.getDefaultState());
        assertFalse(PrinterStates.wireWaits(world(), WIRE, schematic, WIRE, schematic.getBlockState(WIRE)));
    }

    @Test public void sourceWaitsForUnfinishedWire() {
        SchematicWorld schematic = schematic(Blocks.REDSTONE_BLOCK.getDefaultState());
        SimWorld world = world();
        world.setBlockState(WIRE, Blocks.REDSTONE_WIRE.getDefaultState(), 3);
        IBlockState source = schematic.getBlockState(SOURCE);
        assertTrue(PrinterStates.sourceWaits(world, SOURCE, schematic, SOURCE, source));
        world.setBlockState(TURN, Blocks.REDSTONE_WIRE.getDefaultState(), 3);
        assertFalse(PrinterStates.sourceWaits(world, SOURCE, schematic, SOURCE, source));
    }

    @Test public void sourcesThatAreOffWhenPlacedDoNotWait() {
        SchematicWorld schematic = schematic(Blocks.DETECTOR_RAIL.getDefaultState());
        SimWorld world = world();
        world.setBlockState(WIRE, Blocks.REDSTONE_WIRE.getDefaultState(), 3);
        assertFalse(PrinterStates.sourceWaits(world, SOURCE, schematic, SOURCE, schematic.getBlockState(SOURCE)));
    }
}
