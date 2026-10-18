package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockTripWire;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

import static org.junit.Assert.*;

public class PrinterStatesTest {
    private static SchematicWorld world;

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        world = new SchematicWorld(new Schematic(SchematicWorld.DEFAULT_ICON, 3, 3, 3));
    }

    private static boolean built(IBlockState expected, IBlockState real) {
        return PrinterStates.built(world, BlockPos.ORIGIN, expected, real);
    }

    @Test public void redstoneDrivenStatesCountAsBuilt() {
        IBlockState wire = Blocks.REDSTONE_WIRE.getDefaultState();
        assertTrue(built(wire.withProperty(BlockRedstoneWire.POWER, 15), wire));
        IBlockState rail = Blocks.GOLDEN_RAIL.getDefaultState();
        assertTrue(built(rail, rail.withProperty(BlockRailPowered.POWERED, true)));
        IBlockState piston = Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.UP);
        assertTrue(built(piston.withProperty(BlockPistonBase.EXTENDED, true), piston));
        IBlockState dropper = Blocks.DROPPER.getDefaultState().withProperty(BlockDispenser.FACING, EnumFacing.DOWN);
        assertTrue(built(dropper, dropper.withProperty(BlockDispenser.TRIGGERED, true)));
        IBlockState wireHook = Blocks.TRIPWIRE_HOOK.getDefaultState();
        assertTrue(built(wireHook.withProperty(BlockTripWireHook.ATTACHED, true), wireHook.withProperty(BlockTripWireHook.POWERED, true)));
        IBlockState tripwire = Blocks.TRIPWIRE.getDefaultState();
        assertTrue(built(tripwire.withProperty(BlockTripWire.ATTACHED, true), tripwire.withProperty(BlockTripWire.POWERED, true)));
    }

    @Test public void litTwinsCountAsBuilt() {
        assertTrue(built(Blocks.REDSTONE_TORCH.getDefaultState(), Blocks.UNLIT_REDSTONE_TORCH.getDefaultState()));
        assertTrue(built(Blocks.REDSTONE_LAMP.getDefaultState(), Blocks.LIT_REDSTONE_LAMP.getDefaultState()));
        assertTrue(built(Blocks.UNPOWERED_REPEATER.getDefaultState(), Blocks.POWERED_REPEATER.getDefaultState()));
    }

    @Test public void orientationStillMatters() {
        IBlockState dropper = Blocks.DROPPER.getDefaultState();
        assertFalse(built(dropper.withProperty(BlockDispenser.FACING, EnumFacing.UP), dropper.withProperty(BlockDispenser.FACING, EnumFacing.DOWN)));
        assertFalse(built(Blocks.REDSTONE_TORCH.getDefaultState(), Blocks.TORCH.getDefaultState()));
    }

    @Test public void pistonPartsAreLeftAlone() {
        assertTrue(PrinterStates.pistonBusy(Blocks.PISTON_HEAD.getDefaultState(), Blocks.AIR.getDefaultState()));
        assertTrue(PrinterStates.pistonBusy(Blocks.STONE.getDefaultState(), Blocks.PISTON_EXTENSION.getDefaultState()));
        assertFalse(PrinterStates.pistonBusy(Blocks.STONE.getDefaultState(), Blocks.DIRT.getDefaultState()));
    }
}
