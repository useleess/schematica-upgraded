package com.github.lunatrius.schematica.world.storage;

/**
 * A set of int indices kept in lazily allocated pages of 65536 bits, so the region masks of a huge, mostly empty
 * bounding box only take memory where regions are. Covers the whole non-negative int range.
 */
public final class SparseBits {
    private static final int PAGE_BITS = 16;
    private static final int PAGE_WORDS = 1 << (PAGE_BITS - 6);
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    /** Shared marker for a page whose bits are all set; replaced by a real page before it is changed. */
    private static final long[] FULL = new long[PAGE_WORDS];

    static {
        java.util.Arrays.fill(FULL, -1L);
    }

    private long[][] pages = new long[0][];

    public boolean get(int index) {
        if (index < 0) return false;
        int page = index >>> PAGE_BITS;
        if (page >= pages.length) return false;
        long[] words = pages[page];
        return words != null && (words[(index & PAGE_MASK) >>> 6] & 1L << index) != 0;
    }

    public void set(int index, boolean value) {
        if (value) set(index);
        else clear(index);
    }

    public void set(int index) {
        if (index < 0) throw new IndexOutOfBoundsException("index < 0: " + index);
        long[] words = writable(index >>> PAGE_BITS);
        words[(index & PAGE_MASK) >>> 6] |= 1L << index;
    }

    public void clear(int index) {
        if (index < 0 || (index >>> PAGE_BITS) >= pages.length || pages[index >>> PAGE_BITS] == null) return;
        long[] words = writable(index >>> PAGE_BITS);
        words[(index & PAGE_MASK) >>> 6] &= ~(1L << index);
    }

    /** Sets the bits from {@code from} (inclusive) to {@code to} (exclusive). */
    public void set(int from, int to) {
        if (from < 0 || to < from) throw new IndexOutOfBoundsException("Invalid range " + from + ".." + to);
        int index = from;
        while (index < to) {
            int page = index >>> PAGE_BITS;
            int pageEnd = (int) Math.min(to, ((long) page + 1) << PAGE_BITS);
            if ((index & PAGE_MASK) == 0 && pageEnd - index == 1 << PAGE_BITS) {
                grow(page);
                pages[page] = FULL;
            } else if (pages.length <= page || pages[page] != FULL) {
                long[] words = writable(page);
                for (int i = index; i < pageEnd; i++) words[(i & PAGE_MASK) >>> 6] |= 1L << i;
            }
            index = pageEnd;
        }
    }

    /** The first set index at or after {@code from}, or -1. */
    public int nextSetBit(int from) {
        if (from < 0) from = 0;
        for (int page = from >>> PAGE_BITS; page < pages.length; page++) {
            long[] words = pages[page];
            if (words == null) continue;
            int start = page == from >>> PAGE_BITS ? from & PAGE_MASK : 0;
            for (int word = start >>> 6; word < PAGE_WORDS; word++) {
                long bits = words[word];
                if (word == start >>> 6) bits &= -1L << start;
                if (bits != 0) return (page << PAGE_BITS) | (word << 6) | Long.numberOfTrailingZeros(bits);
            }
        }
        return -1;
    }

    private long[] writable(int page) {
        grow(page);
        long[] words = pages[page];
        if (words == null) {
            words = new long[PAGE_WORDS];
            pages[page] = words;
        } else if (words == FULL) {
            words = FULL.clone();
            pages[page] = words;
        }
        return words;
    }

    private void grow(int page) {
        if (page >= pages.length) pages = java.util.Arrays.copyOf(pages, Math.max(page + 1, Math.min(pages.length * 2, (Integer.MAX_VALUE >>> PAGE_BITS) + 1)));
    }
}
