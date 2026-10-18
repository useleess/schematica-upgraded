package com.github.lunatrius.schematica.client.gui.material;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialItemKeyTest {
    static { net.minecraft.init.Bootstrap.register(); }

    @Test public void keepsNbtVariantsSeparateWithoutDependingOnStackSizeOrMutableTags() {
        ItemStack stack = new ItemStack(new Item(), 32, 5);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setInteger("Variant", 1);
        MaterialItemKey key = new MaterialItemKey(stack);
        stack.setCount(1);
        assertEquals(key, new MaterialItemKey(stack));
        assertEquals(key.hashCode(), new MaterialItemKey(stack).hashCode());
        stack.getTagCompound().setInteger("Variant", 2);
        assertNotEquals(key, new MaterialItemKey(stack));
        ItemStack copy = key.stack();
        copy.getTagCompound().setInteger("Variant", 9);
        assertEquals(1, key.stack().getTagCompound().getInteger("Variant"));
    }

    @Test public void blocksStandingInForMissingBlocksAreTheirOwnRow() {
        MaterialItemKey stone = new MaterialItemKey(new ItemStack(net.minecraft.init.Blocks.STONE));
        MaterialItemKey deepslate = stone.withOrigin("minecraft:deepslate");
        assertNotEquals(stone, deepslate);
        assertEquals(stone, deepslate.plain());
        assertEquals(deepslate, stone.withOrigin("minecraft:deepslate"));
        assertEquals("minecraft:deepslate", deepslate.origin());
        assertEquals(deepslate, MaterialItemKey.decode(deepslate.encode()));
        assertEquals(stone, MaterialItemKey.decode(stone.encode()));
        assertNotEquals(deepslate, stone.withOrigin("minecraft:tuff"));
    }
}
