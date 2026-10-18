package com.github.lunatrius.schematica.handler;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockQuartz;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccuratePlacementTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void onlyWhitelistedOrientationPropertiesMayChange() {
        IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState();
        IBlockState turned = stairs.withProperty(BlockStairs.FACING, EnumFacing.WEST).withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP);
        assertEquals(turned, AccuratePlacement.merge(stairs, turned));
        assertNull(AccuratePlacement.merge(stairs, stairs));
        assertTrue(AccuratePlacement.supports(Blocks.OAK_STAIRS));
        assertFalse(AccuratePlacement.supports(Blocks.STONE));
    }

    @Test public void poweredStatesStayAsPlaced() {
        IBlockState lever = Blocks.LEVER.getDefaultState();
        IBlockState wanted = lever.withProperty(BlockLever.FACING, BlockLever.EnumOrientation.EAST).withProperty(BlockLever.POWERED, true);
        IBlockState merged = AccuratePlacement.merge(lever, wanted);
        assertEquals(BlockLever.EnumOrientation.EAST, merged.getValue(BlockLever.FACING));
        assertFalse(merged.getValue(BlockLever.POWERED));
        IBlockState piston = Blocks.PISTON.getDefaultState();
        IBlockState extended = piston.withProperty(BlockPistonBase.FACING, EnumFacing.UP).withProperty(BlockPistonBase.EXTENDED, true);
        assertFalse(AccuratePlacement.merge(piston, extended).getValue(BlockPistonBase.EXTENDED));
    }

    @Test public void doorHalvesTakeFacingOpenAndHinge() {
        IBlockState lower = Blocks.OAK_DOOR.getDefaultState();
        IBlockState wanted = lower.withProperty(BlockDoor.FACING, EnumFacing.WEST).withProperty(BlockDoor.OPEN, true)
            .withProperty(BlockDoor.HINGE, BlockDoor.EnumHingePosition.RIGHT).withProperty(BlockDoor.POWERED, true);
        IBlockState merged = AccuratePlacement.merge(lower, wanted);
        assertEquals(EnumFacing.WEST, merged.getValue(BlockDoor.FACING));
        assertTrue(merged.getValue(BlockDoor.OPEN));
        assertEquals(BlockDoor.EnumHingePosition.RIGHT, merged.getValue(BlockDoor.HINGE));
        assertFalse(merged.getValue(BlockDoor.POWERED));
    }

    @Test public void quartzPillarsTurnButDoNotBecomeOtherQuartz() {
        IBlockState pillar = Blocks.QUARTZ_BLOCK.getDefaultState().withProperty(BlockQuartz.VARIANT, BlockQuartz.EnumType.LINES_Y);
        IBlockState sideways = pillar.withProperty(BlockQuartz.VARIANT, BlockQuartz.EnumType.LINES_X);
        assertEquals(sideways, AccuratePlacement.merge(pillar, sideways));
        assertNull(AccuratePlacement.merge(pillar, pillar.withProperty(BlockQuartz.VARIANT, BlockQuartz.EnumType.CHISELED)));
        IBlockState plain = Blocks.QUARTZ_BLOCK.getDefaultState();
        assertNull(AccuratePlacement.merge(plain, sideways));
    }

    @Test public void positionKeysDoNotCollideAcrossAxes() {
        assertNotEquals(AccuratePlacement.key(1, 0, 0), AccuratePlacement.key(0, 1, 0));
        assertNotEquals(AccuratePlacement.key(0, 0, 1), AccuratePlacement.key(0, 1, 0));
        assertNotEquals(AccuratePlacement.key(-1, 64, 0), AccuratePlacement.key(0, 64, -1));
    }
}
