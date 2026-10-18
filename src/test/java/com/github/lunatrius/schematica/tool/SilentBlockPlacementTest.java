package com.github.lunatrius.schematica.tool;

import java.lang.reflect.Field;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class SilentBlockPlacementTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void writesSectionBoundariesWithoutCallingBlockCallbacks() throws Exception {
        Block block = new CallbackBlock();
        IBlockState state = block.getDefaultState();
        Chunk chunk = new Chunk(null, -1, 2);
        for (int y : new int[] {0, 15, 16, 255}) {
            SilentBlockPlacement.writeStorage(chunk, 3, y, 5, state, true);
            assertSame(state, chunk.getBlockStorageArray()[y >> 4].get(3, y & 15, 5));
            assertTrue(chunk.getBlockStorageArray()[y >> 4].needsRandomTick());
            assertEquals(15, chunk.getBlockStorageArray()[y >> 4].getSkyLight(4, y & 15, 5));
        }
        assertTrue(chunk.needsSaving(false));
        Field precipitation = SilentBlockPlacement.precipitation();
        assertEquals(-999, ((int[]) precipitation.get(chunk))[5 << 4 | 3]);
        Chunk nether = new Chunk(null, 0, 0);
        SilentBlockPlacement.writeStorage(nether, 0, 64, 0, state, false);
        assertNull(nether.getBlockStorageArray()[4].getSkyLight());
    }

    private static final class CallbackBlock extends Block {
        private CallbackBlock() { super(Material.ROCK); setTickRandomly(true); }
        @Override public void onBlockAdded(World world, BlockPos pos, IBlockState state) { fail("Placement callback"); }
        @Override public void breakBlock(World world, BlockPos pos, IBlockState state) { fail("Break callback"); }
    }
}
