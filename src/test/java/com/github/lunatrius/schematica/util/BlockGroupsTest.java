package com.github.lunatrius.schematica.util;

import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockPressurePlate;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.EnumFacing;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.handler.VisualSettings;
import static com.github.lunatrius.schematica.util.BlockGroups.Result.DIFFERENT_BLOCK;
import static com.github.lunatrius.schematica.util.BlockGroups.Result.SAME;
import static com.github.lunatrius.schematica.util.BlockGroups.Result.WRONG_BLOCK;
import static com.github.lunatrius.schematica.util.BlockGroups.Result.WRONG_STATE;
import static org.junit.Assert.assertEquals;

public class BlockGroupsTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static IBlockState wool(EnumDyeColor color) { return Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, color); }

    private static IBlockState log(BlockPlanks.EnumType type, BlockLog.EnumAxis axis) {
        return Blocks.LOG.getDefaultState().withProperty(BlockOldLog.VARIANT, type).withProperty(BlockLog.LOG_AXIS, axis);
    }

    private static IBlockState stairs(net.minecraft.block.Block block, EnumFacing facing) {
        return block.getDefaultState().withProperty(BlockStairs.FACING, facing);
    }

    @Test public void colorsAndWoodTypesAreDifferentBlocksOnlyWithTheSameState() {
        assertEquals(DIFFERENT_BLOCK, BlockGroups.compare(wool(EnumDyeColor.WHITE), wool(EnumDyeColor.ORANGE), true));
        assertEquals(WRONG_STATE, BlockGroups.compare(wool(EnumDyeColor.WHITE), wool(EnumDyeColor.ORANGE), false));
        assertEquals(DIFFERENT_BLOCK, BlockGroups.compare(log(BlockPlanks.EnumType.OAK, BlockLog.EnumAxis.Y), log(BlockPlanks.EnumType.SPRUCE, BlockLog.EnumAxis.Y), true));
        assertEquals(WRONG_STATE, BlockGroups.compare(log(BlockPlanks.EnumType.OAK, BlockLog.EnumAxis.Y), log(BlockPlanks.EnumType.OAK, BlockLog.EnumAxis.X), true));
        assertEquals(WRONG_STATE, BlockGroups.compare(log(BlockPlanks.EnumType.OAK, BlockLog.EnumAxis.Y), log(BlockPlanks.EnumType.SPRUCE, BlockLog.EnumAxis.X), true));
        assertEquals(SAME, BlockGroups.compare(log(BlockPlanks.EnumType.SPRUCE, BlockLog.EnumAxis.X), log(BlockPlanks.EnumType.SPRUCE, BlockLog.EnumAxis.X), true));
    }

    @Test public void otherBlocksOfOneGroupMustKeepTheirState() {
        assertEquals(DIFFERENT_BLOCK, BlockGroups.compare(stairs(Blocks.OAK_STAIRS, EnumFacing.NORTH), stairs(Blocks.STONE_STAIRS, EnumFacing.NORTH), true));
        assertEquals(WRONG_STATE, BlockGroups.compare(stairs(Blocks.OAK_STAIRS, EnumFacing.NORTH), stairs(Blocks.STONE_STAIRS, EnumFacing.SOUTH), true));
        assertEquals(WRONG_BLOCK, BlockGroups.compare(stairs(Blocks.OAK_STAIRS, EnumFacing.NORTH), stairs(Blocks.STONE_STAIRS, EnumFacing.NORTH), false));
        assertEquals(WRONG_BLOCK, BlockGroups.compare(stairs(Blocks.OAK_STAIRS, EnumFacing.EAST), wool(EnumDyeColor.WHITE), true));
        assertEquals(WRONG_BLOCK, BlockGroups.compare(wool(EnumDyeColor.WHITE), Blocks.STAINED_HARDENED_CLAY.getDefaultState(), true));
        assertEquals(WRONG_STATE, BlockGroups.compare(Blocks.STONE.getStateFromMeta(1), Blocks.STONE.getStateFromMeta(2), true));
    }

    @Test public void neighbourMadePropertiesAreNotPartOfTheState() {
        IBlockState straight = stairs(Blocks.OAK_STAIRS, EnumFacing.NORTH);
        IBlockState corner = straight.withProperty(BlockStairs.SHAPE, BlockStairs.EnumShape.OUTER_LEFT);
        assertEquals(SAME, BlockGroups.compare(corner, straight, false));
    }

    @Test public void ignoreCropAgeOnlySkipsTheGrowthStage() {
        IBlockState ripe = Blocks.WHEAT.getDefaultState().withProperty(BlockCrops.AGE, 7), young = ripe.withProperty(BlockCrops.AGE, 2);
        boolean previous = VisualSettings.ignoreCropAge, survival = VisualSettings.ignoreSurvivalStates;
        try {
            VisualSettings.ignoreSurvivalStates = false;
            VisualSettings.ignoreCropAge = false;
            assertEquals(WRONG_STATE, BlockGroups.compare(ripe, young, false));
            VisualSettings.ignoreCropAge = true;
            assertEquals(SAME, BlockGroups.compare(ripe, young, false));
            IBlockState cocoa = Blocks.COCOA.getDefaultState().withProperty(net.minecraft.block.BlockCocoa.AGE, 2);
            assertEquals(SAME, BlockGroups.compare(cocoa, cocoa.withProperty(net.minecraft.block.BlockCocoa.AGE, 0), true));
            assertEquals(WRONG_STATE, BlockGroups.compare(cocoa, cocoa.withProperty(net.minecraft.block.BlockHorizontal.FACING, EnumFacing.EAST), true));
            assertEquals(WRONG_STATE, BlockGroups.compare(Blocks.STONE.getStateFromMeta(1), Blocks.STONE.getStateFromMeta(2), true));
        } finally {
            VisualSettings.ignoreCropAge = previous;
            VisualSettings.ignoreSurvivalStates = survival;
        }
    }

    @Test public void survivalStatesAndLitTwinsAreTheSameBlock() {
        IBlockState leaves = Blocks.LEAVES.getDefaultState().withProperty(BlockOldLeaf.VARIANT, BlockPlanks.EnumType.SPRUCE);
        IBlockState decaying = leaves.withProperty(BlockLeaves.CHECK_DECAY, true).withProperty(BlockLeaves.DECAYABLE, false);
        IBlockState birch = leaves.withProperty(BlockOldLeaf.VARIANT, BlockPlanks.EnumType.BIRCH).withProperty(BlockLeaves.CHECK_DECAY, true);
        IBlockState plate = Blocks.STONE_PRESSURE_PLATE.getDefaultState();
        boolean survival = VisualSettings.ignoreSurvivalStates;
        try {
            VisualSettings.ignoreSurvivalStates = true;
            assertEquals(SAME, BlockGroups.compare(leaves, decaying, true));
            assertEquals(DIFFERENT_BLOCK, BlockGroups.compare(leaves, birch, true));
            assertEquals(WRONG_STATE, BlockGroups.compare(leaves, birch, false));
            assertEquals(SAME, BlockGroups.compare(plate, plate.withProperty(BlockPressurePlate.POWERED, true), false));
            assertEquals(SAME, BlockGroups.compare(Blocks.REDSTONE_ORE.getDefaultState(), Blocks.LIT_REDSTONE_ORE.getDefaultState(), false));
            assertEquals(WRONG_BLOCK, BlockGroups.compare(Blocks.REDSTONE_ORE.getDefaultState(), Blocks.STONE.getDefaultState(), false));
            VisualSettings.ignoreSurvivalStates = false;
            assertEquals(WRONG_STATE, BlockGroups.compare(leaves, decaying, true));
            assertEquals(WRONG_BLOCK, BlockGroups.compare(Blocks.REDSTONE_ORE.getDefaultState(), Blocks.LIT_REDSTONE_ORE.getDefaultState(), false));
        } finally {
            VisualSettings.ignoreSurvivalStates = survival;
        }
    }
}
