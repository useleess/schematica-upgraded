// SPDX-License-Identifier: LGPL-3.0-only
// LitematicaSchematic.toTag of 1.12.2 Litematica (maruohon, liteloader_1.12.2), adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.world.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants.NBT;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Reference;

/**
 * Writes a schematic as a version 4 .litematic of Minecraft 1.12.2 (data version 1343), exactly the layout 1.12.2
 * Litematica writes: it reads it directly, and modern Litematica upgrades it with its 1.12 conversion table.
 */
public final class LitematicExport {
    public static final int VERSION = 4;
    public static final int DATA_VERSION = 1343;

    /** The root tag of a .litematic. */
    public static final class Document {
        public final NBTTagCompound root;

        public Document() { this(new NBTTagCompound()); }

        public Document(NBTTagCompound root) {
            this.root = root;
        }
    }

    private LitematicExport() {}

    public static Document encode(ISchematic schematic, World backupWorld, boolean includeNBT, boolean includeEntities,
        String name, String author, long time) {
        Document document = new Document();
        List<SchematicRegion> boxes = schematic.getRegions().isEmpty()
            ? Collections.singletonList(new SchematicRegion(name.isEmpty() ? "Unnamed" : name, 0, 0, 0,
                schematic.getWidth() - 1, schematic.getHeight() - 1, schematic.getLength() - 1))
            : schematic.getRegions();
        SchematicOrigin origin = schematic.getOrigin();
        Map<Entity, SchematicRegion> owners = new IdentityHashMap<>();
        if (includeEntities) {
            for (Entity entity : schematic.getEntities()) {
                int x = (int) Math.floor(entity.posX), y = (int) Math.floor(entity.posY), z = (int) Math.floor(entity.posZ);
                SchematicRegion owner = boxes.get(0);
                for (SchematicRegion box : boxes) if (box.contains(x, y, z)) { owner = box; break; }
                owners.put(entity, owner);
            }
        }
        NBTTagCompound regions = new NBTTagCompound();
        long volume = 0, blocks = 0;
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX, maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (SchematicRegion box : boxes) {
            ISchematic part = schematic.getRegionSchematic(box.name);
            int width = box.maxX - box.minX + 1, height = box.maxY - box.minY + 1, length = box.maxZ - box.minZ + 1;
            NBTTagCompound region = new NBTTagCompound();
            blocks += writeBlocks(region, schematic, part, box, width, height, length);
            region.setTag("TileEntities", tiles(schematic, part, box, backupWorld, includeNBT));
            region.setTag("Entities", entities(schematic, part, box, owners, includeEntities));
            region.setTag("PendingBlockTicks", new NBTTagList());
            region.setTag("Position", vector(box.minX - origin.x, box.minY - origin.y, box.minZ - origin.z));
            region.setTag("Size", vector(width, height, length));
            regions.setTag(box.name, region);
            volume += (long) width * height * length;
            minX = Math.min(minX, box.minX); minY = Math.min(minY, box.minY); minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX); maxY = Math.max(maxY, box.maxY); maxZ = Math.max(maxZ, box.maxZ);
        }
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("Name", name);
        metadata.setString("Author", author);
        metadata.setString("Description", "");
        metadata.setInteger("RegionCount", boxes.size());
        metadata.setLong("TotalVolume", volume);
        metadata.setLong("TotalBlocks", blocks);
        metadata.setLong("TimeCreated", time);
        metadata.setLong("TimeModified", time);
        metadata.setTag("EnclosingSize", vector(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
        document.root.setInteger("Version", VERSION);
        document.root.setInteger("MinecraftDataVersion", DATA_VERSION);
        document.root.setTag("Metadata", metadata);
        document.root.setTag("Regions", regions);
        return document;
    }

    static NBTTagCompound vector(int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        return tag;
    }

    /** Fills BlockStatePalette and BlockStates (x + z * width + y * width * length, air at index 0); returns non-air count. */
    private static long writeBlocks(NBTTagCompound region, ISchematic schematic, ISchematic part,
        SchematicRegion box, int width, int height, int length) {
        NBTTagList palette = new NBTTagList();
        palette.appendTag(BlockStates.toEntry(net.minecraft.init.Blocks.AIR.getDefaultState()));
        Map<IBlockState, Integer> indices = new HashMap<>();
        int[] values = new int[width * height * length];
        long count = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int sx = box.minX + x, sy = box.minY + y, sz = box.minZ + z;
                    IBlockState state;
                    if (part != null) state = part.getBlockState(x, y, z);
                    else if (schematic.containsBlock(sx, sy, sz)) state = schematic.getBlockState(sx, sy, sz);
                    else continue;
                    Integer index = indices.get(state);
                    if (index == null) {
                        if (BlockStates.isAir(state)) index = 0;
                        else {
                            index = palette.tagCount();
                            palette.appendTag(BlockStates.toEntry(state));
                        }
                        indices.put(state, index);
                    }
                    if (index != 0) count++;
                    values[x + width * (z + length * y)] = index;
                }
            }
        }
        region.setTag("BlockStatePalette", palette);
        region.setTag("BlockStates",
            new NBTTagLongArray(LitematicBitArray.pack(values, LitematicBitArray.getRequiredBits(palette.tagCount()))));
        return count;
    }

    private static NBTTagList tiles(ISchematic schematic, ISchematic part, SchematicRegion box, World backupWorld, boolean includeNBT) {
        NBTTagList list = new NBTTagList();
        if (!includeNBT) return list;
        List<TileEntity> source = part != null ? part.getTileEntities() : schematic.getTileEntities();
        for (TileEntity tile : source) {
            BlockPos pos = tile.getPos();
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (part == null) {
                if (!box.contains(x, y, z) || !schematic.containsBlock(x, y, z)) continue;
                x -= box.minX; y -= box.minY; z -= box.minZ;
            }
            try {
                if (!tile.hasWorld() && backupWorld != null) tile.setWorld(backupWorld);
                NBTTagCompound tag = NBTHelper.writeTileEntityToCompound(tile);
                tag.setInteger("x", x);
                tag.setInteger("y", y);
                tag.setInteger("z", z);
                list.appendTag(tag);
            } catch (Exception e) {
                Reference.logger.error("Block entity {} at {} {} {} failed to save, skipping", tile.getClass().getName(), x, y, z, e);
            }
        }
        return list;
    }

    private static NBTTagList entities(ISchematic schematic, ISchematic part, SchematicRegion box, Map<Entity, SchematicRegion> owners,
        boolean includeEntities) {
        NBTTagList list = new NBTTagList();
        if (!includeEntities) return list;
        List<Entity> source = new ArrayList<>(part != null ? part.getEntities() : schematic.getEntities());
        for (Entity entity : source) {
            if (part == null && owners.get(entity) != box) continue;
            try {
                NBTTagCompound tag = NBTHelper.writeEntityToCompound(entity);
                if (tag == null) continue;
                if (part == null) offset(tag, -box.minX, -box.minY, -box.minZ);
                list.appendTag(tag);
            } catch (Throwable t) {
                Reference.logger.error("Entity {} failed to save, skipping", entity, t);
            }
        }
        return list;
    }

    /** Moves an entity tag (Pos and hanging TileX/Y/Z) and anything riding it. */
    static void offset(NBTTagCompound entity, int dx, int dy, int dz) {
        NBTTagList pos = entity.getTagList("Pos", NBT.TAG_DOUBLE);
        if (pos.tagCount() == 3) {
            NBTTagList moved = new NBTTagList();
            moved.appendTag(new NBTTagDouble(pos.getDoubleAt(0) + dx));
            moved.appendTag(new NBTTagDouble(pos.getDoubleAt(1) + dy));
            moved.appendTag(new NBTTagDouble(pos.getDoubleAt(2) + dz));
            entity.setTag("Pos", moved);
        }
        if (entity.hasKey("TileX")) entity.setInteger("TileX", entity.getInteger("TileX") + dx);
        if (entity.hasKey("TileY")) entity.setInteger("TileY", entity.getInteger("TileY") + dy);
        if (entity.hasKey("TileZ")) entity.setInteger("TileZ", entity.getInteger("TileZ") + dz);
        if (entity.hasKey("Riding", NBT.TAG_COMPOUND)) offset(entity.getCompoundTag("Riding"), dx, dy, dz);
        NBTTagList passengers = entity.getTagList("Passengers", NBT.TAG_COMPOUND);
        for (int i = 0; i < passengers.tagCount(); i++) offset(passengers.getCompoundTagAt(i), dx, dy, dz);
    }
}
