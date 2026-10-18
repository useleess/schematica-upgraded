package com.github.lunatrius.schematica.world.schematic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.world.storage.MultiRegionSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;
import com.github.lunatrius.schematica.world.storage.SchematicCopies;

public class SchematicContentsTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    private static Schematic withChest() {
        Schematic schematic = new Schematic(new ItemStack(Blocks.STONE), 4, 3, 5);
        schematic.setBlockState(1, 1, 2, Blocks.CHEST.getDefaultState());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "minecraft:chest");
        tag.setInteger("x", 1);
        tag.setInteger("y", 1);
        tag.setInteger("z", 2);
        NBTTagList items = new NBTTagList();
        NBTTagCompound stack = new NBTTagCompound();
        new ItemStack(Items.DIAMOND, 7).writeToNBT(stack);
        stack.setByte("Slot", (byte) 3);
        items.appendTag(stack);
        tag.setTag("Items", items);
        TileEntity tile = NBTHelper.readTileEntityFromCompound(tag);
        assertNotNull(tile);
        schematic.setTileEntity(tile.getPos(), tile);
        return schematic;
    }

    private static void assertChest(ISchematic schematic) {
        TileEntity tile = schematic.getTileEntity(new BlockPos(1, 1, 2));
        assertTrue(String.valueOf(tile), tile instanceof TileEntityChest);
        TileEntityChest chest = (TileEntityChest) tile;
        assertEquals(Items.DIAMOND, chest.getStackInSlot(3).getItem());
        assertEquals(7, chest.getStackInSlot(3).getCount());
    }

    @Test
    public void tileEntitiesSurviveAlpha() {
        NBTTagCompound tag = new NBTTagCompound();
        assertTrue(new SchematicAlpha().writeToNBT(tag, withChest(), null, true, false, false));
        assertChest(new SchematicAlpha().readFromNBT(tag));
    }

    @Test
    public void tileEntitiesSurviveLitematic() throws Exception {
        File file = File.createTempFile("schematica-test", ".litematic");
        try {
            assertNotNull(SchematicFormat.saveToFile(file, withChest(), null, true, false, "t"));
            assertChest(SchematicFormat.readFromFile(file));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void tileEntitiesSurviveStructureTemplate() throws Exception {
        File file = File.createTempFile("schematica-test", ".nbt");
        try {
            assertNotNull(SchematicFormat.saveToFile(file, withChest(), null, true, false, "t"));
            assertChest(SchematicFormat.readFromFile(file));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void legacy1710TileEntitiesAreConverted() {
        // A 1.7.10 Alpha file: chest ("Chest", numeric item id 264 = diamond) and a sign with plain text lines
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(Names.NBT.MATERIALS, Names.NBT.FORMAT_ALPHA);
        tag.setShort(Names.NBT.WIDTH, (short) 2);
        tag.setShort(Names.NBT.HEIGHT, (short) 1);
        tag.setShort(Names.NBT.LENGTH, (short) 1);
        tag.setByteArray(Names.NBT.BLOCKS, new byte[] {54, (byte) 63});
        tag.setByteArray(Names.NBT.DATA, new byte[] {2, 4});
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound chest = new NBTTagCompound();
        chest.setString("id", "Chest");
        chest.setInteger("x", 0);
        chest.setInteger("y", 0);
        chest.setInteger("z", 0);
        NBTTagList items = new NBTTagList();
        NBTTagCompound stack = new NBTTagCompound();
        stack.setByte("Slot", (byte) 0);
        stack.setShort("id", (short) 264);
        stack.setByte("Count", (byte) 5);
        stack.setShort("Damage", (short) 0);
        items.appendTag(stack);
        chest.setTag("Items", items);
        tiles.appendTag(chest);
        NBTTagCompound sign = new NBTTagCompound();
        sign.setString("id", "Sign");
        sign.setInteger("x", 1);
        sign.setInteger("y", 0);
        sign.setInteger("z", 0);
        sign.setString("Text1", "hello");
        sign.setString("Text2", "");
        sign.setString("Text3", "");
        sign.setString("Text4", "");
        tiles.appendTag(sign);
        tag.setTag(Names.NBT.TILE_ENTITIES, tiles);

        ISchematic read = new SchematicAlpha().readFromNBT(tag);
        assertSame(Blocks.CHEST, read.getBlockState(0, 0, 0).getBlock());
        assertSame(Blocks.STANDING_SIGN, read.getBlockState(1, 0, 0).getBlock());
        TileEntity chestTile = read.getTileEntity(new BlockPos(0, 0, 0));
        assertTrue(String.valueOf(chestTile), chestTile instanceof TileEntityChest);
        assertEquals(Items.DIAMOND, ((TileEntityChest) chestTile).getStackInSlot(0).getItem());
        assertEquals(5, ((TileEntityChest) chestTile).getStackInSlot(0).getCount());
        TileEntity signTile = read.getTileEntity(new BlockPos(1, 0, 0));
        assertTrue(String.valueOf(signTile), signTile instanceof TileEntitySign);
        assertEquals("hello", ((TileEntitySign) signTile).signText[0].getUnformattedText());
    }

    @Test
    public void rotationAndMirroring() {
        IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST);
        // Y turns clockwise seen from above: east becomes south
        assertEquals(EnumFacing.SOUTH, BlockStateTransform.transform(stairs, 'Y').getValue(BlockStairs.FACING));
        // x mirrors east and west
        assertEquals(EnumFacing.WEST, BlockStateTransform.transform(stairs, 'x').getValue(BlockStairs.FACING));
        assertEquals(EnumFacing.EAST, BlockStateTransform.transform(stairs, 'z').getValue(BlockStairs.FACING));
        // vertical mirror flips the half
        assertEquals(BlockStairs.EnumHalf.TOP, BlockStateTransform.transform(stairs, 'y').getValue(BlockStairs.HALF));
        // a log on the Y axis lies on Z after a turn about X
        IBlockState log = Blocks.LOG.getDefaultState().withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.Y);
        assertEquals(BlockLog.EnumAxis.Z, BlockStateTransform.transform(log, 'X').getValue(BlockLog.LOG_AXIS));
        assertEquals(BlockLog.EnumAxis.X, BlockStateTransform.transform(log, 'Z').getValue(BlockLog.LOG_AXIS));
        // four turns are the identity
        IBlockState state = stairs;
        for (int i = 0; i < 4; i++) state = BlockStateTransform.transform(state, 'Y');
        assertEquals(stairs, state);
    }

    @Test
    public void copiesAreIndependent() {
        Schematic original = withChest();
        Schematic copy = SchematicCopies.copy(original);
        assertChest(copy);
        copy.setBlockState(0, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        assertSame(Blocks.AIR, original.getBlockState(0, 0, 0).getBlock());
        assertTrue(copy.getTileEntity(new BlockPos(1, 1, 2)) != original.getTileEntity(new BlockPos(1, 1, 2)));
    }

    @Test
    public void extractMovesTileEntitiesIntoTheBox() {
        Schematic original = withChest();
        Schematic part = SchematicCopies.extract(original, new SchematicRegion("box", 1, 1, 2, 2, 2, 3));
        assertEquals(2, part.getWidth());
        assertSame(Blocks.CHEST, part.getBlockState(0, 0, 0).getBlock());
        assertTrue(part.getTileEntity(new BlockPos(0, 0, 0)) instanceof TileEntityChest);
    }

    @Test
    public void regionsMaskBlocks() {
        Schematic schematic = new Schematic(null, 4, 1, 1);
        schematic.setRegions(java.util.Collections.singletonList(new SchematicRegion("a", 0, 0, 0, 1, 0, 0)));
        assertTrue(schematic.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState()));
        assertTrue(!schematic.setBlockState(3, 0, 0, Blocks.STONE.getDefaultState()));
    }

    @Test
    public void multiRegionLitematicKeepsRegionsAndOrigin() throws Exception {
        MultiRegionSchematic schematic = new MultiRegionSchematic(null, 5, 1, 1);
        schematic.setRegions(java.util.Arrays.asList(new SchematicRegion("left", 0, 0, 0, 1, 0, 0), new SchematicRegion("right", 3, 0, 0, 4, 0, 0)));
        Schematic left = new Schematic(null, 2, 1, 1);
        left.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        Schematic right = new Schematic(null, 2, 1, 1);
        right.setBlockState(0, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        schematic.addRegion(schematic.getRegions().get(0), left);
        schematic.addRegion(schematic.getRegions().get(1), right);
        File file = File.createTempFile("schematica-test", ".litematic");
        try {
            assertNotNull(SchematicFormat.saveToFile(file, schematic, null, true, false, "t"));
            ISchematic read = SchematicFormat.readFromFile(file);
            assertEquals(2, read.getRegions().size());
            assertSame(Blocks.STONE, read.getBlockState(1, 0, 0).getBlock());
            assertSame(Blocks.GOLD_BLOCK, read.getBlockState(3, 0, 0).getBlock());
            assertSame(Blocks.AIR, read.getBlockState(2, 0, 0).getBlock());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}
