package com.github.lunatrius.schematica.world.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.BitSet;
import java.util.Random;

import org.junit.Test;

public class SparseBitsTest {

    @Test
    public void matchesBitSet() {
        Random random = new Random(7);
        SparseBits sparse = new SparseBits();
        BitSet dense = new BitSet();
        for (int i = 0; i < 2000; i++) {
            int from = random.nextInt(400_000), to = from + random.nextInt(i % 10 == 0 ? 200_000 : 100);
            if (random.nextInt(4) == 0) {
                sparse.clear(from); dense.clear(from);
            } else {
                sparse.set(from, to); dense.set(from, to);
            }
        }
        for (int i = 0; i < 700_000; i++) assertEquals(dense.get(i), sparse.get(i));
        for (int i = dense.nextSetBit(0), j = sparse.nextSetBit(0); ; i = dense.nextSetBit(i + 1), j = sparse.nextSetBit(j + 1)) {
            assertEquals(i, j);
            if (i < 0) break;
        }
    }

    @Test
    public void reachesTheTopOfTheIntRange() {
        SparseBits bits = new SparseBits();
        bits.set(Integer.MAX_VALUE - 5, Integer.MAX_VALUE);
        assertTrue(bits.get(Integer.MAX_VALUE - 1));
        assertFalse(bits.get(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 5, bits.nextSetBit(0));
        bits.set(0, 1 << 17);
        bits.clear(70_000);
        assertFalse(bits.get(70_000));
        assertTrue(bits.get(70_001));
    }
}
