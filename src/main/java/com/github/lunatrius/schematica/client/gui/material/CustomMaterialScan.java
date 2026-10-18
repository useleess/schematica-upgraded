// SPDX-License-Identifier: LGPL-3.0-only
// Litematica MaterialListUtils.createMaterialListFromItems, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;


/** The entries of a custom list: every item missing in full; items this game does not have are skipped. */
final class CustomMaterialScan implements MaterialScanner {
    private final List<MaterialListModel.Entry<MaterialItemKey>> result = new ArrayList<>();
    private int skipped;

    CustomMaterialScan(List<CustomMaterialListFile.Item> items, EntityPlayer player) {
        Map<MaterialItemKey, long[]> counts = new LinkedHashMap<>();
        for (CustomMaterialListFile.Item item : items) {
            net.minecraft.util.ResourceLocation location = new net.minecraft.util.ResourceLocation(item.id);
            Object object = Item.REGISTRY.containsKey(location) ? Item.REGISTRY.getObject(location) : null;
            if (!(object instanceof Item)) { skipped++; continue; }
            MaterialItemKey key = new MaterialItemKey(new ItemStack((Item) object, 1, item.damage));
            counts.computeIfAbsent(key, ignored -> new long[1])[0] += item.count;
        }
        for (Map.Entry<MaterialItemKey, long[]> counted : counts.entrySet()) {
            ItemStack stack = counted.getKey().stack();
            String registry = String.valueOf(net.minecraft.item.Item.REGISTRY.getNameForObject(stack.getItem()));
            String name;
            try { name = stack.getDisplayName(); }
            catch (RuntimeException e) { name = registry; }
            int total = (int) Math.min(Integer.MAX_VALUE, counted.getValue()[0]);
            result.add(new MaterialListModel.Entry<>(counted.getKey(), name, registry, total, total, 0, 0));
        }
        MaterialScan.updateAvailable(result, player);
    }

    @Override public void step() {}
    @Override public boolean done() { return true; }
    @Override public int percent() { return 100; }
    @Override public int skipped() { return skipped; }
    @Override public List<MaterialListModel.Entry<MaterialItemKey>> result() { return result; }
}
