package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.client.printer.MultiBlockPlacement.Kind;
import static org.junit.Assert.*;

public class MultiBlockPlacementTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void facingFollowsTheVanillaItems() {
        // yaw 0 looks south, 90 west, -90 east
        assertEquals(EnumFacing.SOUTH, MultiBlockPlacement.facing(0));
        assertEquals(EnumFacing.WEST, MultiBlockPlacement.facing(90));
        assertEquals(EnumFacing.EAST, MultiBlockPlacement.facing(-90));
        assertEquals(EnumFacing.NORTH, MultiBlockPlacement.facing(180));
    }

    @Test public void everyFacingHasAHorizontalLook() {
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            PrinterLook look = MultiBlockPlacement.look(37, facing);
            assertNotNull(look);
            assertEquals(0, look.pitch, 0);
            assertEquals(facing, MultiBlockPlacement.facing(look.yaw));
        }
    }

    @Test public void secondBlocksAndComparedProperties() {
        IBlockState door = Blocks.OAK_DOOR.getDefaultState();
        IBlockState upper = door.withProperty(BlockDoor.HALF, BlockDoor.EnumDoorHalf.UPPER);
        assertEquals(Kind.DOOR, MultiBlockPlacement.kind(door.getBlock()));
        assertFalse(MultiBlockPlacement.secondary(door));
        assertTrue(MultiBlockPlacement.secondary(upper));
        assertEquals(EnumFacing.UP, MultiBlockPlacement.secondDirection(Kind.DOOR, door));
        IBlockState foot = Blocks.BED.getDefaultState().withProperty(BlockBed.FACING, EnumFacing.WEST);
        assertFalse(MultiBlockPlacement.secondary(foot));
        assertTrue(MultiBlockPlacement.secondary(foot.withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD)));
        assertEquals(EnumFacing.WEST, MultiBlockPlacement.secondDirection(Kind.BED, foot));
        assertEquals(EnumFacing.WEST, MultiBlockPlacement.wantedFacing(Kind.BED, foot));
        IBlockState plant = Blocks.DOUBLE_PLANT.getDefaultState();
        assertTrue(MultiBlockPlacement.secondary(plant.withProperty(BlockDoublePlant.HALF, BlockDoublePlant.EnumBlockHalf.UPPER)));
        assertNull(MultiBlockPlacement.wantedFacing(Kind.DOUBLE_PLANT, plant));

        IBlockState open = door.withProperty(BlockDoor.OPEN, true);
        assertTrue(MultiBlockPlacement.sameState(Kind.DOOR, open, door, false));
        assertFalse(MultiBlockPlacement.sameState(Kind.DOOR, open, door, true));
        assertFalse(MultiBlockPlacement.sameState(Kind.DOOR, door.withProperty(BlockDoor.FACING, EnumFacing.WEST), door, false));
    }
}
