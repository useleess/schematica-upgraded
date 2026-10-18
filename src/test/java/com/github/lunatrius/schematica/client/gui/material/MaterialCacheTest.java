package com.github.lunatrius.schematica.client.gui.material;

import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialCacheTest {
    private static Item ITEM;
    private static final PropertyInteger LEVEL = PropertyInteger.create("level", 0, 15);

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        ITEM = new Item();
    }

    private static final class CountingBlock extends Block {
        int picks;
        final boolean tile;
        CountingBlock(boolean tile) { super(Material.ROCK); this.tile = tile; }
        @Override protected BlockStateContainer createBlockState() { return new BlockStateContainer(this, LEVEL); }
        @Override public IBlockState getStateFromMeta(int meta) { return getDefaultState().withProperty(LEVEL, meta); }
        @Override public int getMetaFromState(IBlockState state) { return state.getValue(LEVEL); }
        @Override public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {
            picks++;
            return new ItemStack(ITEM, 5, picks);
        }
        @Override public boolean hasTileEntity(IBlockState state) { return tile; }
    }

    @Test public void cachesPlainBlocksPerStateButNotTileEntityBlocks() {
        MaterialCache cache = MaterialCache.INSTANCE;
        cache.clear();
        CountingBlock plain = new CountingBlock(false);
        IBlockState three = plain.getStateFromMeta(3), four = plain.getStateFromMeta(4);
        MaterialCache.BuildItems first = cache.items(null, BlockPos.ORIGIN, three, null);
        assertEquals(1, first.size());
        assertEquals(1, first.count(0)); // one item per block, whatever the picked stack size
        assertSame(first, cache.items(null, new BlockPos(1, 2, 3), three, null));
        assertEquals(1, plain.picks);
        cache.items(null, BlockPos.ORIGIN, four, null);
        assertEquals(2, plain.picks);
        cache.clear();
        cache.items(null, BlockPos.ORIGIN, three, null);
        assertEquals(3, plain.picks);
        CountingBlock machine = new CountingBlock(true);
        MaterialCache.BuildItems a = cache.items(null, BlockPos.ORIGIN, machine.getDefaultState(), null);
        MaterialCache.BuildItems b = cache.items(null, BlockPos.ORIGIN, machine.getDefaultState(), null);
        assertEquals(2, machine.picks);
        assertNotEquals(a, b);
    }

    @Test public void buildItemsMergeEqualStacks() {
        MaterialCache.BuildItems items = MaterialCache.BuildItems.of(new ItemStack(ITEM, 1, 0), null, new ItemStack(ITEM, 2, 0), new ItemStack(ITEM, 1, 1));
        assertEquals(2, items.size());
        assertEquals(3, items.count(0));
        assertEquals(items, MaterialCache.BuildItems.of(new ItemStack(ITEM, 3, 0), new ItemStack(ITEM, 1, 1)));
        assertTrue(MaterialCache.BuildItems.of().isEmpty());
        assertSame(MaterialCache.BuildItems.NONE, MaterialCache.BuildItems.of((ItemStack) null));
    }

    @Test public void hudListsTheMissingItemsBeyondTheInventory() {
        MaterialListModel<String> model = new MaterialListModel<>();
        MaterialListModel.Entry<String> stone = new MaterialListModel.Entry<>("stone", "Stone", "minecraft:stone", 100, 40, 0, 0);
        MaterialListModel.Entry<String> dirt = new MaterialListModel.Entry<>("dirt", "Dirt", "minecraft:dirt", 50, 10, 0, 0);
        MaterialListModel.Entry<String> glass = new MaterialListModel.Entry<>("glass", "Glass", "minecraft:glass", 20, 20, 0, 0);
        stone.available = 15;
        dirt.available = 10;
        model.setEntries(Arrays.asList(stone, dirt, glass));
        model.setSort(MaterialListModel.Sort.MISSING, true);
        assertEquals(Arrays.asList(stone, glass), model.missingOnly());
        assertEquals(25, model.hudCount(stone));
        model.ignore("glass");
        assertEquals(Arrays.asList(stone), model.missingOnly());
        model.setMultiplier(3);
        assertEquals(300, model.hudCount(stone));
        assertEquals(Arrays.asList(stone, dirt), model.missingOnly());
    }
}
