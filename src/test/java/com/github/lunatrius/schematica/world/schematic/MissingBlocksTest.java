package com.github.lunatrius.schematica.world.schematic;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStoneBrick;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class MissingBlocksTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @After public void forgetPicks() {
        Map<String, String> none = new HashMap<>();
        none.put("minecraft:deepslate", null);
        MissingBlocks.choose(none);
    }

    private static Map<String, String> props(String... pairs) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    @Test public void everyTableTargetIsA112Block() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            MissingBlocksTest.class.getResourceAsStream("/assets/schematica/data/missing_blocks.txt"), StandardCharsets.UTF_8))) {
            String line;
            int checked = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts[0].equals("rule")) continue;
                assertEquals(line, 3, parts.length);
                String target = parts[2].startsWith("!") || parts[2].equals("air") ? parts[2].replace("air", MissingBlockGuesses.AIR)
                    : "minecraft:" + parts[2];
                assertNotNull(line, MissingBlocks.target(target, Collections.<String, String>emptyMap()));
                checked++;
            }
            assertEquals(true, checked > 150);
        }
    }

    @Test public void guessesByNameRulesAndShapes() {
        assertEquals("minecraft:brick_stairs", MissingBlockGuesses.guess("minecraft:resin_brick_stairs"));
        assertEquals("minecraft:oak_button", MissingBlockGuesses.guess("minecraft:mangrove_button"));
        assertEquals("minecraft:oak_trapdoor", MissingBlockGuesses.guess("minecraft:cherry_trapdoor"));
        assertEquals("minecraft:stone_brick_slab", MissingBlockGuesses.guess("minecraft:deepslate_tile_slab"));
        assertEquals("minecraft:iron_door", MissingBlockGuesses.guess("minecraft:waxed_oxidized_copper_door"));
        assertEquals("minecraft:nether_brick_stairs", MissingBlockGuesses.guess("minecraft:polished_blackstone_brick_stairs"));
        assertEquals("minecraft:acacia_fence", MissingBlockGuesses.guess("minecraft:crimson_fence"));
        assertEquals("minecraft:dark_oak_bark", MissingBlockGuesses.guess("minecraft:warped_hyphae"));
        assertEquals("minecraft:birch_log", MissingBlockGuesses.guess("minecraft:stripped_cherry_log"));
        assertEquals("minecraft:cyan_terracotta", MissingBlockGuesses.guess("minecraft:weathered_cut_copper"));
        assertEquals("minecraft:stone_brick_stairs", MissingBlockGuesses.guess("minecraft:andesite_stairs"));
        assertEquals("minecraft:cobblestone_wall", MissingBlockGuesses.guess("minecraft:tuff_brick_wall"));
        assertEquals("minecraft:red_bed", MissingBlockGuesses.guess("minecraft:blue_bed"));
        assertEquals("minecraft:sign", MissingBlockGuesses.guess("minecraft:pale_oak_hanging_sign"));
        assertEquals(MissingBlockGuesses.STONE, MissingBlockGuesses.guess("somemod:weird_machine"));
        assertEquals(MissingBlockGuesses.AIR, MissingBlockGuesses.guess("somemod:tiny_flower"));
        assertEquals("!minecraft:flower_pot", MissingBlockGuesses.guess("minecraft:potted_azure_bluet_but_newer"));
        assertEquals("Resin Brick Stairs", MissingBlockGuesses.displayName("minecraft:resin_brick_stairs"));
        assertEquals("Weird Machine (somemod)", MissingBlockGuesses.displayName("somemod:weird_machine"));
    }

    @Test public void stand_insKeepTheShapeOfTheFile() {
        IBlockState stairs = MissingBlocks.resolve("minecraft:cherry_stairs", props("facing", "east", "half", "top", "shape", "straight"));
        assertSame(Blocks.BIRCH_STAIRS, stairs.getBlock());
        assertEquals(EnumFacing.EAST, stairs.getValue(BlockStairs.FACING));
        assertEquals(BlockStairs.EnumHalf.TOP, stairs.getValue(BlockStairs.HALF));
        IBlockState door = MissingBlocks.resolve("minecraft:copper_door", props("half", "upper", "facing", "north", "hinge", "right"));
        assertSame(Blocks.IRON_DOOR, door.getBlock());
        assertEquals(BlockDoor.EnumDoorHalf.UPPER, door.getValue(BlockDoor.HALF));
        assertSame(Blocks.WATER, MissingBlocks.resolve("minecraft:glow_lichen", props("waterlogged", "true")).getBlock());
        assertSame(Blocks.AIR, MissingBlocks.resolve("minecraft:glow_lichen", props("waterlogged", "false")).getBlock());

        // a pick keeps the shape too, when the picked block has it
        Map<String, String> pick = new HashMap<>();
        pick.put("minecraft:deepslate", "minecraft:stonebrick[variant=stonebrick]");
        MissingBlocks.choose(pick);
        assertEquals(Blocks.STONEBRICK.getDefaultState(), MissingBlocks.resolve("minecraft:deepslate", props("axis", "y")));
        assertEquals(Blocks.STONEBRICK.getDefaultState(), MissingBlocks.replacement("minecraft:deepslate"));
        assertSame(Blocks.STONE, MissingBlocks.guessed("minecraft:deepslate").getBlock());
    }

    @Test public void renamedBlocksLoadAsThemselves() {
        IBlockState wood = BlockStates.fromStateString("minecraft:oak_wood[axis=y]");
        assertSame(Blocks.LOG, wood.getBlock());
        assertEquals(BlockLog.EnumAxis.NONE, wood.getValue(BlockLog.LOG_AXIS));
        assertEquals(BlockPlanks.EnumType.OAK, wood.getValue(net.minecraft.block.BlockOldLog.VARIANT));
        assertSame(Blocks.MOB_SPAWNER, BlockStates.fromStateString("minecraft:spawner").getBlock());
        assertSame(Blocks.TALLGRASS, BlockStates.fromStateString("minecraft:short_grass").getBlock());
        assertSame(Blocks.GRASS_PATH, BlockStates.fromStateString("minecraft:dirt_path").getBlock());
        assertSame(Blocks.SKULL, BlockStates.fromStateString("minecraft:skeleton_wall_skull[facing=south]").getBlock());
    }

    /** A Sponge v2 file of seven blocks in a row with the given palette. */
    private static File sponge(String... palette) throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("Version", 2);
        root.setInteger("DataVersion", 3955);
        root.setShort("Width", (short) palette.length);
        root.setShort("Height", (short) 1);
        root.setShort("Length", (short) 1);
        NBTTagCompound paletteTag = new NBTTagCompound();
        byte[] data = new byte[palette.length];
        for (int i = 0; i < palette.length; i++) { paletteTag.setInteger(palette[i], i); data[i] = (byte) i; }
        root.setTag("Palette", paletteTag);
        root.setInteger("PaletteMax", palette.length);
        root.setByteArray("BlockData", data);
        File file = File.createTempFile("missing-blocks", ".schem");
        file.deleteOnExit();
        CompressedStreamTools.writeCompressed(root, new java.io.FileOutputStream(file));
        return file;
    }

    @Test public void filesKeepWhichBlockEachStandInIsFor() throws Exception {
        File file = sponge("minecraft:stone", "minecraft:deepslate[axis=y]",
            "minecraft:cherry_stairs[facing=east,half=top,shape=straight,waterlogged=false]", "minecraft:oak_wood[axis=y]",
            "minecraft:glow_lichen[down=true,waterlogged=false]", "minecraft:seagrass", "minecraft:tuff");
        ISchematic read = SchematicFormat.readFromFile(file);
        assertNotNull(read);
        Map<String, Long> counts = ImportReport.lastCounts();
        assertEquals(new java.util.HashSet<>(Arrays.asList("minecraft:deepslate", "minecraft:cherry_stairs", "minecraft:glow_lichen",
            "minecraft:seagrass", "minecraft:tuff")), counts.keySet());
        assertEquals(Long.valueOf(1), counts.get("minecraft:tuff"));

        assertSame(Blocks.STONE, read.getBlockState(0, 0, 0).getBlock());
        assertNull(Schematic.originOf(read, 0, 0, 0));
        assertSame(Blocks.STONE, read.getBlockState(1, 0, 0).getBlock());
        assertEquals("minecraft:deepslate", Schematic.originOf(read, 1, 0, 0));
        assertSame(Blocks.BIRCH_STAIRS, read.getBlockState(2, 0, 0).getBlock());
        assertEquals("minecraft:cherry_stairs", Schematic.originOf(read, 2, 0, 0));
        assertSame(Blocks.LOG, read.getBlockState(3, 0, 0).getBlock());
        assertNull(Schematic.originOf(read, 3, 0, 0));
        assertSame(Blocks.AIR, read.getBlockState(4, 0, 0).getBlock());
        assertSame(net.minecraft.block.material.Material.WATER, read.getBlockState(5, 0, 0).getMaterial());
        assertEquals("minecraft:seagrass", Schematic.originOf(read, 5, 0, 0));

        Map<String, String> pick = new HashMap<>();
        pick.put("minecraft:deepslate", "minecraft:stonebrick[variant=stonebrick]");
        MissingBlocks.choose(pick);
        ISchematic again = SchematicFormat.readFromFile(file);
        assertEquals(Blocks.STONEBRICK.getDefaultState().withProperty(BlockStoneBrick.VARIANT, BlockStoneBrick.EnumType.DEFAULT),
            again.getBlockState(1, 0, 0));
        assertEquals("minecraft:deepslate", Schematic.originOf(again, 1, 0, 0));
    }

    @Test public void originsSurviveCopiesRotationAndOrdinaryEdits() {
        Schematic schematic = new Schematic(com.github.lunatrius.schematica.client.world.SchematicWorld.DEFAULT_ICON, 2, 1, 1);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState(), "minecraft:deepslate");
        schematic.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        assertEquals(true, schematic.hasOrigins());

        Schematic copy = com.github.lunatrius.schematica.world.storage.SchematicCopies.copy(schematic);
        assertEquals("minecraft:deepslate", copy.getOrigin(0, 0, 0));
        assertNull(copy.getOrigin(1, 0, 0));

        com.github.lunatrius.schematica.client.world.SchematicWorld world = new com.github.lunatrius.schematica.client.world.SchematicWorld(copy);
        com.github.lunatrius.schematica.client.world.PlacementState.applyTransforms(world, Arrays.asList("Y", "Y"));
        ISchematic turned = world.getSchematic();
        assertEquals("minecraft:deepslate", Schematic.originOf(turned, 1, 0, 0));
        assertNull(Schematic.originOf(turned, 0, 0, 0));

        // an edit that puts another block there makes it a block of the schematic again
        copy.setBlockState(0, 0, 0, Blocks.DIRT.getDefaultState());
        assertNull(copy.getOrigin(0, 0, 0));
        copy.setBlockState(1, 0, 0, Blocks.AIR.getDefaultState(), "minecraft:tuff");
        assertNull(copy.getOrigin(1, 0, 0));
    }

    @Test public void thePickerListsBlocksOfTheGuessedKind() {
        java.util.function.Function<String, Class<?>> kind = name ->
            com.github.lunatrius.schematica.client.gui.material.BlockChoices.kind(MissingBlocks.guessed(name));
        assertSame(net.minecraft.block.BlockSlab.class, kind.apply("minecraft:cherry_slab"));
        assertSame(net.minecraft.block.BlockSlab.class, kind.apply("minecraft:deepslate_tile_slab"));
        assertSame(net.minecraft.block.BlockStairs.class, kind.apply("minecraft:resin_brick_stairs"));
        assertSame(net.minecraft.block.BlockDoor.class, kind.apply("minecraft:copper_door"));
        assertSame(net.minecraft.block.BlockTrapDoor.class, kind.apply("minecraft:cherry_trapdoor"));
        assertSame(net.minecraft.block.BlockWall.class, kind.apply("minecraft:tuff_wall"));
        assertSame(net.minecraft.block.BlockFence.class, kind.apply("minecraft:crimson_fence"));
        assertSame(net.minecraft.block.BlockLog.class, kind.apply("minecraft:cherry_log"));
        assertSame(net.minecraft.block.Block.class, kind.apply("minecraft:deepslate"));
        assertSame(net.minecraft.block.Block.class, kind.apply("minecraft:tube_coral_block"));
        assertNull(kind.apply("minecraft:glow_lichen"));
    }

    @Test public void picksAreRememberedAcrossRestarts() throws Exception {
        File saved = File.createTempFile("missing-blocks", ".json");
        saved.delete();
        saved.deleteOnExit();
        MissingBlocks.load(saved);
        Map<String, String> pick = new HashMap<>();
        pick.put("minecraft:deepslate", "minecraft:stonebrick[variant=stonebrick]");
        MissingBlocks.choose(pick);
        MissingBlocks.load(saved);
        assertEquals("minecraft:stonebrick[variant=stonebrick]", MissingBlocks.choice("minecraft:deepslate"));
        MissingBlocks.load(new File(saved.getPath() + ".none"));
    }

    @Test public void aConvertedFileSavedOverTheOriginalHasNoMissingBlocks() throws Exception {
        File file = sponge("minecraft:stone", "minecraft:deepslate[axis=y]",
            "minecraft:cherry_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        ISchematic converted = SchematicFormat.readFromFile(file);
        assertEquals(false, ImportReport.lastCounts().isEmpty());
        File saved = new File(file.getParentFile(), file.getName().replace(".schem", ".litematic"));
        saved.deleteOnExit();
        assertNotNull(SchematicFormat.saveToFile(saved, converted, null, true, true, "test"));
        ISchematic again = SchematicFormat.readFromFile(saved);
        assertNotNull(again);
        assertEquals(true, ImportReport.lastCounts().isEmpty());
        assertSame(Blocks.STONE, again.getBlockState(1, 0, 0).getBlock());
        IBlockState stairs = again.getBlockState(2, 0, 0);
        assertSame(Blocks.BIRCH_STAIRS, stairs.getBlock());
        assertEquals(EnumFacing.EAST, stairs.getValue(BlockStairs.FACING));
        assertEquals(BlockStairs.EnumHalf.TOP, stairs.getValue(BlockStairs.HALF));
    }
}
