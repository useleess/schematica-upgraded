package com.github.lunatrius.schematica.world.storage;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTConversionException;
import com.github.lunatrius.schematica.nbt.NBTHelper;

public final class SchematicCopies {
    private SchematicCopies() {}

    public static boolean independent(ISchematic source) {
        for (SchematicRegion region : source.getRegions()) if (source.getRegionSchematic(region.name) != null) return true;
        return false;
    }

    public static Schematic copy(ISchematic source) {
        boolean independent = independent(source);
        ItemStack icon = source.getIcon() == null ? null : source.getIcon().copy();
        Schematic result = independent ? new MultiRegionSchematic(icon, source.getWidth(), source.getHeight(), source.getLength())
            : new Schematic(icon, source.getWidth(), source.getHeight(), source.getLength());
        result.setOrigin(source.getOrigin());
        result.setRegions(source.getRegions());
        overlay(source, result, 0, 0, 0, true);
        if (independent) for (SchematicRegion region : source.getRegions()) {
            ISchematic contents = source.getRegionSchematic(region.name);
            if (contents != null) ((MultiRegionSchematic) result).addRegion(region, copy(contents));
        }
        return result;
    }

    /** The part of a schematic inside a region box, as a schematic of the box size. */
    public static Schematic extract(ISchematic source, SchematicRegion box) {
        Schematic part = new Schematic(source.getIcon() == null ? null : source.getIcon().copy(),
            box.maxX - box.minX + 1, box.maxY - box.minY + 1, box.maxZ - box.minZ + 1);
        for (int y = 0; y < part.getHeight(); y++) for (int z = 0; z < part.getLength(); z++) for (int x = 0; x < part.getWidth(); x++) {
            Schematic.set(part, x, y, z, source.getBlockState(box.minX + x, box.minY + y, box.minZ + z),
                Schematic.originOf(source, box.minX + x, box.minY + y, box.minZ + z));
        }
        for (TileEntity tile : source.getTileEntities()) {
            BlockPos pos = tile.getPos();
            if (!box.contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            TileEntity copy = tile(tile, box.minX, box.minY, box.minZ);
            part.setTileEntity(copy.getPos(), copy);
        }
        for (Entity entity : source.getEntities()) {
            if (box.contains((int) Math.floor(entity.posX), (int) Math.floor(entity.posY), (int) Math.floor(entity.posZ))) {
                part.addEntity(entity(entity, box.minX, box.minY, box.minZ));
            }
        }
        return part;
    }

    public static void overlay(ISchematic source, ISchematic target, int dx, int dy, int dz, boolean entities) {
        if (source == target || dx < 0 || dy < 0 || dz < 0
            || (long) dx + source.getWidth() > target.getWidth() || (long) dy + source.getHeight() > target.getHeight()
            || (long) dz + source.getLength() > target.getLength()) throw new IllegalArgumentException("Invalid region overlay");
        // Air is not copied, so overlapping regions do not erase each other's blocks (targets start out empty).
        if (source instanceof Schematic) {
            ((Schematic) source).forEachBlock((x, y, z, state, origin) -> Schematic.set(target, x + dx, y + dy, z + dz, state, origin));
        } else {
            for (int y = 0; y < source.getHeight(); y++) for (int z = 0; z < source.getLength(); z++) for (int x = 0; x < source.getWidth(); x++) {
                IBlockState state = source.getBlockState(x, y, z);
                if (state.getBlock() != Blocks.AIR) target.setBlockState(x + dx, y + dy, z + dz, state);
            }
        }
        target.getTileEntities().removeIf(tile -> {
            BlockPos pos = tile.getPos();
            return pos.getX() >= dx && pos.getX() < dx + source.getWidth()
                && pos.getY() >= dy && pos.getY() < dy + source.getHeight()
                && pos.getZ() >= dz && pos.getZ() < dz + source.getLength();
        });
        for (TileEntity tile : source.getTileEntities()) {
            TileEntity copy = tile(tile, -dx, -dy, -dz);
            target.setTileEntity(copy.getPos(), copy);
        }
        if (entities) for (Entity entity : source.getEntities()) target.addEntity(entity(entity, -dx, -dy, -dz));
    }

    /** A copy moved by -(x, y, z). */
    public static TileEntity tile(TileEntity tile, int x, int y, int z) {
        try {
            TileEntity copy = NBTHelper.reloadTileEntity(tile, x, y, z);
            if (copy == null) throw new IllegalArgumentException("Unable to copy tile entity");
            return copy;
        } catch (NBTConversionException e) {
            throw new IllegalArgumentException("Unable to copy tile entity", e);
        }
    }

    public static Entity entity(Entity entity, int x, int y, int z) {
        try {
            Entity copy = NBTHelper.reloadEntity(entity, x, y, z);
            if (copy == null) throw new IllegalArgumentException("Unable to copy entity");
            return copy;
        } catch (NBTConversionException e) {
            throw new IllegalArgumentException("Unable to copy entity", e);
        }
    }
}
