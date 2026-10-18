package com.github.lunatrius.schematica.client.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.world.schematic.SchematicFileSnapshot;
import com.github.lunatrius.schematica.world.storage.Schematic;

/** Sub-region placements: moving one region recomposes the placement from its source. */
public class SubRegionCompositionTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    /** Two 2x1x1 regions side by side: stone at x 0-1 ("left"), gold at x 2-3 ("right"). */
    private static SchematicWorld placement() throws Exception {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 4, 1, 1);
        schematic.setRegions(Arrays.asList(new SchematicRegion("left", 0, 0, 0, 1, 0, 0), new SchematicRegion("right", 2, 0, 0, 3, 0, 0)));
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(2, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        schematic.setBlockState(3, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        SchematicSourceData source = SchematicSourceData.of(SchematicFileSnapshot.capture(schematic));
        SchematicWorld world = new SchematicWorld(source.instantiate(), "two.schemplus");
        world.setPlacementSource(source);
        world.isRendering = true;
        world.position.set(100, 64, 100);
        return world;
    }

    @Test
    public void regionsAreListedFromTheSource() throws Exception {
        SchematicWorld world = placement();
        assertNotNull(world.subregions());
        assertEquals(2, world.subregions().regions().size());
        assertTrue(world.hasEnabledRegions());
        assertEquals(2, world.enabledRegionBounds().size());
    }

    @Test
    public void movingARegionRecomposesThePlacement() throws Exception {
        SchematicWorld world = placement();
        int[] right = world.subregionCorners("right");
        assertEquals(102, right[0]);
        // move "right" two blocks further east: the placement grows to 6 wide with a gap
        world.moveSubregionTo("right", right[0] + 2, right[1], right[2]);
        assertEquals(6, world.getWidth());
        assertSame(Blocks.STONE, world.getBlockState(new BlockPos(0, 0, 0)).getBlock());
        assertSame(Blocks.AIR, world.getBlockState(new BlockPos(2, 0, 0)).getBlock());
        assertSame(Blocks.GOLD_BLOCK, world.getBlockState(new BlockPos(4, 0, 0)).getBlock());
        assertTrue(world.isBlockRendered(4, 0, 0));
        assertTrue(world.subregions().modified());
        int[] box = world.enclosingBox();
        assertEquals(105, box[3]);
    }

    @Test
    public void repeatedRegionChangesDecodeTheFileOnceAndNeverChangeIt() throws Exception {
        SchematicWorld world = placement();
        SchematicSourceData source = world.sourceData();
        int[] right = world.subregionCorners("right");
        world.moveSubregionTo("right", right[0] + 2, right[1], right[2]);
        Object first = source.composeSource();
        // changes to the placement's own blocks never reach the shared compose source
        world.getSchematic().setBlockState(0, 0, 0, Blocks.DIRT.getDefaultState());
        world.moveSubregionTo("right", right[0] + 3, right[1], right[2]);
        world.rotate(EnumFacing.Axis.Y);
        assertSame(first, source.composeSource());
        assertSame(Blocks.STONE, source.composeSource().getBlockState(new BlockPos(0, 0, 0)).getBlock());
        assertEquals(4, source.composeSource().getWidth());
    }

    @Test
    public void rotatingAModifiedPlacementKeepsTheRegionLayout() throws Exception {
        SchematicWorld world = placement();
        int[] right = world.subregionCorners("right");
        world.moveSubregionTo("right", right[0] + 2, right[1], right[2]);
        world.rotate(EnumFacing.Axis.Y);
        // 6 wide along x becomes 6 long along z
        assertEquals(1, world.getWidth());
        assertEquals(6, world.getLength());
        assertEquals(1, world.rotationStateY);
        int gold = 0;
        for (int z = 0; z < 6; z++) if (world.getBlockState(new BlockPos(0, 0, z)).getBlock() == Blocks.GOLD_BLOCK) gold++;
        assertEquals(2, gold);
    }

    @Test
    public void resettingRegionsRestoresTheSourceLayout() throws Exception {
        SchematicWorld world = placement();
        int[] right = world.subregionCorners("right");
        world.moveSubregionTo("right", right[0] + 2, right[1], right[2]);
        world.resetSubregions(null);
        assertEquals(4, world.getWidth());
        assertSame(Blocks.GOLD_BLOCK, world.getBlockState(new BlockPos(2, 0, 0)).getBlock());
    }

    @Test
    public void cellStatesTransformAndWrite() {
        CellState stairs = CellState.of(Blocks.OAK_STAIRS.getDefaultState()
            .withProperty(net.minecraft.block.BlockStairs.FACING, EnumFacing.EAST));
        CellState turned = stairs.transform(Arrays.asList("Y"), new java.util.HashMap<>());
        assertEquals(EnumFacing.SOUTH, turned.state.getValue(net.minecraft.block.BlockStairs.FACING));
        CellState chest = CellState.of(Blocks.CHEST.getDefaultState());
        assertNotNull("a chest cell carries its tile entity", chest.tile);
        Schematic target = new Schematic(null, 2, 1, 1);
        chest.write(target, 1, 0, 0);
        assertSame(Blocks.CHEST, target.getBlockState(1, 0, 0).getBlock());
        assertNotNull(target.getTileEntity(new BlockPos(1, 0, 0)));
    }
}
