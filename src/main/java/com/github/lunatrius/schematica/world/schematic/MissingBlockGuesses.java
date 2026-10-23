// Closest 1.12 blocks for blocks of newer versions, by HackerRouter, 2026.
package com.github.lunatrius.schematica.world.schematic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out, from its name only, which block a block of a newer Minecraft version becomes in 1.12: renames first
 * (aliases, the same block), then the listed closest block, name rules (wood types, stone kinds, weathering), shapes
 * (stairs, slabs, walls... of the closest material) and finally stone, or air for small non-solid things. The answer
 * is a flattened 1.13 name of the conversion table, "minecraft:air", or "!" + a 1.12 state string.
 */
public final class MissingBlockGuesses {
    private static final String RESOURCE = "/assets/schematica/data/missing_blocks.txt";
    public static final String AIR = "minecraft:air", STONE = "minecraft:stone";
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    /** Shapes worked out from the material, longest suffix first, with the 1.13 block used when the material has none. */
    private static final String[][] SHAPES = {
        {"wall_hanging_sign", "wall_sign"}, {"hanging_sign", "sign"}, {"pressure_plate", "stone_pressure_plate"},
        {"fence_gate", "oak_fence_gate"}, {"wall_sign", "wall_sign"}, {"trapdoor", "oak_trapdoor"}, {"stairs", "stone_brick_stairs"},
        {"button", "stone_button"}, {"carpet", "white_carpet"}, {"shelf", "bookshelf"}, {"fence", "oak_fence"},
        {"slab", "stone_slab"}, {"wall", "cobblestone_wall"}, {"door", "oak_door"}, {"sign", "sign"}, {"pane", "glass_pane"},
    };
    /** Parts of names of small things that are no full block: guessed as air when nothing else is known. */
    private static final String[] NOT_SOLID = {"torch", "flower", "sapling", "button", "plate", "rail", "carpet", "sign",
        "banner", "vine", "roots", "grass", "fern", "bush", "sprout", "lichen", "candle", "pickle", "coral", "kelp",
        "seagrass", "fungus", "petals", "litter", "egg", "head", "skull", "propagule", "blossom", "bud", "crop", "plant",
        "dripleaf", "moss", "lantern", "chain", "rod", "frame", "fire", "portal", "air", "light", "statue"};

    private static Map<String, String> aliases, blocks;
    private static List<String[]> rules;

    private MissingBlockGuesses() {}

