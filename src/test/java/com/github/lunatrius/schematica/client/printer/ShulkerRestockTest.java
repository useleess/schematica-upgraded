package com.github.lunatrius.schematica.client.printer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

import org.junit.BeforeClass;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import com.mojang.authlib.GameProfile;

import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.ShulkerRestock;
import com.github.lunatrius.schematica.network.message.MessageCapabilities;
import com.github.lunatrius.schematica.network.message.MessageShulkerTake;

import static org.junit.Assert.*;

public class ShulkerRestockTest {
    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        // ConfigurationHandler's defaults need a proxy (forkEvery = 1 gives each test class its own JVM)
        if (com.github.lunatrius.schematica.SchematicaPlus.proxy == null) {
            com.github.lunatrius.schematica.SchematicaPlus.proxy = new com.github.lunatrius.schematica.proxy.ServerProxy() {
                @Override public java.io.File getDataDirectory() { return new java.io.File("build/tmp"); }
            };
        }
    }

    private static final class Player extends EntityPlayer {
        Player(SimWorld world) { super(world, new GameProfile(java.util.UUID.randomUUID(), "shulker")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    private static ItemStack box(ItemStack... slots) {
        ItemStack box = new ItemStack(Blocks.PURPLE_SHULKER_BOX);
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < slots.length; i++) items.set(i, slots[i]);
        NBTTagCompound tag = box.getOrCreateSubCompound(ShulkerRestock.TAG);
        ItemStackHelper.saveAllItems(tag, items, false);
        return box;
    }

    private static int id(Item item) { return Item.getIdFromItem(item); }

    @Test public void takesAStackOutOfTheBox() {
        ConfigurationHandler.shulkerRestockEnabled = true;
        EntityPlayer player = new Player(new SimWorld());
        player.inventory.mainInventory.set(20, box(ItemStack.EMPTY, new ItemStack(Blocks.STONE, 64), new ItemStack(Blocks.STONE, 30)));
        int stone = id(Item.getItemFromBlock(Blocks.STONE));
        assertEquals(64, ShulkerRestock.take(player, 20, 1, stone, 0, 64));
        assertEquals(64, player.inventory.mainInventory.get(0).getCount());
        NonNullList<ItemStack> left = ShulkerRestock.contents(player.inventory.mainInventory.get(20));
        assertTrue(left.get(1).isEmpty());
        assertEquals(30, left.get(2).getCount());
        // the client finds the remaining stone in slot 2
        assertArrayEquals(new int[] {20, 2}, ShulkerRestockClient.find(player.inventory.mainInventory, new ItemStack(Blocks.STONE)));
        assertEquals(30, ShulkerRestock.take(player, 20, 2, stone, 0, 64));
        // an emptied box drops its tag, like a broken empty box
        assertNull(player.inventory.mainInventory.get(20).getTagCompound());
        assertNull(ShulkerRestockClient.find(player.inventory.mainInventory, new ItemStack(Blocks.STONE)));
    }

    @Test public void refusesWrongRequests() {
        ConfigurationHandler.shulkerRestockEnabled = true;
        EntityPlayer player = new Player(new SimWorld());
        player.inventory.mainInventory.set(5, box(new ItemStack(Items.DYE, 10, 4)));
        player.inventory.mainInventory.set(6, new ItemStack(Blocks.CHEST));
        assertEquals(0, ShulkerRestock.take(player, 5, 0, id(Items.DYE), 3, 10)); // other damage
        assertEquals(0, ShulkerRestock.take(player, 5, 1, id(Items.DYE), 4, 10)); // empty slot
        assertEquals(0, ShulkerRestock.take(player, 6, 0, id(Items.DYE), 4, 10)); // not a box
        assertEquals(0, ShulkerRestock.take(player, 99, 0, id(Items.DYE), 4, 10)); // no such slot
        ConfigurationHandler.shulkerRestockEnabled = false;
        assertEquals(0, ShulkerRestock.take(player, 5, 0, id(Items.DYE), 4, 10));
        ConfigurationHandler.shulkerRestockEnabled = true;
        assertEquals(10, ShulkerRestock.take(player, 5, 0, id(Items.DYE), 4, 64));
    }

    @Test public void whatDoesNotFitStaysInTheBox() {
        ConfigurationHandler.shulkerRestockEnabled = true;
        EntityPlayer player = new Player(new SimWorld());
        for (int i = 0; i < 36; i++) player.inventory.mainInventory.set(i, new ItemStack(Blocks.DIRT, 64));
        player.inventory.mainInventory.set(3, new ItemStack(Blocks.STONE, 60));
        player.inventory.mainInventory.set(4, box(new ItemStack(Blocks.STONE, 64)));
        assertEquals(4, ShulkerRestock.take(player, 4, 0, id(Item.getItemFromBlock(Blocks.STONE)), 0, 64));
        assertEquals(64, player.inventory.mainInventory.get(3).getCount());
        assertEquals(60, ShulkerRestock.contents(player.inventory.mainInventory.get(4)).get(0).getCount());
    }

    @Test public void messagesRoundTrip() {
        MessageShulkerTake take = new MessageShulkerTake(35, 26, 4096, 15, 64);
        ByteBuf buffer = Unpooled.buffer();
        take.toBytes(buffer);
        MessageShulkerTake read = new MessageShulkerTake();
        read.fromBytes(buffer);
        assertEquals(35, read.boxSlot); assertEquals(26, read.innerSlot); assertEquals(4096, read.itemId);
        assertEquals(15, read.meta); assertEquals(64, read.count);

        MessageCapabilities caps = new MessageCapabilities(true, true, true);
        caps.supportsShulkerRestock = true;
        buffer = Unpooled.buffer();
        caps.toBytes(buffer);
        MessageCapabilities back = new MessageCapabilities();
        back.fromBytes(buffer);
        assertTrue(back.supportsShulkerRestock);
        // a server without it (older Plus) sends no flag
        buffer = Unpooled.buffer();
        for (int i = 0; i < 7; i++) buffer.writeBoolean(true);
        buffer.writeFloat(16);
        back = new MessageCapabilities();
        back.fromBytes(buffer);
        assertFalse(back.supportsShulkerRestock);
    }
}
