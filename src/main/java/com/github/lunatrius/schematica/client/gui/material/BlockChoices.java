package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.github.lunatrius.schematica.client.printer.MultiBlockPlacement;
import com.github.lunatrius.schematica.client.world.CellState;
import com.github.lunatrius.schematica.reference.Reference;

/** The blocks Replace can put into a schematic: one per item that builds a block, plus air. */
public final class BlockChoices {
    private BlockChoices() {}

    public static final class Choice {
        public final IBlockState state;
        /** Empty for air. */
        public final ItemStack icon;
        public final String name, registryName;

        Choice(IBlockState state, ItemStack icon, String name, String registryName) {
            this.state = state; this.icon = icon; this.name = name; this.registryName = registryName;
        }

        /** What the search matches. */
        public String label() { return name + " " + registryName; }
    }

    public static List<Choice> all(EntityPlayer player, String airName) {
        Map<MaterialItemKey, Choice> byItem = new LinkedHashMap<>();
        CellState.Scratch shared = CellState.Scratch.create();
        for (Block block : ForgeRegistries.BLOCKS) {
            if (block == Blocks.AIR) continue;
            Set<IBlockState> seen = new java.util.HashSet<>();
            List<IBlockState> states = new ArrayList<>();
            states.add(block.getDefaultState());
            for (int meta = 0; meta < 16; meta++) {
                try { states.add(block.getStateFromMeta(meta)); }
                catch (RuntimeException ignored) {}
            }
            for (IBlockState state : states) {
                if (!seen.add(state)) continue;
                if (MultiBlockPlacement.kind(block) != null && MultiBlockPlacement.secondary(state)) continue;
                ItemStack icon = icon(shared, state, player);
                if (icon == null || icon.isEmpty()) continue;
                MaterialItemKey key = new MaterialItemKey(icon);
                Choice existing = byItem.get(key);
                // The block the item itself places wins (stone slab over double slab, furnace over lit furnace)
                Block own = Block.getBlockFromItem(icon.getItem());
                if (existing != null && (existing.state.getBlock() == own || block != own)) continue;
                String name;
                try { name = icon.getDisplayName(); }
                catch (RuntimeException e) { name = String.valueOf(block.getRegistryName()); }
                byItem.put(key, new Choice(state, icon, name, String.valueOf(block.getRegistryName())));
            }
        }
        List<Choice> result = new ArrayList<>();
        result.add(new Choice(Blocks.AIR.getDefaultState(), ItemStack.EMPTY, airName, "minecraft:air"));
        result.addAll(byItem.values());
        return result;
    }

    /** Kinds of blocks picked from together: a slab is replaced by slabs, a door by doors, a full block by full blocks. */
    private static final Class<?>[] KINDS = {
        net.minecraft.block.BlockSlab.class, net.minecraft.block.BlockStairs.class, net.minecraft.block.BlockWall.class,
        net.minecraft.block.BlockFenceGate.class, net.minecraft.block.BlockFence.class, net.minecraft.block.BlockDoor.class,
        net.minecraft.block.BlockTrapDoor.class, net.minecraft.block.BlockButton.class, net.minecraft.block.BlockBasePressurePlate.class,
        net.minecraft.block.BlockSign.class, net.minecraft.block.BlockCarpet.class, net.minecraft.block.BlockPane.class,
        net.minecraft.block.BlockLog.class, net.minecraft.block.BlockLeaves.class, net.minecraft.block.BlockSapling.class,
        net.minecraft.block.BlockTorch.class, net.minecraft.block.BlockBed.class, net.minecraft.block.BlockBanner.class,
        net.minecraft.block.BlockRailBase.class, net.minecraft.block.BlockSkull.class, net.minecraft.block.BlockFlowerPot.class,
        net.minecraft.block.BlockBush.class,
    };

    /** The kind of a block (one of KINDS, or Block for full cubes), or null when it has none to pick from. */
    @SuppressWarnings("deprecation")
    public static Class<?> kind(IBlockState state) {
        if (state == null || state.getBlock() == Blocks.AIR) return null;
        for (Class<?> kind : KINDS) if (kind.isInstance(state.getBlock())) return kind;
        try { return state.isFullCube() ? Block.class : null; }
        catch (RuntimeException e) { return null; }
    }

    /** Whether a choice is of the same kind as a block (air always is: it removes the blocks). */
    public static boolean similar(Choice choice, Class<?> kind) {
        return kind == null || choice.state.getBlock() == Blocks.AIR || kind == kind(choice.state);
    }

    /** The item that builds a block, one of it; null when none does. */
    public static ItemStack icon(IBlockState state, EntityPlayer player) {
        return icon(CellState.Scratch.create(), state, player);
    }

    private static ItemStack icon(CellState.Scratch shared, IBlockState state, EntityPlayer player) {
        try {
            boolean tile = state.getBlock().hasTileEntity(state);
            CellState.Scratch world = tile ? CellState.Scratch.create() : shared;
            world.setBlockState(BlockPos.ORIGIN, state, 2);
            MaterialCache.BuildItems items = MaterialCache.INSTANCE.items(world, BlockPos.ORIGIN, state, player);
            if (items == null || items.isEmpty()) return null;
            ItemStack stack = items.key(0).stack();
            stack.setCount(1);
            return stack;
        } catch (RuntimeException | LinkageError error) {
            Reference.logger.debug("No block choice for {}", state, error);
            return null;
        }
    }
}
