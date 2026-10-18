package com.github.lunatrius.schematica.client.gui.material;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.BlockStoneSlab;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class SchematicFileMaterialScanTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static Map<Item, Integer> run(MaterialScanner scan) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!scan.done()) {
            assertTrue("material scan took too long", System.currentTimeMillis() < deadline);
            scan.step();
            Thread.yield();
        }
        Map<Item, Integer> totals = new HashMap<>();
        List<MaterialListModel.Entry<MaterialItemKey>> entries = scan.result();
        for (MaterialListModel.Entry<MaterialItemKey> entry : entries) totals.merge(entry.key.stack().getItem(), entry.total, Integer::sum);
        return totals;
    }

    /** The 720 x 121 x 720 World Eater box: 62.7M cells, mostly unallocated air sections. */
    private static Schematic bigSparse() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 720, 121, 720);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) schematic.setBlockState(x, 0, z, Blocks.STONE.getDefaultState());
        for (int y = 100; y < 120; y++) schematic.setBlockState(700, y, 700, Blocks.DOUBLE_STONE_SLAB.getDefaultState().withProperty(BlockStoneSlab.VARIANT, BlockStoneSlab.EnumType.STONE));
        schematic.setBlockState(5, 50, 600, Blocks.CHEST.getDefaultState());
        schematic.setBlockState(6, 50, 600, Blocks.CHEST.getDefaultState());
        return schematic;
    }

    @Test public void countsALargeSparseSchematicQuickly() {
        MaterialCache.INSTANCE.clear();
        long start = System.nanoTime();
        Map<Item, Integer> totals = run(new SchematicFileMaterialScan(new SchematicWorld(bigSparse()), Collections.emptyList(), null));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(Integer.valueOf(64 * 64), totals.get(Item.getItemFromBlock(Blocks.STONE)));
        assertEquals(Integer.valueOf(40), totals.get(Item.getItemFromBlock(Blocks.STONE_SLAB))); // double slab = 2 slabs
        assertEquals(Integer.valueOf(2), totals.get(Item.getItemFromBlock(Blocks.CHEST)));
        assertTrue("took " + millis + " ms", millis < 5_000);
    }

    @Test public void countsOnlyTheSelectedSubRegions() {
        MaterialCache.INSTANCE.clear();
        SchematicRegion corner = new SchematicRegion("corner", 0, 0, 0, 9, 0, 9);
        Map<Item, Integer> totals = run(new SchematicFileMaterialScan(new SchematicWorld(bigSparse()), Collections.singletonList(corner), null));
        assertEquals(Integer.valueOf(100), totals.get(Item.getItemFromBlock(Blocks.STONE)));
        assertNull(totals.get(Item.getItemFromBlock(Blocks.CHEST)));
        assertEquals(1, totals.size());
    }

    /** Times the material list of every schematic in the import folder (skipped when there is none). */
    @Test public void importedSchematicsCountQuickly() throws Exception {
        java.io.File[] files = new java.io.File("../reference/test-schematics").listFiles((dir, name) -> name.matches("(?i).*\\.(litematic|schematic|schem|nbt)"));
        org.junit.Assume.assumeTrue(files != null && files.length > 0);
        for (java.io.File file : files) {
            com.github.lunatrius.schematica.client.world.SchematicSourceData source;
            try { source = com.github.lunatrius.schematica.client.world.SchematicSourceData.read(file); }
            catch (Exception e) { continue; } // load failures are ImportFolderLoadTest's business
            SchematicWorld world = new SchematicWorld(source.instantiate(), file.getName());
            MaterialCache.INSTANCE.clear();
            long start = System.nanoTime();
            Map<Item, Integer> totals = run(new SchematicFileMaterialScan(world, Collections.emptyList(), null));
            long millis = (System.nanoTime() - start) / 1_000_000;
            long items = 0;
            for (int total : totals.values()) items += total;
            System.out.println("MATERIALS " + file.getName() + " " + world.getWidth() + "x" + world.getHeight() + "x" + world.getLength()
                + " items=" + items + " kinds=" + totals.size() + " " + millis + " ms");
            assertTrue(file.getName() + " took " + millis + " ms", millis < 15_000);
        }
    }

    @Test public void sectionWalkMatchesCellWalk() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 37, 20, 41);
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) schematic.setBlockState(random.nextInt(37), random.nextInt(20), random.nextInt(41), Blocks.STONE.getDefaultState());
        int[] box = {3, 2, 5, 30, 17, 33};
        long expected = 0;
        for (int y = box[1]; y < box[4]; y++) for (int z = box[2]; z < box[5]; z++) for (int x = box[0]; x < box[3]; x++) {
            if (schematic.getBlockState(x, y, z).getBlock() == Blocks.STONE) expected++;
        }
        long[] seen = {0};
        SchematicSections sections = new SchematicSections(schematic);
        for (int i = 0; i < sections.count(); i++) sections.visit(i, box, (x, y, z, state) -> seen[0]++);
        assertEquals(expected, seen[0]);
    }
}
