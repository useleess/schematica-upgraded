package com.github.lunatrius.schematica.client.gui.material;

import java.util.Objects;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class MaterialItemKey {
    private static final String FROM = "from=";
    private final Item item;
    private final int damage;
    private final NBTTagCompound tag;
    /** For blocks that stand in for a block this game does not have: that block's name, so they get a row of their own. */
    private final String origin;
    private final int hash;

    public MaterialItemKey(ItemStack stack) {
        this(stack, null);
    }

    private MaterialItemKey(ItemStack stack, String origin) {
        item = Objects.requireNonNull(stack.getItem());
        damage = stack.getMetadata();
        tag = stack.hasTagCompound() ? (NBTTagCompound) stack.getTagCompound().copy() : null;
        this.origin = origin;
        hash = Objects.hash(item, damage, tag, origin);
    }

    /** The same item counted for blocks that stand in for origin (null: blocks of the file). */
    public MaterialItemKey withOrigin(String origin) {
        return Objects.equals(origin, this.origin) ? this : new MaterialItemKey(stack(), origin);
    }

    /** The block of the file this game does not have that these blocks stand in for, or null. */
    public String origin() { return origin; }

    /** The key of the item itself, as the inventory holds it. */
    public MaterialItemKey plain() { return withOrigin(null); }

    public ItemStack stack() {
        ItemStack stack = new ItemStack(item, 1, damage);
        if (tag != null) stack.setTagCompound((NBTTagCompound) tag.copy());
        return stack;
    }

    /** "modid:name@damage", with "#" and the NBT when there is one; null for unregistered items. */
    public String encode() {
        Object name = Item.REGISTRY.getNameForObject(item);
        if (name == null) return null;
        return (origin == null ? "" : FROM + origin + "|") + name + "@" + damage + (tag == null ? "" : "#" + tag);
    }

    public static MaterialItemKey decode(String value) {
        if (value == null) return null;
        String origin = null;
        if (value.startsWith(FROM) && value.indexOf('|') > FROM.length()) {
            origin = value.substring(FROM.length(), value.indexOf('|'));
            value = value.substring(value.indexOf('|') + 1);
        }
        try {
            int hash = value.indexOf('#'), at = value.lastIndexOf('@', hash < 0 ? value.length() : hash);
            if (at < 0) return null;
            net.minecraft.util.ResourceLocation location = new net.minecraft.util.ResourceLocation(value.substring(0, at));
            Item item = Item.REGISTRY.containsKey(location) ? Item.REGISTRY.getObject(location) : null;
            if (item == null) return null;
            ItemStack stack = new ItemStack(item, 1, Integer.parseInt(value.substring(at + 1, hash < 0 ? value.length() : hash)));
            if (hash >= 0) {
                net.minecraft.nbt.NBTBase tag = net.minecraft.nbt.JsonToNBT.getTagFromJson(value.substring(hash + 1));
                if (tag instanceof NBTTagCompound) stack.setTagCompound((NBTTagCompound) tag);
            }
            return new MaterialItemKey(stack, origin);
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof MaterialItemKey)) return false;
        MaterialItemKey other = (MaterialItemKey) object;
        return item == other.item && damage == other.damage && Objects.equals(tag, other.tag) && Objects.equals(origin, other.origin);
    }

    @Override
    public int hashCode() { return hash; }
}
