// Client side of printer restocking from shulker boxes carried in the inventory, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.ShulkerRestock;
import com.github.lunatrius.schematica.network.PacketHandler;
import com.github.lunatrius.schematica.network.message.MessageShulkerTake;

/**
 * When the printer, Easy Place or the container filler lacks an item that a shulker box in the inventory holds, a Plus
 * server (single player included) is asked to move a stack of it out of the box; the item arrives with the server's
 * inventory update and is used on a later pass. Without a Plus server (or with printerShulkerRestock off) nothing is
 * sent and the item is reported missing as before.
 */
public final class ShulkerRestockClient {
    /** A request waits this long for the server's inventory update before the same item may be asked for again. */
    static final long RETRY_MILLIS = 1000;
    private static final Map<String, Long> pending = new HashMap<>();

    private ShulkerRestockClient() {}

    public static boolean active() {
        return SchematicaPlus.proxy.supportsShulkerRestock && ConfigurationHandler.printerShulkerRestock;
    }

    /** Asks for a stack of the item from a carried shulker box; true when a request is sent or still pending. */
    public static boolean request(EntityPlayerSP player, ItemStack wanted) {
        if (!active() || player == null || wanted.isEmpty() || player.openContainer != player.inventoryContainer) return false;
        String key = Item.getIdFromItem(wanted.getItem()) + ":" + wanted.getMetadata();
        long now = System.currentTimeMillis();
        for (Iterator<Long> it = pending.values().iterator(); it.hasNext();) if (now - it.next() > RETRY_MILLIS) it.remove();
        if (pending.containsKey(key)) return true;
        if (!hasRoom(player, wanted)) return false;
        int[] found = find(player.inventory.mainInventory, wanted);
        if (found == null) return false;
        PacketHandler.INSTANCE.sendToServer(new MessageShulkerTake(found[0], found[1], Item.getIdFromItem(wanted.getItem()),
            wanted.getMetadata(), wanted.getMaxStackSize()));
        pending.put(key, now);
        return true;
    }

    /** A free slot, or a stack of the item with room left. */
    static boolean hasRoom(EntityPlayerSP player, ItemStack wanted) {
        if (player.inventory.getFirstEmptyStack() >= 0) return true;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack.isItemEqual(wanted) && ItemStack.areItemStackTagsEqual(stack, wanted) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    /** The inventory slot of a box holding the item and the box slot with the most of it, or null. */
    static int[] find(NonNullList<ItemStack> inventory, ItemStack wanted) {
        int[] best = null;
        int most = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack box = inventory.get(slot);
            if (!ShulkerRestock.isBox(box)) continue;
            NonNullList<ItemStack> contents = ShulkerRestock.contents(box);
            for (int inner = 0; inner < contents.size(); inner++) {
                ItemStack stack = contents.get(inner);
                if (!stack.isEmpty() && stack.isItemEqual(wanted) && stack.getCount() > most) {
                    most = stack.getCount();
                    best = new int[] {slot, inner};
                }
            }
        }
        return best;
    }
}
