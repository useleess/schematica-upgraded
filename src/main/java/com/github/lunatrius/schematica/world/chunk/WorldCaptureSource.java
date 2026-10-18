// SPDX-License-Identifier: LGPL-3.0-only
// Block source of a normal save, by HackerRouter, 2026.
package com.github.lunatrius.schematica.world.chunk;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Reference;

/** The world being saved; block entities come from the server world when there is one (full inventories). */
public final class WorldCaptureSource implements CaptureSource {
    private final World world, serverWorld;

    public WorldCaptureSource(World world, World serverWorld) {
        this.world = world;
        this.serverWorld = serverWorld;
    }

    @Override public IBlockState state(int x, int y, int z) { return world.getBlockState(new BlockPos(x, y, z)); }

    @Override
    public TileEntity tile(int x, int y, int z, int offsetX, int offsetY, int offsetZ) throws Exception {
        BlockPos pos = new BlockPos(x, y, z);
        TileEntity tile = serverWorld.getTileEntity(pos);
        if (tile == null) tile = world.getTileEntity(pos);
        return tile == null ? null : NBTHelper.reloadTileEntity(tile, offsetX, offsetY, offsetZ);
    }

    @Override
    public List<Entity> entities(AxisAlignedBB box, int offsetX, int offsetY, int offsetZ) {
        List<Entity> copies = new ArrayList<>();
        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box)) {
            try {
                Entity copy = NBTHelper.reloadEntity(entity, offsetX, offsetY, offsetZ);
                if (copy != null) copies.add(copy);
            } catch (Exception e) {
                Reference.logger.error("Error while trying to save entity '{}'!", entity, e);
            }
        }
        return copies;
    }

    @Override
    public boolean covers(int x, int y, int z, EnumFacing side) {
        if (y < 0 || y > 255) return false;
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = world.getBlockState(pos);
        return state.isOpaqueCube() && state.getBlock().isSideSolid(state, world, pos, side);
    }
}
