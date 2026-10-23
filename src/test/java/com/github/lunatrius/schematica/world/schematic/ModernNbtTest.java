package com.github.lunatrius.schematica.world.schematic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.github.lunatrius.schematica.client.world.SchematicSourceData;
import com.github.lunatrius.schematica.nbt.NBTHelper;

/** Issue #1: inventories and entities of 1.13+ files keep their items when loaded into 1.12. */
public class ModernNbtTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static ItemStack stack(String snbt) throws Exception {
        NBTTagCompound converted = ModernNbt.itemTo112(JsonToNBT.getTagFromJson(snbt), 0);
        return converted == null ? ItemStack.EMPTY : new ItemStack(converted);
    }

    private static void assertItem(String snbt, String id, int damage, int count) throws Exception {
        ItemStack stack = stack(snbt);
        assertEquals(snbt, id, stack.getItem().getRegistryName().toString());
        assertEquals(snbt, damage, stack.getMetadata());
        assertEquals(snbt, count, stack.getCount());
    }

    @Test public void renamedAndBlockItemsBecomeTheir112Items() throws Exception {
        assertItem("{id:\"minecraft:red_wool\",count:5}", "minecraft:wool", 14, 5);
        assertItem("{id:\"minecraft:oak_log\",Count:3b}", "minecraft:log", 0, 3);
        assertItem("{id:\"minecraft:dark_oak_planks\",count:64}", "minecraft:planks", 5, 64);
        assertItem("{id:\"minecraft:andesite\",count:1}", "minecraft:stone", 5, 1);
        assertItem("{id:\"minecraft:ink_sac\",count:2}", "minecraft:dye", 0, 2);
        assertItem("{id:\"minecraft:bone_meal\",count:2}", "minecraft:dye", 15, 2);
        assertItem("{id:\"minecraft:lapis_lazuli\",count:2}", "minecraft:dye", 4, 2);
        assertItem("{id:\"minecraft:nether_brick\",count:9}", "minecraft:netherbrick", 0, 9);
        assertItem("{id:\"minecraft:red_bed\",count:1}", "minecraft:bed", 14, 1);
        assertItem("{id:\"minecraft:oak_door\",count:1}", "minecraft:wooden_door", 0, 1);
        assertItem("{id:\"minecraft:spruce_door\",count:1}", "minecraft:spruce_door", 0, 1);
        assertItem("{id:\"minecraft:sugar_cane\",count:1}", "minecraft:reeds", 0, 1);
        assertItem("{id:\"minecraft:charcoal\",count:1}", "minecraft:coal", 1, 1);
        assertItem("{id:\"minecraft:cooked_salmon\",count:1}", "minecraft:cooked_fish", 1, 1);
        assertItem("{id:\"minecraft:music_disc_cat\",count:1}", "minecraft:record_cat", 0, 1);
        assertItem("{id:\"minecraft:hopper\",count:99}", "minecraft:hopper", 0, 64);
        assertItem("{id:\"minecraft:cobweb\",count:1}", "minecraft:web", 0, 1);
        assertItem("{id:\"minecraft:short_grass\",count:1}", "minecraft:tallgrass", 1, 1);
        assertItem("{id:\"minecraft:oak_slab\",count:4}", "minecraft:wooden_slab", 0, 4);
        assertItem("{id:\"minecraft:cobblestone_slab\",count:4}", "minecraft:stone_slab", 3, 4);
        assertItem("{id:\"minecraft:cherry_door\",count:1}", "minecraft:birch_door", 0, 1);
    }

    @Test public void itemsWithout112CounterpartAreLeftOutAndReported() throws Exception {
        ImportReport.begin();
        try {
            assertTrue(stack("{id:\"minecraft:copper_ingot\",count:3}").isEmpty());
            assertTrue(stack("{id:\"minecraft:angler_pottery_sherd\",count:1}").isEmpty());
            assertEquals(2, ImportReport.items().removed);
            assertTrue(ImportReport.items().unknownItems.contains("minecraft:copper_ingot"));
            // a block 1.12 lacks keeps its stand-in block's item
            assertTrue(stack("{id:\"minecraft:waxed_copper_block\",count:3}").getItem() != net.minecraft.init.Items.AIR);
        } finally {
            ImportReport.end();
        }
    }

    @Test public void namesEnchantmentsDamageAndPotionsCarryOverInBothFormats() throws Exception {
        ItemStack named = stack("{id:\"minecraft:white_wool\",count:1,components:{\"minecraft:custom_name\":\"{\\\"text\\\":\\\"Filter\\\",\\\"extra\\\":[\\\" 1\\\"]}\"}}");
        assertEquals("Filter 1", named.getDisplayName());
        ItemStack nbtName = stack("{id:\"minecraft:stone\",count:1,components:{\"minecraft:custom_name\":{text:\"Hi\"}}}");
        assertEquals("Hi", nbtName.getDisplayName());
        ItemStack oldName = stack("{id:\"minecraft:stone\",Count:1b,tag:{display:{Name:\"{\\\"text\\\":\\\"Old\\\"}\"}}}");
        assertEquals("Old", oldName.getDisplayName());

        ItemStack sword = stack("{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:damage\":12,\"minecraft:enchantments\":{levels:{\"minecraft:sharpness\":5,\"minecraft:sweeping_edge\":3}}}}");
        assertEquals(12, sword.getItemDamage());
        assertEquals(5, net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.SHARPNESS, sword));
        assertEquals(3, net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.SWEEPING, sword));
        ItemStack oldSword = stack("{id:\"minecraft:iron_sword\",Count:1b,tag:{Damage:7,Enchantments:[{id:\"minecraft:knockback\",lvl:2s}]}}");
        assertEquals(7, oldSword.getItemDamage());
        assertEquals(2, net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(net.minecraft.init.Enchantments.KNOCKBACK, oldSword));

        ItemStack potion = stack("{id:\"minecraft:potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:strong_healing\"}}}");
        assertEquals("minecraft:strong_healing", potion.getTagCompound().getString("Potion"));
        ItemStack egg = stack("{id:\"minecraft:iron_golem_spawn_egg\",count:1}");
        assertEquals("minecraft:villager_golem", egg.getTagCompound().getCompoundTag("EntityTag").getString("id"));
    }

    @Test public void shulkerBoxItemsKeepTheirContents() throws Exception {
        ItemStack box = stack("{id:\"minecraft:red_shulker_box\",count:1,components:{\"minecraft:container\":["
            + "{slot:0,item:{id:\"minecraft:red_wool\",count:7}},{slot:5,item:{id:\"minecraft:copper_ingot\",count:1}}]}}");
        assertEquals("minecraft:red_shulker_box", box.getItem().getRegistryName().toString());
        net.minecraft.nbt.NBTTagList items = box.getTagCompound().getCompoundTag("BlockEntityTag").getTagList("Items", 10);
        assertEquals(1, items.tagCount());
        assertEquals("minecraft:wool", items.getCompoundTagAt(0).getString("id"));
        assertEquals(14, items.getCompoundTagAt(0).getShort("Damage"));
        ItemStack oldBox = stack("{id:\"minecraft:shulker_box\",Count:1b,tag:{BlockEntityTag:{Items:[{Slot:3b,id:\"minecraft:oak_planks\",Count:2b}]}}}");
        assertEquals(3, oldBox.getTagCompound().getCompoundTag("BlockEntityTag").getTagList("Items", 10).getCompoundTagAt(0).getByte("Slot"));
    }

    @Test public void aBarrelLoadedAsAChestKeepsItsItems() throws Exception {
        NBTTagCompound barrel = JsonToNBT.getTagFromJson("{id:\"minecraft:barrel\",x:1,y:2,z:3,CustomName:\"{\\\"text\\\":\\\"Sorted\\\"}\","
            + "Items:[{Slot:0b,id:\"minecraft:red_wool\",count:18},{Slot:26b,id:\"minecraft:bone_meal\",count:64}]}");
        TileEntity tile = NBTHelper.readTileEntityFromCompound(ModernNbt.tile(barrel, false, Blocks.CHEST.getDefaultState()));
        assertNotNull(tile);
        IInventory inventory = (IInventory) tile;
        assertEquals(14, inventory.getStackInSlot(0).getMetadata());
        assertEquals(18, inventory.getStackInSlot(0).getCount());
        assertEquals("minecraft:dye", inventory.getStackInSlot(26).getItem().getRegistryName().toString());
        assertEquals("Sorted", inventory.getName());
    }

    @Test public void itemFramesTurnToTheir112FacingAndFloorFramesAreLeftOut() throws Exception {
        NBTTagCompound frame = ModernNbt.entity(JsonToNBT.getTagFromJson("{id:\"minecraft:glow_item_frame\",Facing:3b,"
            + "Item:{id:\"minecraft:red_wool\",count:1}}"), false);
        assertEquals("minecraft:item_frame", frame.getString("id"));
        assertEquals(0, frame.getByte("Facing"));
        assertEquals("minecraft:wool", frame.getCompoundTag("Item").getString("id"));
        assertNull(ModernNbt.entity(JsonToNBT.getTagFromJson("{id:\"minecraft:item_frame\",Facing:1b}"), false));
        NBTTagCompound stand = ModernNbt.entity(JsonToNBT.getTagFromJson("{id:\"minecraft:armor_stand\","
            + "equipment:{head:{id:\"minecraft:diamond_helmet\",count:1},mainhand:{id:\"minecraft:stick\",count:1}}}"), false);
        assertEquals("minecraft:diamond_helmet", stand.getTagList("ArmorItems", 10).getCompoundTagAt(3).getString("id"));
        assertEquals("minecraft:stick", stand.getTagList("HandItems", 10).getCompoundTagAt(0).getString("id"));
    }

    @Test public void signLinesOf120SignsCarryOver() throws Exception {
        NBTTagCompound sign = ModernNbt.tile(JsonToNBT.getTagFromJson("{id:\"minecraft:sign\",x:0,y:0,z:0,"
            + "front_text:{messages:[\"{\\\"text\\\":\\\"Iron\\\"}\",\"\\\"\\\"\"]}}"), false, Blocks.STANDING_SIGN.getDefaultState());
        assertEquals("{\"text\":\"Iron\"}", sign.getString("Text1"));
        assertEquals("{\"text\":\"\"}", sign.getString("Text3"));
    }

    /** The user's real storage system (7806 stacks in containers) saved from 1.21.11 and 1.20.4. */
    @Test public void realStorageFilesKeepTheirContainerContents() throws Exception {
        for (String name : new String[] {"downgrade.litematic", "downgrade_1.20.4.litematic"}) {
            File file = new File("../reference/test-schematics/" + name);
            Assume.assumeTrue(file.isFile());
            SchematicSourceData source = SchematicSourceData.read(file);
            ItemIdMaps.Result left = ImportReport.lastItems();
            System.out.println("LEFT OUT " + name + ": " + left.removed + " " + left.unknownItems);
            com.github.lunatrius.schematica.api.ISchematic schematic = source.instantiate();
            int stacks = 0, containers = 0;
            for (TileEntity tile : schematic.getTileEntities()) {
                if (!(tile instanceof IInventory)) continue;
                containers++;
                IInventory inventory = (IInventory) tile;
                for (int i = 0; i < inventory.getSizeInventory(); i++) if (!inventory.getStackInSlot(i).isEmpty()) stacks++;
            }
            int frames = 0;
            for (net.minecraft.entity.Entity entity : schematic.getEntities()) if (entity instanceof net.minecraft.entity.item.EntityItemFrame) frames++;
            System.out.println("DOWNGRADE " + name + ": " + containers + " containers, " + stacks + " stacks, " + frames + " item frames");
            assertTrue(name + ": " + stacks + " stacks", stacks > 6000);
            assertTrue(name + ": " + frames + " frames", frames > 0);
        }
    }
}
