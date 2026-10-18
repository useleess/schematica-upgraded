// Filling item filters in placed hoppers, droppers and dispensers, after the behavior of sakura-ryoko's litematica-printer2, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockHopper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.client.world.SchematicWorld;

/**
 * A placed hopper, dropper or dispenser whose schematic block holds items (an item sorter's filter) is opened once
 * it is built and the player carries all of its items; the items go into the schematic's slots, one slot a tick, and the
 * screen is closed. A container is filled once per refresh: the client cannot see its contents without opening it.
 * Slots that hold other items are left alone.
 */
final class ContainerFiller {
    /** Ticks to wait for the screen to open, and for its contents to arrive once open. */
    static final int OPEN_TIMEOUT = 20, SETTLE = 2;
    /** The vanilla reach for opening a block, from the eyes. */
    static final double REACH = 4.5;

    private final Set<BlockPos> done = new HashSet<>();
    private World world;
    private Job job;

    private static final class Job {
        final BlockPos pos;
        final ItemStack[] wanted;
        int waited, settled, slot;
        int windowId = -1;
        Job(BlockPos pos, ItemStack[] wanted) { this.pos = pos; this.wanted = wanted; }
    }

    void clear() {
        this.done.clear();
        this.job = null;
    }

    boolean busy() { return this.job != null; }

    /** Hoppers, droppers and dispensers: the containers item filters are made of. */
    static boolean fillable(Block block) {
        return block instanceof BlockHopper || block instanceof BlockDispenser;
    }

    /** The schematic's items per slot for a built container, or null when it holds none or was filled already. */
    ItemStack[] wanted(World world, SchematicWorld placement, BlockPos local, BlockPos pos) {
        if (world != this.world) {
            this.done.clear();
            this.world = world;
        }
        if (this.done.contains(pos) || !fillable(placement.getBlockState(local).getBlock())) return null;
        TileEntity tile = placement.getTileEntity(local);
        if (!(tile instanceof IInventory)) return null;
        IInventory inventory = (IInventory) tile;
        ItemStack[] wanted = new ItemStack[inventory.getSizeInventory()];
        boolean any = false;
        for (int i = 0; i < wanted.length; i++) {
            wanted[i] = inventory.getStackInSlot(i);
            any |= !wanted[i].isEmpty();
        }
        return any ? wanted : null;
    }

    /** Whether the player carries every item of the filter; the missing ones are listed in the missing materials HUD. */
    static boolean carries(EntityPlayerSP player, ItemStack[] wanted) {
        List<ItemStack> needed = new ArrayList<>();
        for (ItemStack stack : wanted) {
            if (stack.isEmpty()) continue;
            ItemStack total = null;
            for (ItemStack other : needed) if (same(other, stack)) total = other;
            if (total == null) needed.add(stack.copy());
            else total.grow(stack.getCount());
        }
        boolean all = true;
        for (ItemStack stack : needed) {
            int have = 0;
            for (ItemStack own : player.inventory.mainInventory) if (same(own, stack)) have += own.getCount();
            if (have < stack.getCount()) {
                if (!ShulkerRestockClient.request(player, stack)) PrinterMissingMaterials.record(stack);
                all = false;
            }
        }
        return all;
    }

    /** Item and damage; names and other tags are not required (a renamed filter item is as good as a plain one). */
    static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && a.isItemEqual(b);
    }

    /** Opens the container with a click (not sneaking, so the block's screen opens instead of placing the held item). */
    boolean start(Minecraft minecraft, SchematicPrinter printer, EntityPlayerSP player, BlockPos pos, ItemStack[] wanted) {
        if (minecraft.currentScreen != null || !carries(player, wanted)) return false;
        if (player.isSneaking()) printer.syncSneaking(player, false);
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        if (minecraft.playerController.processRightClickBlock(player, minecraft.world, pos, EnumFacing.UP, hit, EnumHand.MAIN_HAND)
            != net.minecraft.util.EnumActionResult.SUCCESS) {
            this.done.add(pos);
            return false;
        }
        player.swingArm(EnumHand.MAIN_HAND);
        this.job = new Job(pos, wanted);
        return true;
    }

    /** One step of the open job: waits for the screen, fills one slot, closes when done. */
    void tick(Minecraft minecraft, EntityPlayerSP player) {
        Job job = this.job;
        Container container = player.openContainer;
        boolean open = minecraft.currentScreen instanceof GuiContainer && container != null && container != player.inventoryContainer;
        if (job.windowId < 0) {
            if (!open) {
                if (++job.waited > OPEN_TIMEOUT) finish(job, null);
                return;
            }
            job.windowId = container.windowId;
        }
        if (!open || container.windowId != job.windowId) {
            // closed by the player (or replaced by another screen): not tried again until a refresh
            finish(job, null);
            return;
        }
        if (++job.settled <= SETTLE) return;
        List<Slot> own = new ArrayList<>(), block = new ArrayList<>();
        for (Slot slot : container.inventorySlots) (slot.inventory == player.inventory ? own : block).add(slot);
        if (block.size() != job.wanted.length) {
            finish(job, player);
            return;
        }
        while (job.slot < job.wanted.length) {
            int index = job.slot++;
            if (fill(minecraft, player, container, slotFor(block, index), own, job.wanted[index])) return;
        }
        finish(job, player);
    }

    private static Slot slotFor(List<Slot> block, int index) {
        for (Slot slot : block) if (slot.getSlotIndex() == index) return slot;
        return block.get(index);
    }

    /** Moves the wanted count into the slot; true when it clicked (the rest of the slots follow on later ticks). */
    private static boolean fill(Minecraft minecraft, EntityPlayerSP player, Container container, Slot target, List<Slot> own, ItemStack wanted) {
        if (wanted.isEmpty()) return false;
        ItemStack current = target.getStack();
        if (!current.isEmpty() && !same(current, wanted)) return false;
        int need = wanted.getCount() - current.getCount();
        if (need <= 0) return false;
        boolean clicked = false;
        for (Slot source : own) {
            if (need <= 0) break;
            if (!same(source.getStack(), wanted)) continue;
            click(minecraft, player, container, source.slotNumber, 0);
            clicked = true;
            ItemStack held = player.inventory.getItemStack();
            if (held.getCount() <= need) {
                need -= held.getCount();
                click(minecraft, player, container, target.slotNumber, 0);
            } else {
                for (; need > 0; need--) click(minecraft, player, container, target.slotNumber, 1);
            }
            // the rest goes back where it came from
            if (!player.inventory.getItemStack().isEmpty()) click(minecraft, player, container, source.slotNumber, 0);
        }
        return clicked;
    }

    private static void click(Minecraft minecraft, EntityPlayerSP player, Container container, int slot, int button) {
        minecraft.playerController.windowClick(container.windowId, slot, button, ClickType.PICKUP, player);
    }

    private void finish(Job job, EntityPlayerSP player) {
        this.done.add(job.pos);
        this.job = null;
        if (player != null) player.closeScreen();
    }
}
