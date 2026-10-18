package com.github.lunatrius.schematica.client.gui.browser;

import static org.junit.Assert.assertNotNull;

import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

/** Huge schematics get a (downsampled) 3D preview instead of none. */
public class SchematicPreview3DTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Test(timeout = 60_000)
    public void billionCellBoxGetsADownsampledPreview() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 2000, 256, 2000);
        for (int x = 0; x < 2000; x += 7) schematic.setBlockState(x, 0, x, Blocks.STONE.getDefaultState());
        schematic.setBlockState(1999, 255, 1999, Blocks.GOLD_BLOCK.getDefaultState());
        assertNotNull(SchematicPreview3D.of(schematic));
    }

    @Test(timeout = 60_000)
    public void denseLargeBoxGetsAPreview() {
        // 300 x 64 x 300 solid = 5.76M blocks, over the old 4M volume cap and the 1M cell budget
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 300, 64, 300);
        for (int y = 0; y < 64; y++) for (int z = 0; z < 300; z++) for (int x = 0; x < 300; x++) {
            schematic.setBlockState(x, y, z, Blocks.STONE.getDefaultState());
        }
        assertNotNull(SchematicPreview3D.of(schematic));
    }
}
