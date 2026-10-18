package com.github.lunatrius.schematica.client.gui.material;

import java.util.Arrays;

import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStoneSlab;
import net.minecraft.block.BlockWoodSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.MultiRegionSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class SchematicBlockReplaceTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static MaterialItemKey key(net.minecraft.block.Block block, int meta) { return new MaterialItemKey(new ItemStack(block, 1, meta)); }

    @Test public void keepsSharedPropertiesButNotTheVariant() {
        IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.NORTH)
            .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP);
        IBlockState spruce = SchematicBlockReplace.convert(stairs, Blocks.SPRUCE_STAIRS.getDefaultState());
        assertEquals(Blocks.SPRUCE_STAIRS, spruce.getBlock());
        assertEquals(EnumFacing.NORTH, spruce.getValue(BlockStairs.FACING));
        assertEquals(BlockStairs.EnumHalf.TOP, spruce.getValue(BlockStairs.HALF));

        IBlockState white = Blocks.WOOL.getDefaultState();
        IBlockState red = Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.RED);
        assertEquals(red, SchematicBlockReplace.convert(white, red));

        IBlockState topStone = Blocks.STONE_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP);
        IBlockState birch = Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockWoodSlab.VARIANT, BlockPlanks.EnumType.BIRCH);
        IBlockState slab = SchematicBlockReplace.convert(topStone, birch);
        assertEquals(BlockSlab.EnumBlockHalf.TOP, slab.getValue(BlockSlab.HALF));
        assertEquals(BlockPlanks.EnumType.BIRCH, slab.getValue(BlockWoodSlab.VARIANT));
    }

    @Test public void doubleSlabsStayDouble() {
        IBlockState full = SchematicBlockReplace.convert(Blocks.DOUBLE_STONE_SLAB.getDefaultState(),
            Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockWoodSlab.VARIANT, BlockPlanks.EnumType.SPRUCE));
        assertEquals(Blocks.DOUBLE_WOODEN_SLAB, full.getBlock());
        assertEquals(BlockPlanks.EnumType.SPRUCE, full.getValue(BlockWoodSlab.VARIANT));
    }

    @Test public void replacesTheRowsBlocksAndBothDoorHalves() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 5, 2, 1);
        IBlockState oak = Blocks.PLANKS.getDefaultState();
        schematic.setBlockState(0, 0, 0, oak);
        schematic.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(2, 0, 0, oak);
        schematic.setBlockState(3, 0, 0, Blocks.PLANKS.getDefaultState().withProperty(BlockPlanks.VARIANT, BlockPlanks.EnumType.BIRCH));
        IBlockState lower = Blocks.OAK_DOOR.getDefaultState().withProperty(BlockDoor.FACING, EnumFacing.EAST);
        schematic.setBlockState(4, 0, 0, lower);
        schematic.setBlockState(4, 1, 0, Blocks.OAK_DOOR.getDefaultState().withProperty(BlockDoor.HALF, BlockDoor.EnumDoorHalf.UPPER));
        SchematicWorld world = new SchematicWorld(schematic);

        assertEquals(2, SchematicBlockReplace.replace(schematic, world, null, key(Blocks.PLANKS, 0), Blocks.STONEBRICK.getDefaultState()));
        assertEquals(Blocks.STONEBRICK, schematic.getBlockState(0, 0, 0).getBlock());
        assertEquals(Blocks.STONE, schematic.getBlockState(1, 0, 0).getBlock());
        assertEquals(Blocks.STONEBRICK, schematic.getBlockState(2, 0, 0).getBlock());
        assertEquals(BlockPlanks.EnumType.BIRCH, schematic.getBlockState(3, 0, 0).getValue(BlockPlanks.VARIANT));

        MaterialItemKey door = new MaterialItemKey(new ItemStack(net.minecraft.init.Items.OAK_DOOR));
        assertEquals(2, SchematicBlockReplace.replace(schematic, world, null, door, Blocks.SPRUCE_DOOR.getDefaultState()));
        assertEquals(Blocks.SPRUCE_DOOR, schematic.getBlockState(4, 0, 0).getBlock());
        assertEquals(EnumFacing.EAST, schematic.getBlockState(4, 0, 0).getValue(BlockDoor.FACING));
        assertEquals(Blocks.SPRUCE_DOOR, schematic.getBlockState(4, 1, 0).getBlock());
        assertEquals(BlockDoor.EnumDoorHalf.UPPER, schematic.getBlockState(4, 1, 0).getValue(BlockDoor.HALF));

        assertEquals(1, SchematicBlockReplace.replace(schematic, world, null, key(Blocks.STONE, 0), Blocks.AIR.getDefaultState()));
        assertEquals(Blocks.AIR, schematic.getBlockState(1, 0, 0).getBlock());
    }

    @Test public void choicesListEachBuildingItemOnce() {
        java.util.List<BlockChoices.Choice> choices = BlockChoices.all(null, "Air");
        assertEquals(Blocks.AIR, choices.get(0).state.getBlock());
        java.util.Set<net.minecraft.block.Block> blocks = new java.util.HashSet<>();
        int planks = 0;
        for (BlockChoices.Choice choice : choices) {
            blocks.add(choice.state.getBlock());
            if (choice.state.getBlock() == Blocks.PLANKS) planks++;
        }
        assertEquals(6, planks);
        org.junit.Assert.assertTrue(blocks.contains(Blocks.STONE_SLAB));
        org.junit.Assert.assertTrue(blocks.contains(Blocks.FURNACE));
        org.junit.Assert.assertTrue(!blocks.contains(Blocks.DOUBLE_STONE_SLAB));
        org.junit.Assert.assertTrue(!blocks.contains(Blocks.LIT_FURNACE));
    }

    @Test public void regionPayloadsChangeWithTheCombinedSchematic() {
        MultiRegionSchematic multi = new MultiRegionSchematic(SchematicWorld.DEFAULT_ICON, 4, 1, 1);
        Schematic part = new Schematic(SchematicWorld.DEFAULT_ICON, 2, 1, 1);
        part.setBlockState(0, 0, 0, Blocks.STONE_SLAB.getDefaultState().withProperty(BlockStoneSlab.VARIANT, BlockStoneSlab.EnumType.STONE));
        part.setBlockState(1, 0, 0, Blocks.DOUBLE_STONE_SLAB.getDefaultState());
        SchematicRegion box = new SchematicRegion("a", 2, 0, 0, 3, 0, 0);
        multi.setRegions(Arrays.asList(box));
        multi.addRegion(box, part);

        long changed = SchematicBlockReplace.replace(multi, new SchematicWorld(multi), null, key(Blocks.STONE_SLAB, 0),
            Blocks.WOODEN_SLAB.getDefaultState());
        assertEquals(4, changed);
        assertEquals(Blocks.WOODEN_SLAB, part.getBlockState(0, 0, 0).getBlock());
        assertEquals(Blocks.DOUBLE_WOODEN_SLAB, part.getBlockState(1, 0, 0).getBlock());
        assertEquals(Blocks.WOODEN_SLAB, multi.getBlockState(2, 0, 0).getBlock());
        assertEquals(Blocks.DOUBLE_WOODEN_SLAB, multi.getBlockState(3, 0, 0).getBlock());
    }

    @Test public void aMissingBlocksRowChangesOnlyItsBlocksAndTheyStayItsBlocks() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 3, 1, 1);
        IBlockState stone = Blocks.STONE.getDefaultState();
        schematic.setBlockState(0, 0, 0, stone);
        schematic.setBlockState(1, 0, 0, stone, "minecraft:deepslate");
        schematic.setBlockState(2, 0, 0, stone, "minecraft:tuff");
        SchematicWorld world = new SchematicWorld(schematic);
        MaterialItemKey row = key(Blocks.STONE, 0).withOrigin("minecraft:tuff");

        assertEquals(1, SchematicBlockReplace.replace(schematic, world, null, row, Blocks.COBBLESTONE.getDefaultState()));
        assertEquals(stone, schematic.getBlockState(0, 0, 0));
        assertEquals(stone, schematic.getBlockState(1, 0, 0));
        assertEquals(Blocks.COBBLESTONE, schematic.getBlockState(2, 0, 0).getBlock());
        assertEquals("minecraft:tuff", schematic.getOrigin(2, 0, 0));
        assertEquals("minecraft:deepslate", schematic.getOrigin(1, 0, 0));

        assertEquals(1, SchematicBlockReplace.replace(schematic, world, null, key(Blocks.STONE, 0), Blocks.DIRT.getDefaultState()));
        assertEquals(Blocks.DIRT, schematic.getBlockState(0, 0, 0).getBlock());
        assertEquals(null, schematic.getOrigin(0, 0, 0));
        assertEquals(stone, schematic.getBlockState(1, 0, 0));
    }

    @Test public void aMergedRowChangesItsBlocksFromEverySource() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 3, 1, 1);
        IBlockState stone = Blocks.STONE.getDefaultState();
        schematic.setBlockState(0, 0, 0, stone);
        schematic.setBlockState(1, 0, 0, stone, "minecraft:deepslate");
        schematic.setBlockState(2, 0, 0, stone, "minecraft:tuff");
        SchematicWorld world = new SchematicWorld(schematic);
        assertEquals(3, SchematicBlockReplace.replace(schematic, world, null, key(Blocks.STONE, 0), Blocks.COBBLESTONE.getDefaultState(), true));
        for (int x = 0; x < 3; x++) assertEquals(Blocks.COBBLESTONE, schematic.getBlockState(x, 0, 0).getBlock());
        assertEquals("minecraft:deepslate", schematic.getOrigin(1, 0, 0));
    }
}
