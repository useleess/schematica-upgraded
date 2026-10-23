package com.github.lunatrius.schematica.world.schematic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStone;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.util.EnumFacing;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class SchematicFormatsTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    private static Schematic sample() {
        Schematic schematic = new Schematic(new ItemStack(Blocks.STONE), 3, 2, 4);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE));
        schematic.setBlockState(1, 0, 0, Blocks.PLANKS.getDefaultState().withProperty(BlockPlanks.VARIANT, BlockPlanks.EnumType.DARK_OAK));
        schematic.setBlockState(2, 1, 3, Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.WEST)
            .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP));
        schematic.setBlockState(1, 1, 1, Blocks.STONE_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP));
        return schematic;
    }

    private static void assertSameBlocks(ISchematic expected, ISchematic actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getLength(), actual.getLength());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int z = 0; z < expected.getLength(); z++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    assertEquals("at " + x + " " + y + " " + z, expected.getBlockState(x, y, z), actual.getBlockState(x, y, z));
                }
            }
        }
    }

    /** Issue #2: saving keeps entities unless the player ticks "Ignore entities". */
    @Test
    public void entitiesAreSavedByDefault() {
        org.junit.Assert.assertTrue(SchematicFormat.saveEntities);
    }

    @Test
    public void paletteStoresAndReturnsStates() {
        Schematic schematic = sample();
        assertSame(Blocks.AIR, schematic.getBlockState(2, 0, 0).getBlock());
        assertEquals(BlockStone.EnumType.GRANITE, schematic.getBlockState(0, 0, 0).getValue(BlockStone.VARIANT));
        assertSame(Blocks.AIR, schematic.getBlockState(-1, 0, 0).getBlock());
        assertTrue(!schematic.setBlockState(5, 0, 0, Blocks.STONE.getDefaultState()));
    }

    @Test
    public void alphaRoundTrip() {
        Schematic schematic = sample();
        NBTTagCompound tag = new NBTTagCompound();
        assertTrue(new SchematicAlpha().writeToNBT(tag, schematic, null, true, false, false));
        ISchematic read = new SchematicAlpha().readFromNBT(tag);
        assertNotNull(read);
        assertSameBlocks(schematic, read);
    }

    @Test
    public void alphaReadsIdsAndMetadataOf1710() {
        // 2x1x1: stone variant granite (id 1, meta 1) and an east-facing bottom oak stair (id 53, meta 0)
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(Names.NBT.MATERIALS, Names.NBT.FORMAT_ALPHA);
        tag.setShort(Names.NBT.WIDTH, (short) 2);
        tag.setShort(Names.NBT.HEIGHT, (short) 1);
        tag.setShort(Names.NBT.LENGTH, (short) 1);
        tag.setByteArray(Names.NBT.BLOCKS, new byte[] {1, 53});
        tag.setByteArray(Names.NBT.DATA, new byte[] {1, 0});
        ISchematic read = new SchematicAlpha().readFromNBT(tag);
        assertEquals(BlockStone.EnumType.GRANITE, read.getBlockState(0, 0, 0).getValue(BlockStone.VARIANT));
        IBlockState stairs = read.getBlockState(1, 0, 0);
        assertSame(Blocks.OAK_STAIRS, stairs.getBlock());
        assertEquals(EnumFacing.EAST, stairs.getValue(BlockStairs.FACING));
        assertEquals(BlockStairs.EnumHalf.BOTTOM, stairs.getValue(BlockStairs.HALF));
    }

    @Test
    public void litematicRoundTripThroughAFile() throws Exception {
        Schematic schematic = sample();
        File file = File.createTempFile("schematica-test", ".litematic");
        try {
            File written = SchematicFormat.saveToFile(file, schematic, null, true, false, "test");
            assertNotNull(written);
            ISchematic read = SchematicFormat.readFromFile(written);
            assertNotNull("litematic could not be read back", read);
            // Litematica regions are re-based to their minimum corner, which here is the schematic origin
            assertSameBlocks(schematic, read);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void litematicVersion4WritesLongArrayAndPalette() throws Exception {
        LitematicExport.Document document = LitematicExport.encode(sample(), null, true, false, "x", "me", 1L);
        assertEquals(4, document.root.getInteger("Version"));
        NBTTagCompound region = document.root.getCompoundTag("Regions").getCompoundTag("x");
        assertTrue(region.getTagList("BlockStatePalette", 10).tagCount() >= 5);
        assertNotNull(LitematicaNBTReader.getLongArray(region, "BlockStates"));
        assertEquals("minecraft:stone", region.getTagList("BlockStatePalette", 10).getCompoundTagAt(1).getString("Name"));
    }

    @Test
    public void flattenedNamesMapTo112States() {
        NBTTagCompound planks = LegacyBlockStates.tag("minecraft:birch_planks");
        IBlockState state = BlockStates.fromModern(planks);
        assertSame(Blocks.PLANKS, state.getBlock());
        assertEquals(BlockPlanks.EnumType.BIRCH, state.getValue(BlockPlanks.VARIANT));

        IBlockState stairs = BlockStates.fromModern(LegacyBlockStates.tag(
            "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"));
        assertSame(Blocks.OAK_STAIRS, stairs.getBlock());
        assertEquals(EnumFacing.NORTH, stairs.getValue(BlockStairs.FACING));
        assertEquals(BlockStairs.EnumHalf.TOP, stairs.getValue(BlockStairs.HALF));

        assertSame(Blocks.GRASS, BlockStates.fromModern(LegacyBlockStates.tag("minecraft:grass_block[snowy=false]")).getBlock());
    }

    @Test
    public void stateStringsRoundTrip() {
        IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.SOUTH);
        String text = BlockStates.toString(stairs);
        assertTrue(text, text.startsWith("minecraft:oak_stairs["));
        assertEquals(stairs, BlockStates.parse(text));
    }

    private static NBTTagCompound xyz(int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x); tag.setInteger("y", y); tag.setInteger("z", z);
        return tag;
    }

    /** One 300x200x300 region (18M cells, over the old 16.7M limit), a newer file version and invalid entries that are skipped. */
    @Test
    public void largeSingleRegionLitematicWithInvalidEntriesLoads() {
        int sx = 300, sy = 200, sz = 300;
        long cells = (long) sx * sy * sz;
        long[] states = new long[(int) ((cells * 2 + 63) / 64)];
        states[0] = 1; // stone at 0,0,0
        long last = (cells - 1) * 2;
        states[(int) (last >> 6)] |= 2L << (last & 63); // palette 2 (empty name -> air) at the far corner
        NBTTagList palette = new NBTTagList();
        for (String name : new String[] {"minecraft:air", "minecraft:stone", ""}) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("Name", name);
            palette.appendTag(entry);
        }
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound outside = xyz(999, 0, 0);
        outside.setString("id", "minecraft:chest");
        tiles.appendTag(outside);
        NBTTagCompound noPosition = new NBTTagCompound();
        noPosition.setString("id", "minecraft:chest");
        tiles.appendTag(noPosition);
        NBTTagList entities = new NBTTagList();
        NBTTagCompound badEntity = new NBTTagCompound();
        badEntity.setString("id", "minecraft:pig");
        entities.appendTag(badEntity);
        NBTTagCompound region = new NBTTagCompound();
        region.setTag("Position", xyz(0, 0, 0));
        region.setTag("Size", xyz(sx, sy, sz));
        region.setTag("BlockStatePalette", palette);
        region.setTag("BlockStates", new NBTTagLongArray(states));
        region.setTag("TileEntities", tiles);
        region.setTag("Entities", entities);
        NBTTagCompound regions = new NBTTagCompound();
        regions.setTag("big", region);
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("Version", 8);
        root.setInteger("MinecraftDataVersion", 1343);
        root.setTag("Metadata", new NBTTagCompound());
        root.setTag("Regions", regions);

        ISchematic read = new SchematicLitematica().readFromNBT(root);
        assertEquals(sx, read.getWidth());
        assertSame(Blocks.STONE, read.getBlockState(0, 0, 0).getBlock());
        assertSame(Blocks.AIR, read.getBlockState(sx - 1, sy - 1, sz - 1).getBlock());
        assertTrue(read.getTileEntities().isEmpty());
    }
}
