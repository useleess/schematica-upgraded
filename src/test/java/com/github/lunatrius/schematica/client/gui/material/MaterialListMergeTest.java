package com.github.lunatrius.schematica.client.gui.material;

import java.util.Arrays;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MaterialListMergeTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void mergingPutsStandInsIntoTheRowsOfTheirItems() {
        MaterialItemKey stone = new MaterialItemKey(new ItemStack(Blocks.STONE)), dirt = new MaterialItemKey(new ItemStack(Blocks.DIRT));
        List<MaterialListModel.Entry<MaterialItemKey>> merged = MaterialList.merge(Arrays.asList(
            new MaterialListModel.Entry<>(stone, "Stone", "minecraft:stone", 10, 4, 1, 0),
            new MaterialListModel.Entry<>(stone.withOrigin("minecraft:deepslate"), "Stone (from Deepslate)", "minecraft:stone", 5, 5, 0, 2),
            new MaterialListModel.Entry<>(dirt, "Dirt", "minecraft:dirt", 3, 0, 0, 0),
            new MaterialListModel.Entry<>(stone.withOrigin("minecraft:tuff"), "Stone (from Tuff)", "minecraft:stone", 1, 1, 1, 0)));
        assertEquals(2, merged.size());
        MaterialListModel.Entry<MaterialItemKey> row = merged.get(0);
        assertEquals(stone, row.key);
        assertNull(row.key.origin());
        assertEquals(16, row.total);
        assertEquals(10, row.missing);
        assertEquals(2, row.mismatched);
        assertEquals(2, row.unknown);
        assertEquals(dirt, merged.get(1).key);
    }
}
