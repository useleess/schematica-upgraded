// SPDX-License-Identifier: LGPL-3.0-only
// Litematica MaterialCache, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFlowerPot;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;

import com.github.lunatrius.schematica.client.printer.MultiBlockPlacement;

/**
 * The items needed to build a block. Results are cached per block state, except for blocks with a tile entity, whose
 * item may depend on that tile entity (flower pots, skulls, banners).
 */
public final class MaterialCache {
    public static final MaterialCache INSTANCE = new MaterialCache();

    /** The items of one block, with their counts; empty when the block needs no item (door tops, portals, flowing fluids). */
    public static final class BuildItems {
        public static final BuildItems NONE = new BuildItems(Collections.<MaterialItemKey>emptyList(), new int[0]);
        private final List<MaterialItemKey> keys;
        private final int[] counts;
        private final int hash;

        BuildItems(List<MaterialItemKey> keys, int[] counts) {
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.counts = counts.clone();
            hash = this.keys.hashCode() * 31 + Arrays.hashCode(this.counts);
        }

        static BuildItems of(ItemStack... stacks) {
            List<MaterialItemKey> keys = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) continue;
                MaterialItemKey key = new MaterialItemKey(stack);
                int index = keys.indexOf(key);
                if (index >= 0) counts.set(index, counts.get(index) + stack.getCount());
                else { keys.add(key); counts.add(stack.getCount()); }
            }
            int[] values = new int[counts.size()];
            for (int i = 0; i < values.length; i++) values[i] = counts.get(i);
            return keys.isEmpty() ? NONE : new BuildItems(keys, values);
        }

        public boolean isEmpty() { return keys.isEmpty(); }
        public int size() { return keys.size(); }
        public MaterialItemKey key(int index) { return keys.get(index); }
        public int count(int index) { return counts[index]; }

        @Override public boolean equals(Object object) {
            return object instanceof BuildItems && keys.equals(((BuildItems) object).keys) && Arrays.equals(counts, ((BuildItems) object).counts);
        }
        @Override public int hashCode() { return hash; }
    }

    private final Map<IBlockState, BuildItems> cache = new HashMap<>();

    private MaterialCache() {}

    public synchronized void clear() { cache.clear(); }

    /** Returns null when the block has no item to build it with. */
    public BuildItems items(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        boolean cacheable;
        try { cacheable = !state.getBlock().hasTileEntity(state); }
        catch (RuntimeException | LinkageError error) { cacheable = false; }
        if (cacheable) {
            synchronized (this) {
                BuildItems cached = cache.get(state);
                if (cached != null) return cached == UNKNOWN ? null : cached;
            }
        }
        BuildItems items = resolve(world, pos, state, player);
        if (cacheable) {
            synchronized (this) { cache.put(state, items == null ? UNKNOWN : items); }
        }
        return items;
    }

    private static final BuildItems UNKNOWN = new BuildItems(Collections.<MaterialItemKey>emptyList(), new int[] {-1});

    @SuppressWarnings("deprecation")
    private static BuildItems resolve(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        Block block = state.getBlock();
        TileEntity tile = world == null ? null : world.getTileEntity(pos);
        if (block == Blocks.PISTON_HEAD || block == Blocks.PISTON_EXTENSION || block == Blocks.PORTAL || block == Blocks.END_PORTAL
            || block == Blocks.END_GATEWAY || block == Blocks.FIRE) return BuildItems.NONE;
        if (MultiBlockPlacement.kind(block) != null && MultiBlockPlacement.secondary(state)) return BuildItems.NONE;
        if (block == Blocks.FARMLAND || block == Blocks.GRASS_PATH) return BuildItems.of(new ItemStack(Blocks.DIRT));
        if (block instanceof BlockLiquid) {
            if (state.getValue(BlockLiquid.LEVEL) != 0) return BuildItems.NONE;
            return BuildItems.of(new ItemStack(state.getMaterial() == Material.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET));
        }
        if (block instanceof IFluidBlock) return fluid((IFluidBlock) block, world, pos);
        if (block == Blocks.FLOWER_POT) {
            ItemStack plant = tile instanceof TileEntityFlowerPot ? ((TileEntityFlowerPot) tile).getFlowerItemStack() : ItemStack.EMPTY;
            return BuildItems.of(new ItemStack(Items.FLOWER_POT), plant);
        }
        ItemStack stack = block.getPickBlock(state, new RayTraceResult(new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), EnumFacing.UP, pos),
            world, pos, player);
        if (stack == null || stack.isEmpty()) return null;
        stack = stack.copy();
        stack.setCount(1);
        if (block instanceof BlockSlab && ((BlockSlab) block).isDouble()) stack.setCount(2);
        else if (block instanceof BlockSnow) stack.setCount(state.getValue(BlockSnow.LAYERS));
        return BuildItems.of(stack);
    }

    private static BuildItems fluid(IFluidBlock block, World world, BlockPos pos) {
        Fluid fluid = block.getFluid();
        if (fluid == null || world == null || !block.canDrain(world, pos)) return BuildItems.NONE;
        ItemStack bucket = FluidUtil.getFilledBucket(new FluidStack(fluid, Fluid.BUCKET_VOLUME));
        return bucket.isEmpty() ? null : BuildItems.of(bucket);
    }
}
