// Server side of printer restocking from shulker boxes carried in the inventory, 2026.
package com.github.lunatrius.schematica.handler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

/**
 * A Plus client whose printer runs out of an item asks a Plus server to move it out of a shulker box in the player's
 * inventory: the server takes up to the requested count from one slot of the box (as stored in the box item's
 * BlockEntityTag, like a placed box would hold it) into the player's inventory, and writes the box back. Whatever does
 * not fit stays in the box. Nothing is placed in the world.
 */
public final class ShulkerRestock {
    public static final String TAG = "BlockEntityTag", ITEMS = "Items";
    /** A shulker box has 27 slots. */
    public static final int BOX_SLOTS = 27;

    private ShulkerRestock() {}

    /** Whether the stack is a single shulker box item. */
    public static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1 && Block.getBlockFromItem(stack.getItem()) instanceof BlockShulkerBox;
    }

    /** The box item's contents, 27 slots (empty when it holds nothing). */
    public static NonNullList<ItemStack> contents(ItemStack box) {
        NonNullList<ItemStack> items = NonNullList.withSize(BOX_SLOTS, ItemStack.EMPTY);
        NBTTagCompound tag = box.getSubCompound(TAG);
        if (tag != null && tag.hasKey(ITEMS, 9)) ItemStackHelper.loadAllItems(tag, items);
        return items;
    }

    /** Writes the contents back the way vanilla drops a box: no Items list (and no tag) when it is empty. */
    static void store(ItemStack box, NonNullList<ItemStack> items) {
        NBTTagCompound tag = box.getSubCompound(TAG);
        if (tag == null) tag = box.getOrCreateSubCompound(TAG);
        tag.removeTag(ITEMS);
        ItemStackHelper.saveAllItems(tag, items, false);
        if (tag.hasNoTags()) {
            box.removeSubCompound(TAG);
            if (box.getTagCompound() != null && box.getTagCompound().hasNoTags()) box.setTagCompound(null);
        }
    }

    /**
     * Moves up to count items of the item (id) and damage from slot innerSlot of the box at inventory slot boxSlot into
     * the player's inventory; returns the number moved.
     */
    public static int take(EntityPlayer player, int boxSlot, int innerSlot, int itemId, int meta, int count) {
        if (!ConfigurationHandler.shulkerRestockEnabled || player == null || player.isSpectator() || player.isDead) return 0;
        // with another container open its slots could hold the box; restocking waits until it is closed
        if (player.openContainer != player.inventoryContainer) return 0;
        if (boxSlot < 0 || boxSlot >= player.inventory.mainInventory.size() || innerSlot < 0 || innerSlot >= BOX_SLOTS || count <= 0) return 0;
        ItemStack box = player.inventory.mainInventory.get(boxSlot);
        if (!isBox(box)) return 0;
        NonNullList<ItemStack> items = contents(box);
        ItemStack inner = items.get(innerSlot);
        if (inner.isEmpty() || Item.getIdFromItem(inner.getItem()) != itemId || inner.getMetadata() != meta) return 0;
        int amount = Math.min(Math.min(count, inner.getCount()), inner.getMaxStackSize());
        ItemStack moved = inner.splitStack(amount);
        player.inventory.addItemStackToInventory(moved);
        // what did not fit goes back
        if (!moved.isEmpty()) {
            if (inner.isEmpty()) items.set(innerSlot, moved);
            else inner.grow(moved.getCount());
        }
        int taken = amount - moved.getCount();
        if (taken > 0) {
            store(box, items);
            player.inventoryContainer.detectAndSendChanges();
        }
        return taken;
    }
}
