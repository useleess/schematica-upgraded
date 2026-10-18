package com.github.lunatrius.schematica.world.schematic;

import java.io.File;
import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.reference.Reference;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

public final class SchematicUtil {

    private static final ItemStack DEFAULT_ICON = new ItemStack(Blocks.GRASS);

    public static NBTTagCompound readTagCompoundFromFile(File file) throws IOException {
        try {
            return LitematicaNBTReader.readFromFile(file);
        } finally {
        }
    }

    public static ItemStack getIconFromName(String iconName) {
        String name = "";
        int damage = 0;

        String[] parts = iconName.split(",");
        if (parts.length >= 1) {
            name = parts[0];
            if (parts.length >= 2) {
                try {
                    damage = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        ResourceLocation location = new ResourceLocation(name);
        Block block = Block.REGISTRY.containsKey(location) ? Block.REGISTRY.getObject(location) : null;
        if (block != null) return new ItemStack(block, 1, damage);

        Item item = Item.REGISTRY.containsKey(location) ? Item.REGISTRY.getObject(location) : null;
        if (item != null) return new ItemStack(item, 1, damage);

        return DEFAULT_ICON.copy();
    }

    public static ItemStack getIconFromNBT(NBTTagCompound tagCompound) {
        ItemStack icon = DEFAULT_ICON.copy();

        if (tagCompound != null && tagCompound.hasKey(Names.NBT.ICON)) {
            ItemStack read = new ItemStack(LegacyNbt.itemTo112(tagCompound.getCompoundTag(Names.NBT.ICON)));
            if (!read.isEmpty()) icon = read;
        }

        return icon;
    }

    public static ItemStack getIconFromFile(File file) {
        try {
            final String name = file.getName().toLowerCase();
            if (name.endsWith(".litematic")) {
                // .litematic files contain TAG_Long_Array which vanilla NBT reader
                // can't handle — use our custom reader, then clean up
                NBTTagCompound tag = LitematicaNBTReader.readFromFile(file);
                return getIconFromNBT(tag);
            }
            return getIconFromNBT(readTagCompoundFromFile(file));
        } catch (Exception e) {
            Reference.logger.error("Failed to read schematic icon!", e);
        }

        return DEFAULT_ICON.copy();
    }
}
