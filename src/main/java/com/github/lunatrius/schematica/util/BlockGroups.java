// SPDX-License-Identifier: LGPL-3.0-only
// MaLiLib BlockUtils.isInSameGroup / matchPropertiesOnly (replaceable block groups), adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockConcretePowder;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockFlowerPot;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockGlazedTerracotta;
import net.minecraft.block.BlockHardenedClay;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockNetherWart;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockRailDetector;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneLight;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.block.BlockRedstoneRepeater;
import net.minecraft.block.BlockRedstoneTorch;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockSapling;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.BlockSign;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStem;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockTripWire;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockWallSign;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

/**
 * Different blocks (enableDifferentBlocks): two blocks of one replaceable group, such as two kinds of stairs, glass or
 * wool, with the same state. In 1.12 colors and wood types are a property (variant, color, type) of one block, so those
 * properties are left out of the state; within a group a different variant is a different block. States are compared
 * in their stored form (what metadata keeps), as the world has them.
 */
public final class BlockGroups {
    public enum Result { SAME, DIFFERENT_BLOCK, WRONG_STATE, WRONG_BLOCK }

    /** enableDifferentBlocks */
    public static volatile boolean enabled;

    private static final Set<String> VARIANTS = new HashSet<>(Arrays.asList("variant", "color", "type"));
    private static final Set<String> AGE = Collections.singleton("age");
    private static final Map<Block, String> GROUPS = new IdentityHashMap<>();
    private static final String NONE = "";

    private BlockGroups() {}

    /** The replaceable group of a block, or null. */
    public static synchronized String group(Block block) {
        if (block == null) return null;
        String group = GROUPS.get(block);
        if (group == null) {
            group = findGroup(block);
            if (group == null) group = NONE;
            GROUPS.put(block, group);
        }
        return group.isEmpty() ? null : group;
    }

    @SuppressWarnings("deprecation")
    private static String findGroup(Block block) {
        if (block instanceof BlockStairs) return "stairs";
        if (block instanceof BlockSlab) return ((BlockSlab) block).isDouble() ? "double_slabs" : "slabs";
        if (block instanceof BlockWall) return "walls";
        if (block instanceof BlockFenceGate) return "fence_gates";
        if (block instanceof BlockFence) return "fences";
        if (block instanceof BlockDoor) return "doors";
        if (block instanceof BlockTrapDoor) return "trapdoors";
        if (block instanceof BlockButton) return "buttons";
        if (block instanceof BlockBasePressurePlate) return "pressure_plates";
        if (block instanceof BlockSign) return block instanceof BlockWallSign ? "wall_signs" : "standing_signs";
        if (block instanceof BlockLog) return "logs";
        if (block instanceof BlockLeaves) return "leaves";
        if (block instanceof BlockPlanks) return "planks";
        if (block instanceof BlockSapling) return "saplings";
        if (block instanceof BlockFlower) return "flowers";
        if (block instanceof BlockFlowerPot) return "flower_pots";
        if (block instanceof BlockAnvil) return "anvil";
        if (block instanceof BlockBed) return "beds";
        if (block instanceof BlockCarpet) return "wool_carpets";
        if (block instanceof BlockShulkerBox) return "shulker_boxes";
        if (block instanceof BlockGlazedTerracotta) return "glazed_terracotta";
        if (block instanceof BlockConcretePowder) return "concrete_powder";
        if (block instanceof BlockGlass || block instanceof BlockStainedGlass) return "glass";
        if (block instanceof BlockStainedGlassPane || block instanceof BlockPane && block.getDefaultState().getMaterial() == Material.GLASS) return "glass_panes";
        if (block instanceof BlockHardenedClay) return "terracotta";
        if (block instanceof BlockColored) {
            Material material = block.getDefaultState().getMaterial();
            return material == Material.CLOTH ? "wool" : material == Material.ROCK && block.getRegistryName() != null
                && block.getRegistryName().getResourcePath().equals("concrete") ? "concrete" : "terracotta";
        }
        if (block instanceof BlockOre || block instanceof BlockRedstoneOre) return "ores";
        return null;
    }

    /** The properties that hold the block's growth stage (Litematica's "age" property). */
    public static Set<String> ageProperties(Block block) {
        if (block instanceof BlockCrops || block instanceof BlockStem || block instanceof BlockNetherWart || block instanceof BlockCocoa
            || block instanceof BlockCactus || block instanceof BlockReed || block instanceof BlockFire) return AGE;
        return Collections.emptySet();
    }

    /**
     * The properties a player cannot choose in survival or that follow from the surroundings: leaf decay, sapling growth,
     * the power of buttons, plates, detector and powered rails, daylight sensors, comparators and redstone wire, extended
     * pistons, triggered dispensers, tripwire and hooks, occupied beds, fluid levels, farmland moisture and hoppers
     * disabled by redstone. Growth stages count too.
     */
    public static Set<String> survivalProperties(Block block) {
        Set<String> names = new HashSet<>(ageProperties(block));
        if (block instanceof BlockLeaves) names.addAll(Arrays.asList("decayable", "check_decay"));
        else if (block instanceof BlockSapling) names.add("stage");
        else if (block instanceof BlockButton || block instanceof BlockRailDetector || block instanceof BlockRailPowered
            || block instanceof BlockRedstoneComparator) names.add("powered");
        else if (block instanceof BlockPistonBase) names.add("extended");
        else if (block instanceof BlockDispenser) names.add("triggered");
        else if (block instanceof BlockHopper) names.add("enabled");
        else if (block instanceof BlockBasePressurePlate || block instanceof BlockDaylightDetector || block instanceof BlockRedstoneWire) {
            names.addAll(Arrays.asList("power", "powered"));
        } else if (block instanceof BlockLiquid) names.add("level");
        else if (block instanceof BlockTripWire) names.addAll(Arrays.asList("powered", "attached", "disarmed"));
        else if (block instanceof BlockTripWireHook) names.addAll(Arrays.asList("attached", "powered"));
        else if (block instanceof BlockBed) names.add("occupied");
        else if (block instanceof BlockFarmland) names.add("moisture");
        return names;
    }

