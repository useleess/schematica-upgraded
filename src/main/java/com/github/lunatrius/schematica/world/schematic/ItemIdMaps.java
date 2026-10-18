// Item ids of stored stacks, by HackerRouter, 2026; reduced for 1.12.2.
package com.github.lunatrius.schematica.world.schematic;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 1.7.10 Alpha files store numeric item ids. 1.12.2 keeps the fixed ids of vanilla items, which LegacyNbt turns into
 * registry names while reading, and stores registry names itself, so no id maps are needed here. Only the record of
 * what a decode could not resolve remains.
 */
public final class ItemIdMaps {
    private ItemIdMaps() {}

    /** What a decode did with the item ids of a file. */
    public static final class Result {
        public int removed;
        public final Set<String> unknownItems = new LinkedHashSet<>();
        public String translatedWith;
    }
}
