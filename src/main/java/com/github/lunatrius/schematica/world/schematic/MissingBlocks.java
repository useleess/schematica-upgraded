// Blocks of a schematic file that this game does not have, by HackerRouter, 2026.
package com.github.lunatrius.schematica.world.schematic;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.github.lunatrius.schematica.reference.Reference;

/**
 * What a block of a file that this game does not have (newer versions, missing mods) is loaded as: the block the player
 * picked for it before, else the guess of {@link MissingBlockGuesses}. The block keeps the facing, half, shape... of the
 * file where the chosen block has them. Picks are saved per block name and used for every file.
 */
public final class MissingBlocks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Map<String, String> choices = Collections.emptyMap();
    private static File file;

    private MissingBlocks() {}

    /** Reads the saved picks; the file is written again whenever picks change. */
    public static synchronized void load(File file) {
        MissingBlocks.file = file;
        Map<String, String> read = new TreeMap<>();
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                JsonElement root = new JsonParser().parse(reader);
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) read.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                Reference.logger.error("Could not read the missing block choices from {}", file, e);
            }
        }
        choices = Collections.unmodifiableMap(read);
    }

    /** The saved pick (a 1.12 state string) for a block name, or null. */
    public static String choice(String name) { return choices.get(name); }

    /** Saves picks (block name to 1.12 state string, null to forget one); they apply to files read from now on. */
    public static synchronized void choose(Map<String, String> picked) {
        Map<String, String> next = new TreeMap<>(choices);
        for (Map.Entry<String, String> entry : picked.entrySet()) {
            if (entry.getValue() == null) next.remove(entry.getKey());
            else next.put(entry.getKey(), entry.getValue());
        }
        choices = Collections.unmodifiableMap(next);
        save();
    }

    private static synchronized void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String> entry : choices.entrySet()) root.addProperty(entry.getKey(), entry.getValue());
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
        } catch (IOException | RuntimeException e) {
            Reference.logger.error("Could not save the missing block choices to {}", file, e);
        }
    }

    /** The block a block of the file this game does not have becomes; never null. */
    public static IBlockState resolve(NBTTagCompound entry) {
        return resolve(entry.getString("Name"), properties(entry));
    }

    public static IBlockState resolve(String name, Map<String, String> properties) {
        String choice = choices.get(name);
        IBlockState state = choice == null ? null : chosen(choice, properties);
        if (state == null) state = target(MissingBlockGuesses.guess(name), properties);
        if (state == null) state = Blocks.STONE.getDefaultState();
        if (state.getBlock() == Blocks.AIR && "true".equals(properties.get("waterlogged"))) state = Blocks.WATER.getDefaultState();
        return state;
    }

    /** The plain form a block is shown in: a single bottom slab, the lower half of a door or plant, a bed's foot. */
    private static final Map<String, String> SHOWN = new HashMap<>();
    static {
        SHOWN.put("type", "bottom");
        SHOWN.put("half", "lower");
        SHOWN.put("part", "foot");
    }

    /** What the block of a name becomes, in its plain form, as the missing blocks screen shows it. */
    public static IBlockState replacement(String name) {
        return resolve(name, SHOWN);
    }

    /** What loading picks for a block name without the player's pick, in its plain form. */
    public static IBlockState guessed(String name) {
        IBlockState state = target(MissingBlockGuesses.guess(name), SHOWN);
        return state == null ? Blocks.STONE.getDefaultState() : state;
    }

    /** The picked 1.12 state, shaped like the file's block where the picked block has those properties. */
    private static IBlockState chosen(String choice, Map<String, String> properties) {
        IBlockState picked = BlockStates.parse(choice);
        // a block of a mod that is not installed any more: guess instead
        if (picked == null) return null;
        if (picked.getBlock() == Blocks.AIR || properties.isEmpty()) return picked;
        LegacyBlockStates.Entry entry = LegacyBlockStates.entry(choice);
        if (entry == null) return picked;
        IBlockState shaped = target(LegacyBlockStates.name(entry.modern), properties);
        return shaped == null ? picked : shaped;
    }

    /** The state of a guess or alias target: a flattened 1.13 name, air or "!" + a 1.12 state; null when unknown. */
    static IBlockState target(String target, Map<String, String> properties) {
        if (target.equals(MissingBlockGuesses.AIR)) return Blocks.AIR.getDefaultState();
        if (target.startsWith("!")) {
            NBTTagCompound tag = LegacyBlockStates.tag(target.substring(1));
            NBTTagCompound merged = tag.getCompoundTag("Properties");
            for (Map.Entry<String, String> property : properties.entrySet()) merged.setString(property.getKey(), property.getValue());
            tag.setTag("Properties", merged);
            return BlockStates.fromEntry(tag);
        }
        LegacyBlockStates.Entry entry = LegacyBlockStates.modern(LegacyBlockStates.state(target, properties));
        return entry == null ? null : BlockStates.fromIdMeta(entry.idMeta);
    }

    static Map<String, String> properties(NBTTagCompound entry) {
        Map<String, String> properties = new HashMap<>();
        NBTTagCompound tag = entry.getCompoundTag("Properties");
        for (String key : tag.getKeySet()) properties.put(key, tag.getString(key));
        return properties;
    }
}