    private static synchronized void load() {
        if (aliases != null) return;
        Map<String, String> alias = new HashMap<>(), block = new HashMap<>();
        List<String[]> rule = new ArrayList<>();
        try (InputStream stream = MissingBlockGuesses.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\t", -1);
                // a rule without a replacement removes the part
                if (parts.length == 2 && parts[0].equals("rule")) parts = new String[] {parts[0], parts[1], ""};
                if (parts.length != 3) throw new IOException("Invalid line: " + line);
                switch (parts[0]) {
                    case "alias": alias.put(full(parts[1]), target(parts[2])); break;
                    case "block": block.put(full(parts[1]), target(parts[2])); break;
                    case "rule": rule.add(new String[] {parts[1], parts[2]}); break;
                    default: throw new IOException("Invalid line: " + line);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not load the missing block table", e);
        }
        for (String color : COLORS) {
            if (!color.equals("red")) block.putIfAbsent("minecraft:" + color + "_bed", "minecraft:red_bed");
            if (!color.equals("white")) {
                block.putIfAbsent("minecraft:" + color + "_banner", "minecraft:white_banner");
                block.putIfAbsent("minecraft:" + color + "_wall_banner", "minecraft:white_wall_banner");
            }
            block.putIfAbsent("minecraft:" + color + "_candle", AIR);
            block.putIfAbsent("minecraft:" + color + "_candle_cake", "minecraft:cake");
        }
        block.putIfAbsent("minecraft:candle", AIR);
        aliases = alias;
        blocks = block;
        rules = rule;
    }

    private static String full(String name) { return name.indexOf(':') < 0 ? "minecraft:" + name : name; }

    private static String target(String target) {
        if (target.startsWith("!")) return target;
        return target.equals("air") ? AIR : full(target);
    }

    /** The table name a renamed block (same block, newer name) stands for, or null. */
    public static String alias(String name) {
        load();
        return aliases.get(full(name));
    }

    /** Whether 1.12 has the block under this flattened name, directly or as a rename. */
    static boolean known(String name) {
        return name.equals(AIR) || name.startsWith("!") || LegacyBlockStates.hasModern(name) || alias(name) != null;
    }

    /** Whether the table or its rules know a block of this name (unlike {@link #guess}, which falls back to stone or air). */
    static boolean resolvable(String name) {
        load();
        return find(full(name), 0, new HashSet<>()) != null;
    }

    /** The closest 1.12 block of a block name 1.12 does not have; never null. */
    public static String guess(String name) {
        load();
        String found = find(full(name), 0, new HashSet<>());
        if (found != null) return found;
        String path = path(full(name));
        if (path.startsWith("potted_")) return "!minecraft:flower_pot";
        for (String part : NOT_SOLID) if (path.contains(part)) return AIR;
        return STONE;
    }

    private static String path(String name) { return name.substring(name.indexOf(':') + 1); }

    private static String find(String name, int depth, Set<String> visited) {
        if (depth > 10 || !visited.add(name)) return null;
        String alias = aliases.get(name);
        if (alias != null) return alias;
        if (known(name)) return name;
        String listed = blocks.get(name);
        if (listed != null) return known(listed) ? listed : find(listed, depth + 1, visited);
        String namespace = name.substring(0, name.indexOf(':') + 1), path = path(name);
        for (String[] rule : rules) {
            int at = path.indexOf(rule[0]);
            if (at < 0) continue;
            String next = path.substring(0, at) + rule[1] + path.substring(at + rule[0].length());
            if (next.isEmpty() || next.startsWith("_") || next.endsWith("_")) continue;
            String found = find(namespace + next, depth + 1, visited);
            if (found != null) return found;
        }
        for (String[] shape : SHAPES) {
            String suffix = "_" + shape[0];
            if (!path.endsWith(suffix) || path.length() == suffix.length()) continue;
            String base = path.substring(0, path.length() - suffix.length());
            for (String material : new String[] {base, base + "s", base + "_block", base + "_planks", base + "_bricks"}) {
                String full = find("minecraft:" + material, depth + 1, visited);
                if (full == null || full.equals(AIR) || full.startsWith("!")) continue;
                String shaped = shapeOf(full, shape[0]);
                if (shaped != null) return shaped;
            }
            return "minecraft:" + shape[1];
        }
        return null;
    }

    /** The block of a shape made of a full block's material (bricks -> brick_stairs, oak_planks -> oak_fence), or null. */
    private static String shapeOf(String full, String shape) {
        String path = path(full);
        List<String> names = new ArrayList<>();
        boolean planks = path.endsWith("_planks");
        if (planks) names.add(path.substring(0, path.length() - 7));
        if (path.endsWith("_block")) names.add(path.substring(0, path.length() - 6));
        if (path.endsWith("s")) names.add(path.substring(0, path.length() - 1));
        names.add(path);
        // wood shapes 1.12 has only in oak (trapdoors, buttons, pressure plates) are oak ones
        if (planks) names.add("oak");
        for (String material : names) {
            String shaped = "minecraft:" + material + "_" + shape;
            if (LegacyBlockStates.hasModern(shaped) || alias(shaped) != null) return shaped;
        }
        return null;
    }

    /** "minecraft:resin_brick_stairs" -> "Resin Brick Stairs" (other namespaces in brackets). */
    public static String displayName(String name) {
        String path = path(full(name));
        StringBuilder result = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        String namespace = full(name).substring(0, full(name).indexOf(':'));
        return namespace.equals("minecraft") ? result.toString() : result + " (" + namespace + ")";
    }
}
