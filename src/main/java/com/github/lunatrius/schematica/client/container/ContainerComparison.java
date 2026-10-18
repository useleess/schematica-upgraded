// Container contents check after Tech Utils' inventory verifier (public domain) and QuickCraft's container verifier, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.container;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/** Slot by slot comparison of a container in the world with the same container in the schematic. */
public final class ContainerComparison {
    /** MISSING: expected item, empty slot; EXTRA: item where the schematic slot is empty; WRONG_ITEM: another item; MISMATCH: same item, other count or NBT. */
    public enum Status { MATCH, MISSING, EXTRA, WRONG_ITEM, MISMATCH }

    private ContainerComparison() {}

    public static Status compare(ItemStack expected, ItemStack actual) {
        boolean hasExpected = !empty(expected), hasActual = !empty(actual);
        if (!hasExpected) return hasActual ? Status.EXTRA : Status.MATCH;
        if (!hasActual) return Status.MISSING;
        if (!sameItem(expected, actual)) return Status.WRONG_ITEM;
        return expected.getCount() == actual.getCount() && ItemStack.areItemStackTagsEqual(expected, actual) ? Status.MATCH : Status.MISMATCH;
    }

    static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && (a.isItemStackDamageable() || a.getMetadata() == b.getMetadata());
    }

    /** The same item, damage and NBT, so that the stacks merge in a slot. */
    static boolean stackable(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata() && ItemStack.areItemStackTagsEqual(a, b);
    }

    /**
     * The items the schematic's container holds and the world's lacks, summed per item (same item, damage and NBT)
     * in the order they first appear; with unknown world contents (null) everything the schematic holds.
     */
    public static List<ItemStack> missing(List<ItemStack> expected, List<ItemStack> actual) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : expected) {
            if (empty(stack) || stack.getCount() <= 0) continue;
            ItemStack total = null;
            for (ItemStack entry : result) if (stackable(entry, stack)) total = entry;
            if (total == null) {
                total = stack.copy();
                result.add(total);
            } else {
                total.grow(stack.getCount());
            }
        }
        if (actual != null) {
            for (ItemStack stack : actual) {
                if (empty(stack)) continue;
                for (ItemStack entry : result) if (stackable(entry, stack)) entry.shrink(stack.getCount());
            }
            result.removeIf(entry -> entry.getCount() <= 0);
        }
        return result;
    }

    /** A container slot: its index in the open container's slot list, the schematic's stack and the current one. */
    public static final class Target {
        final int slot;
        final ItemStack expected, actual;
        public Target(int slot, ItemStack expected, ItemStack actual) { this.slot = slot; this.expected = expected; this.actual = actual; }
    }

    /** A player inventory slot of the open container that can give items. */
    public static final class Source {
        final int slot;
        final ItemStack stack;
        int count;
        public Source(int slot, ItemStack stack) { this.slot = slot; this.stack = stack; this.count = stack.getCount(); }
    }

    /**
     * Clicks (slot, mouse button; always the pickup mode) that move items from the player inventory into the
     * container until each slot holds the schematic's stack: a whole source stack goes in with a left click, part
     * of one with right clicks and the rest is put back. Slots with another item are left alone.
     */
    public static List<int[]> autofill(List<Target> targets, List<Source> sources) {
        List<int[]> clicks = new ArrayList<>();
        for (Target target : targets) {
            ItemStack expected = target.expected;
            if (empty(expected)) continue;
            int have = 0;
            if (!empty(target.actual)) {
                if (!stackable(expected, target.actual)) continue;
                have = target.actual.getCount();
            }
            int need = expected.getCount() - have;
            for (Source source : sources) {
                if (need <= 0) break;
                if (source.count <= 0 || !stackable(expected, source.stack)) continue;
                int take = Math.min(need, source.count);
                clicks.add(new int[] {source.slot, 0});
                if (take == source.count) {
                    clicks.add(new int[] {target.slot, 0});
                } else {
                    for (int i = 0; i < take; i++) clicks.add(new int[] {target.slot, 1});
                    clicks.add(new int[] {source.slot, 0});
                }
                source.count -= take;
                need -= take;
            }
        }
        return clicks;
    }

    /** No stack: null (unknown) or an empty stack. */
    static boolean empty(ItemStack stack) { return stack == null || stack.isEmpty(); }
}
