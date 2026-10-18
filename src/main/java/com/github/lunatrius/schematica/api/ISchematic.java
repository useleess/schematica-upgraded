package com.github.lunatrius.schematica.api;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

public interface ISchematic {

    default SchematicOrigin getOrigin() { return SchematicOrigin.ZERO; }

    default ISchematic getRegionSchematic(String name) { return null; }

    default List<SchematicRegion> getRegions() {
        return Collections.emptyList();
    }

    default boolean containsBlock(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= getWidth() || y >= getHeight() || z >= getLength()) return false;
        if (getRegions().isEmpty()) return true;
        for (SchematicRegion region : getRegions()) {
            if (region.contains(x, y, z)) return true;
        }
        return false;
    }

    default boolean containsBlock(BlockPos pos) {
        return containsBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The state at a location within the schematic; anything outside of its bounds is air. */
    IBlockState getBlockState(int x, int y, int z);

    default IBlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Sets the state at a location; returns false (and changes nothing) outside of the schematic or for a null state. */
    boolean setBlockState(int x, int y, int z, IBlockState state);

    default boolean setBlockState(BlockPos pos, IBlockState state) {
        return setBlockState(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /** The tile entity at a location, or null. */
    TileEntity getTileEntity(BlockPos pos);

    /** All tile entities in the schematic. */
    List<TileEntity> getTileEntities();

    /** Adds or replaces the tile entity at a location (no-op out of bounds). */
    void setTileEntity(BlockPos pos, TileEntity tileEntity);

    /** Removes the tile entity at a location if there is one. */
    void removeTileEntity(BlockPos pos);

    /** All entities in the schematic. */
    List<net.minecraft.entity.Entity> getEntities();

    /** Adds an entity to the schematic if it is not a player. */
    void addEntity(Entity entity);

    void removeEntity(Entity entity);

    /** The icon that will be used to save the schematic. */
    ItemStack getIcon();

    void setIcon(ItemStack icon);

    int getWidth();

    int getLength();

    int getHeight();
}
