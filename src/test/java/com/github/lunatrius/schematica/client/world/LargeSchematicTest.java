package com.github.lunatrius.schematica.client.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.world.schematic.SchematicFileSnapshot;
import com.github.lunatrius.schematica.world.storage.MultiRegionSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

/** Schematics whose bounding box is far larger than their contents (spread-out litematic regions). */
public class LargeSchematicTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    private static int count(ISchematic schematic) {
        int[] count = {0};
        ((Schematic) schematic).forEachBlock((x, y, z, state) -> count[0]++);
        return count[0];
    }

    @Test
    public void hugeMostlyEmptyBoxIsStoredSparsely() {
        // 2000 x 20 x 2000 = 80M cells, beyond the old 16M dense limit
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 2000, 20, 2000);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(1999, 19, 1999, Blocks.GOLD_BLOCK.getDefaultState());
        schematic.setBlockState(500, 5, 500, Blocks.AIR.getDefaultState());
        assertSame(Blocks.STONE, schematic.getBlockState(0, 0, 0).getBlock());
        assertSame(Blocks.GOLD_BLOCK, schematic.getBlockState(1999, 19, 1999).getBlock());
        assertSame(Blocks.AIR, schematic.getBlockState(1000, 10, 1000).getBlock());
        assertEquals(2, count(schematic));
    }

    /** Two small regions 2000 blocks apart at full world height: a ~1 billion cell box that must load, rotate and move cheaply. */
    @Test
    public void billionCellBoxWithSmallRegionsLoadsPlacesAndRotates() {
        MultiRegionSchematic schematic = new MultiRegionSchematic(SchematicWorld.DEFAULT_ICON, 2000, 256, 2000);
        Schematic a = new Schematic(SchematicWorld.DEFAULT_ICON, 10, 10, 10);
        a.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        Schematic b = new Schematic(SchematicWorld.DEFAULT_ICON, 10, 10, 10);
        b.setBlockState(9, 9, 9, Blocks.GOLD_BLOCK.getDefaultState());
        SchematicRegion boxA = new SchematicRegion("a", 0, 0, 0, 9, 9, 9), boxB = new SchematicRegion("b", 1990, 246, 1990, 1999, 255, 1999);
        schematic.setRegions(Arrays.asList(boxA, boxB));
        schematic.addRegion(boxA, a);
        schematic.addRegion(boxB, b);
        assertSame(Blocks.GOLD_BLOCK, schematic.getBlockState(1999, 255, 1999).getBlock());
        assertEquals(2, count(schematic));

        SchematicWorld world = new SchematicWorld(schematic, "huge");
        world.isRendering = true;
        world.position.set(0, 0, 0);
        world.rotate(EnumFacing.Axis.Y);
        assertEquals(2, count(world.getSchematic()));
        assertEquals(2, world.getSchematic().getRegions().size());
    }

    @Test
    public void overlappingRegionsKeepEachOthersBlocks() {
        MultiRegionSchematic schematic = new MultiRegionSchematic(SchematicWorld.DEFAULT_ICON, 3, 1, 1);
        Schematic a = new Schematic(SchematicWorld.DEFAULT_ICON, 2, 1, 1);
        a.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        Schematic b = new Schematic(SchematicWorld.DEFAULT_ICON, 2, 1, 1);
        b.setBlockState(1, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        schematic.setRegions(Arrays.asList(new SchematicRegion("a", 0, 0, 0, 1, 0, 0), new SchematicRegion("b", 1, 0, 0, 2, 0, 0)));
        schematic.addRegion(new SchematicRegion("a", 0, 0, 0, 1, 0, 0), a);
        // b's air at x=1 must not erase a's stone there
        schematic.addRegion(new SchematicRegion("b", 1, 0, 0, 2, 0, 0), b);
        assertSame(Blocks.STONE, schematic.getBlockState(1, 0, 0).getBlock());
        assertSame(Blocks.GOLD_BLOCK, schematic.getBlockState(2, 0, 0).getBlock());
    }

    /** The 720x121x720 world eater from the import folder (skipped when it is not there). */
    @Test
    public void worldEaterLoadsPlacesAndRotates() throws Exception {
        File file = new File("../reference/test-schematics/720 World Eater (12w trenches).litematic");
        Assume.assumeTrue(file.isFile());
        SchematicSourceData source = SchematicSourceData.read(file);
        ISchematic schematic = source.instantiate();
        assertEquals(720, schematic.getWidth());
        assertEquals(121, schematic.getHeight());
        assertEquals(720, schematic.getLength());
        assertEquals(4, schematic.getRegions().size());
        assertEquals(27370, count(schematic));

        SchematicWorld world = new SchematicWorld(schematic, file.getName());
        world.setPlacementSource(source);
        world.isRendering = true;
        world.position.set(0, 0, 0);
        world.rotate(EnumFacing.Axis.Y);
        assertEquals(27370, count(world.getSchematic()));

        // moving a sub-region recomposes the placement from the source
        int[] middle = world.subregionCorners("Middle");
        world.moveSubregionTo("Middle", middle[0] + 3, middle[1], middle[2]);
        assertTrue(world.subregions().modified());
        // Middle's concrete shared a cell with another region before the move, so there is one more block now
        assertEquals(27371, count(world.getSchematic()));
        assertSame(Blocks.AIR, world.getBlockState(new BlockPos(-1, -1, -1)).getBlock());
    }
}