    /** The lit and unlit (powered and unpowered) blocks of vanilla furnaces, lamps, torches, repeaters, comparators and redstone ore. */
    public static boolean switchedPair(Block a, Block b) {
        if (a.getClass() != b.getClass()) return false;
        return a instanceof BlockFurnace || a instanceof BlockRedstoneLight || a instanceof BlockRedstoneTorch
            || a instanceof BlockRedstoneRepeater || a instanceof BlockRedstoneComparator || a instanceof BlockRedstoneOre;
    }

    /** The state as the world stores it: only the properties its metadata keeps. */
    public static IBlockState stored(IBlockState state) {
        return Block.getStateById(Block.getStateId(state));
    }

    /** Whether two states have the same values for all properties outside the ignored ones (by name). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean sameProperties(IBlockState expected, IBlockState found, Set<String> ignored) {
        IBlockState a = stored(expected), b = stored(found);
        for (IProperty property : a.getPropertyKeys()) {
            String name = property.getName();
            if (ignored.contains(name)) continue;
            IProperty other = null;
            for (IProperty candidate : b.getPropertyKeys()) if (candidate.getName().equals(name)) { other = candidate; break; }
            if (other == null || !property.getName(a.getValue(property)).equals(other.getName(b.getValue(other)))) return false;
        }
        for (IProperty property : b.getPropertyKeys()) {
            if (ignored.contains(property.getName())) continue;
            boolean present = false;
            for (IProperty candidate : a.getPropertyKeys()) if (candidate.getName().equals(property.getName())) { present = true; break; }
            if (!present) return false;
        }
        return true;
    }

    /** ignoreSurvivalStates: the same block, or its lit/powered twin, differing at most in its survival properties. */
    public static boolean sameInSurvival(IBlockState expected, IBlockState found) {
        if (expected.getBlock() != found.getBlock() && !switchedPair(expected.getBlock(), found.getBlock())) return false;
        return sameProperties(expected, found, survivalProperties(expected.getBlock()));
    }

    /** ignoreCropAge (BlockUtils.areStatesEqualIgnoringAge): the same block, differing at most in its age. */
    public static boolean sameIgnoringAge(IBlockState expected, IBlockState found) {
        if (expected.getBlock() != found.getBlock()) return false;
        return sameProperties(expected, found, ageProperties(expected.getBlock()));
    }

    /** Whether the world block counts as the schematic block under ignoreCropAge and ignoreSurvivalStates. */
    public static boolean tolerated(IBlockState expected, IBlockState found) {
        return com.github.lunatrius.schematica.handler.VisualSettings.ignoreSurvivalStates && sameInSurvival(expected, found)
            || com.github.lunatrius.schematica.handler.VisualSettings.ignoreCropAge && sameIgnoringAge(expected, found);
    }

    /** Whether the stored forms are equal: block and metadata. */
    @SuppressWarnings("deprecation")
    public static boolean sameStored(IBlockState expected, IBlockState found) {
        return expected.getBlock() == found.getBlock()
            && expected.getBlock().getMetaFromState(expected) == found.getBlock().getMetaFromState(found);
    }

    /** Compares a schematic block with the block in the world; both are present and not air. */
    public static Result compare(IBlockState expected, IBlockState found, boolean differentBlocks) {
        if (sameStored(expected, found)) return Result.SAME;
        if (tolerated(expected, found)) return Result.SAME;
        String group = differentBlocks ? group(expected.getBlock()) : null;
        boolean sameGroup = group != null && group.equals(group(found.getBlock()));
        if (expected.getBlock() != found.getBlock() && !sameGroup) return Result.WRONG_BLOCK;
        if (!sameGroup) return Result.WRONG_STATE;
        Set<String> ignored = new HashSet<>(VARIANTS);
        if (com.github.lunatrius.schematica.handler.VisualSettings.ignoreSurvivalStates) {
            ignored.addAll(survivalProperties(expected.getBlock()));
            ignored.addAll(survivalProperties(found.getBlock()));
        }
        return sameProperties(expected, found, ignored) ? Result.DIFFERENT_BLOCK : Result.WRONG_STATE;
    }

    /** Compares states given by registry name and metadata (the verifier's form); unknown blocks compare by name only. */
    @SuppressWarnings("deprecation")
    public static Result compare(String expected, int expectedMeta, String found, int foundMeta, boolean differentBlocks) {
        Block a = com.github.lunatrius.schematica.world.schematic.BlockStates.block(expected);
        Block b = com.github.lunatrius.schematica.world.schematic.BlockStates.block(found);
        if (a == null || b == null) {
            if (!expected.equals(found)) return Result.WRONG_BLOCK;
            return expectedMeta == foundMeta ? Result.SAME : Result.WRONG_STATE;
        }
        IBlockState expectedState, foundState;
        try {
            expectedState = a.getStateFromMeta(expectedMeta);
            foundState = b.getStateFromMeta(foundMeta);
        } catch (RuntimeException error) {
            if (a != b) return Result.WRONG_BLOCK;
            return expectedMeta == foundMeta ? Result.SAME : Result.WRONG_STATE;
        }
        return compare(expectedState, foundState, differentBlocks);
    }
}
