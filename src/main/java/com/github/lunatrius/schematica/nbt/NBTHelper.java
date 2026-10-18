package com.github.lunatrius.schematica.nbt;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.world.WorldDummy;

public class NBTHelper {

    public static List<TileEntity> readTileEntitiesFromCompound(final NBTTagCompound compound) {
        return readTileEntitiesFromCompound(compound, new ArrayList<>());
    }

    public static List<TileEntity> readTileEntitiesFromCompound(final NBTTagCompound compound,
        final List<TileEntity> tileEntities) {
        final NBTTagList tagList = compound.getTagList(Names.NBT.TILE_ENTITIES, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tagList.tagCount(); i++) {
            final TileEntity tileEntity = readTileEntityFromCompound(tagList.getCompoundTagAt(i));
            if (tileEntity != null) tileEntities.add(tileEntity);
        }
        return tileEntities;
    }

    public static NBTTagCompound writeTileEntitiesToCompound(final List<TileEntity> tileEntities) {
        return writeTileEntitiesToCompound(tileEntities, new NBTTagCompound());
    }

    public static NBTTagCompound writeTileEntitiesToCompound(final List<TileEntity> tileEntities,
        final NBTTagCompound compound) {
        final NBTTagList tagList = new NBTTagList();
        for (TileEntity tileEntity : tileEntities) {
            tagList.appendTag(writeTileEntityToCompound(tileEntity));
        }
        compound.setTag(Names.NBT.TILE_ENTITIES, tagList);
        return compound;
    }

    public static List<Entity> readEntitiesFromCompound(final NBTTagCompound compound) {
        return readEntitiesFromCompound(compound, null, new ArrayList<>());
    }

    public static List<Entity> readEntitiesFromCompound(final NBTTagCompound compound, final World world,
        final List<Entity> entities) {
        final NBTTagList tagList = compound.getTagList(Names.NBT.ENTITIES, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tagList.tagCount(); i++) {
            final Entity entity = readEntityFromCompound(tagList.getCompoundTagAt(i), world == null ? WorldDummy.instance() : world);
            if (entity != null) entities.add(entity);
        }
        return entities;
    }

    public static NBTTagCompound writeEntitiesToCompound(final List<Entity> entities) {
        return writeEntitiesToCompound(entities, new NBTTagCompound());
    }

    public static NBTTagCompound writeEntitiesToCompound(final List<Entity> entities, final NBTTagCompound compound) {
        final NBTTagList tagList = new NBTTagList();
        for (Entity entity : entities) {
            final NBTTagCompound entityCompound = writeEntityToCompound(entity);
            if (entityCompound != null) tagList.appendTag(entityCompound);
        }
        compound.setTag(Names.NBT.ENTITIES, tagList);
        return compound;
    }

    public static TileEntity reloadTileEntity(TileEntity tileEntity) throws NBTConversionException {
        return reloadTileEntity(tileEntity, 0, 0, 0);
    }

    /** A copy of a tile entity moved by -offset. */
    public static TileEntity reloadTileEntity(TileEntity tileEntity, int offsetX, int offsetY, int offsetZ)
        throws NBTConversionException {
        if (tileEntity == null) return null;
        try {
            final NBTTagCompound compound = writeTileEntityToCompound(tileEntity);
            compound.setInteger("x", compound.getInteger("x") - offsetX);
            compound.setInteger("y", compound.getInteger("y") - offsetY);
            compound.setInteger("z", compound.getInteger("z") - offsetZ);
            return readTileEntityFromCompound(compound);
        } catch (Throwable t) {
            throw new NBTConversionException(tileEntity, t);
        }
    }

    public static Entity reloadEntity(Entity entity) throws NBTConversionException {
        return reloadEntity(entity, 0, 0, 0);
    }

    /** A copy of an entity moved by -offset (hanging entities carry their wall position in the tag as well). */
    public static Entity reloadEntity(Entity entity, int offsetX, int offsetY, int offsetZ)
        throws NBTConversionException {
        if (entity == null) return null;
        try {
            final NBTTagCompound compound = writeEntityToCompound(entity);
            if (compound == null) return null;
            shiftEntityTag(compound, -offsetX, -offsetY, -offsetZ);
            return readEntityFromCompound(compound, WorldDummy.instance());
        } catch (Throwable t) {
            throw new NBTConversionException(entity, t);
        }
    }

    /** Moves the position (and the TileX/Y/Z of hanging entities) in an entity tag by a delta. */
    public static void shiftEntityTag(NBTTagCompound compound, int dx, int dy, int dz) {
        NBTTagList pos = compound.getTagList("Pos", Constants.NBT.TAG_DOUBLE);
        if (pos.tagCount() == 3) {
            double x = pos.getDoubleAt(0) + dx, y = pos.getDoubleAt(1) + dy, z = pos.getDoubleAt(2) + dz;
            NBTTagList moved = new NBTTagList();
            moved.appendTag(new NBTTagDouble(x));
            moved.appendTag(new NBTTagDouble(y));
            moved.appendTag(new NBTTagDouble(z));
            compound.setTag("Pos", moved);
        }
        if (compound.hasKey("TileX")) {
            compound.setInteger("TileX", compound.getInteger("TileX") + dx);
            compound.setInteger("TileY", compound.getInteger("TileY") + dy);
            compound.setInteger("TileZ", compound.getInteger("TileZ") + dz);
        }
    }

    public static NBTTagCompound writeTileEntityToCompound(final TileEntity tileEntity) {
        return tileEntity.writeToNBT(new NBTTagCompound());
    }

    public static TileEntity readTileEntityFromCompound(final NBTTagCompound tileEntityCompound) {
        return TileEntity.create(WorldDummy.instance(), (NBTTagCompound) tileEntityCompound.copy());
    }

    /** The tile entity position stored in a tile entity tag. */
    public static BlockPos tilePos(NBTTagCompound tag) {
        return new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
    }

    public static NBTTagCompound writeEntityToCompound(final Entity entity) {
        final NBTTagCompound entityCompound = new NBTTagCompound();
        return entity.writeToNBTOptional(entityCompound) ? entityCompound : null;
    }

    public static Entity readEntityFromCompound(final NBTTagCompound nbtTagCompound, final World world) {
        return EntityList.createEntityFromNBT(nbtTagCompound, world);
    }
}
