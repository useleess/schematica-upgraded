package com.github.lunatrius.schematica.world.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.Optional;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** Conversions between block states, their 1.12 state strings, palette entries and flattened 1.13+ names. */
public final class BlockStates {
    private static final String AIR = "minecraft:air";

    private BlockStates() {}

    /** The registered block of a name, or null (the registry itself answers air for unknown names). */
    public static Block block(String name) {
        ResourceLocation location = new ResourceLocation(name);
        return Block.REGISTRY.containsKey(location) ? Block.REGISTRY.getObject(location) : null;
    }

    public static String name(Block block) {
        ResourceLocation key = Block.REGISTRY.getNameForObject(block);
        return key == null ? AIR : key.toString();
    }

    public static boolean isAir(IBlockState state) {
        return state == null || state.getBlock() == Blocks.AIR;
    }

    /** name[key=value,...] of a state, keys sorted: the form 1.12.2 Litematica writes. */
    public static String toString(IBlockState state) {
        Map<String, String> properties = properties(state);
        return LegacyBlockStates.state(name(state.getBlock()), properties);
    }

    /** A palette entry ({Name, Properties}) of a state. */
    public static NBTTagCompound toEntry(IBlockState state) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("Name", name(state.getBlock()));
        Map<String, String> properties = properties(state);
        if (!properties.isEmpty()) {
            NBTTagCompound tag = new NBTTagCompound();
            List<String> keys = new ArrayList<>(properties.keySet());
            Collections.sort(keys);
            for (String key : keys) tag.setString(key, properties.get(key));
            entry.setTag("Properties", tag);
        }
        return entry;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, String> properties(IBlockState state) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            IProperty property = entry.getKey();
            result.put(property.getName(), property.getName(entry.getValue()));
        }
        return result;
    }

    /** The state of a 1.12 palette entry; unknown blocks are null, unknown properties or values keep the default. */
    public static IBlockState fromEntry(NBTTagCompound entry) {
        Block block = block(entry.getString("Name"));
        if (block == null) return null;
        IBlockState state = block.getDefaultState();
        NBTTagCompound properties = entry.getCompoundTag("Properties");
        for (String key : properties.getKeySet()) {
            state = withProperty(state, key, properties.getString(key));
        }
        return state;
    }

    /** The state of a name[key=value,...] string in 1.12 form, or null for unknown blocks. */
    public static IBlockState parse(String state) {
        return fromEntry(LegacyBlockStates.tag(state));
    }

    /** The 1.12 state a palette entry of a flattened (1.13+) file stands for, or null when it is unknown. */
    public static IBlockState fromModern(NBTTagCompound entry) {
        String modern = LegacyBlockStates.state(entry);
        LegacyBlockStates.Entry known = LegacyBlockStates.modern(modern);
        if (known != null) {
            IBlockState state = fromIdMeta(known.idMeta);
            if (state != null) return state;
        }
        // the same block under a name newer than the table's (oak_wood, spawner, short_grass...)
        String alias = MissingBlockGuesses.alias(entry.getString("Name"));
        if (alias != null) {
            IBlockState state = MissingBlocks.target(alias, MissingBlocks.properties(entry));
            if (state != null) return state;
        }
        return fromEntry(entry);
    }

    /** The state of any state string: 1.12 form first, then flattened 1.13+ form; null for unknown blocks. */
    public static IBlockState fromStateString(String state) {
        if (LegacyBlockStates.entry(state) != null) {
            IBlockState known = parse(state);
            if (known != null) return known;
        }
        return fromModern(LegacyBlockStates.tag(state));
    }

    /** A state by the numeric id << 4 | metadata of the 1.12 registry. */
    @SuppressWarnings("deprecation")
    public static IBlockState fromIdMeta(int idMeta) {
        Block block = Block.getBlockById(idMeta >> 4);
        if (block == Blocks.AIR && idMeta >> 4 != 0) return null;
        return block.getStateFromMeta(idMeta & 15);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState withProperty(IBlockState state, String key, String value) {
        for (IProperty property : state.getPropertyKeys()) {
            if (!property.getName().equals(key)) continue;
            Optional parsed = property.parseValue(value);
            if (parsed.isPresent()) return state.withProperty(property, (Comparable) parsed.get());
            return state;
        }
        return state;
    }
}
