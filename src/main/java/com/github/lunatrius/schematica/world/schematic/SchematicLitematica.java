package com.github.lunatrius.schematica.world.schematic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.WorldDummy;
import com.github.lunatrius.schematica.world.storage.MultiRegionSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class SchematicLitematica extends SchematicFormat {

    @Override
    public ISchematic readFromNBT(NBTTagCompound tagCompound) {
        // Errors are thrown on (not swallowed) so the load screen can say why a file failed.
        return readLitematica(tagCompound);
    }

    /** .litematic files are written by SchematicFormat.saveToFile through LitematicExport. */
    @Override
    public boolean writeToNBT(NBTTagCompound tagCompound, ISchematic schematic, World backupWorld) {
        return false;
    }

    private ISchematic readLitematica(NBTTagCompound root) {
        LitematicRegions document = new LitematicRegions(root);
        MultiRegionSchematic schematic = new MultiRegionSchematic(new ItemStack(Blocks.GRASS), document.width, document.height, document.length);
        SchematicOrigin min = document.minimum;
        schematic.setOrigin(new SchematicOrigin(-min.x, -min.y, -min.z));
        List<SchematicRegion> bounds = new ArrayList<>();
        for (LitematicRegions.Region region : document.regions) bounds.add(region.box.offset(-min.x, -min.y, -min.z));
        schematic.setRegions(bounds);
        boolean legacy = isLegacy(root);
        for (LitematicRegions.Region region : document.regions) {
            Schematic part = new Schematic(schematic.getIcon(), region.width, region.height, region.length);
            part.setOrigin(region.origin);
            readRegion(region, part, legacy);
            schematic.addRegion(region.box.offset(-min.x, -min.y, -min.z), part);
        }
        return schematic;
    }

    /** Files before version 5 (or data version 1631, 1.13.2) hold 1.12 block states, as in Litematica's own check. */
    static boolean isLegacy(NBTTagCompound root) {
        int dataVersion = root.getInteger("MinecraftDataVersion");
        return root.getInteger("Version") < 5 || dataVersion > 0 && dataVersion < 1631;
    }

    /** The state of a palette entry: 1.12 states directly, flattened 1.13+ states through the conversion table. */
    static IBlockState state(NBTTagCompound entry, boolean legacy) {
        return paletteState(entry, legacy).state;
    }

    /** A palette state, and for a block this game does not have the name of that block (null otherwise). */
    static final class PaletteState {
        final IBlockState state;
        /** The missing block a non-air state stands in for, kept with the block. */
        final String origin;
        /** The missing block this entry was read for (also when it became air), or null. */
        final String missing;
        PaletteState(IBlockState state, String missing) {
            this.state = state;
            this.missing = missing;
            this.origin = state.getBlock() == Blocks.AIR ? null : missing;
        }
    }

    static PaletteState paletteState(NBTTagCompound entry, boolean legacy) {
        String name = entry.getString("Name");
        if (name.isEmpty()) {
            ImportReport.unknownBlock("(missing block name)", "minecraft:air");
            return new PaletteState(Blocks.AIR.getDefaultState(), null);
        }
        if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) return new PaletteState(Blocks.AIR.getDefaultState(), null);
        IBlockState state = legacy ? BlockStates.fromEntry(entry) : BlockStates.fromModern(entry);
        if (state != null) return new PaletteState(state, null);
        return missing(name, MissingBlocks.properties(entry));
    }

    /** A block this game does not have: what the player picked for it, or the closest block. */
    static PaletteState missing(String name, java.util.Map<String, String> properties) {
        IBlockState state = MissingBlocks.resolve(name, properties);
        ImportReport.unknownBlock(name, BlockStates.toString(state));
        return new PaletteState(state, name);
    }

    private void readRegion(LitematicRegions.Region rd, ISchematic schematic, boolean legacy) {
        int paletteSize = rd.palette.tagCount();
        PaletteState[] palette = new PaletteState[paletteSize];
        for (int i = 0; i < paletteSize; i++) palette[i] = paletteState(rd.palette.getCompoundTagAt(i), legacy);
        long[] missing = new long[paletteSize];

        for (int y = 0; y < rd.height; y++) {
            for (int z = 0; z < rd.length; z++) {
                for (int x = 0; x < rd.width; x++) {
                    int index = rd.paletteIndex(x, y, z);
                    PaletteState state = palette[index];
                    if (state.missing != null) missing[index]++;
                    if (state.state.getBlock() == Blocks.AIR) continue;
                    Schematic.set(schematic, x, y, z, state.state, state.origin);
                }
            }
        }
        for (int i = 0; i < paletteSize; i++) if (palette[i].missing != null) ImportReport.missingBlocks(palette[i].missing, missing[i]);

        NBTTagList tileEntitiesList = rd.tileEntities();
        int teLoaded = 0, teSkipped = rd.invalidTiles;
        for (int i = 0; i < tileEntitiesList.tagCount(); i++) {
            try {
                NBTTagCompound teTag = tileEntitiesList.getCompoundTagAt(i);
                if (!teTag.hasKey("x") || !teTag.hasKey("y") || !teTag.hasKey("z")) continue;
                IBlockState placed = schematic.getBlockState(teTag.getInteger("x"), teTag.getInteger("y"), teTag.getInteger("z"));
                TileEntity te = NBTHelper.readTileEntityFromCompound(ModernNbt.tile(teTag, legacy, placed));
                if (te != null) {
                    schematic.setTileEntity(te.getPos(), te);
                    teLoaded++;
                } else {
                    teSkipped++;
                }
            } catch (Exception e) {
                Reference.logger.debug("Failed to load TileEntity from litematic region '{}': {}", rd.box.name, e.getMessage());
                teSkipped++;
            }
        }

        NBTTagList entitiesList = rd.entities();
        int entLoaded = 0, entSkipped = rd.invalidEntities;
        for (int i = 0; i < entitiesList.tagCount(); i++) {
            try {
                NBTTagCompound converted = ModernNbt.entity(entitiesList.getCompoundTagAt(i), legacy);
                Entity entity = converted == null ? null : createEntity(converted);
                if (entity != null) {
                    schematic.addEntity(entity);
                    entLoaded++;
                } else {
                    entSkipped++;
                }
            } catch (Exception e) {
                Reference.logger.debug("Failed to load Entity from litematic region '{}': {}", rd.box.name, e.getMessage());
                entSkipped++;
            }
        }

        Reference.logger.info("Region '{}': {}x{}x{}, palette size {}, {} tile entities ({} skipped), {} entities ({} skipped)",
            rd.box.name, rd.width, rd.height, rd.length, paletteSize, teLoaded, teSkipped, entLoaded, entSkipped);
    }

    /** An entity from NBT with its previous position and rotation equal to the current ones, for still rendering. */
    static Entity createEntity(NBTTagCompound entityTag) {
        Entity entity = NBTHelper.readEntityFromCompound(entityTag, WorldDummy.instance());
        if (entity == null) return null;
        entity.prevRotationYaw = entity.rotationYaw;
        entity.prevRotationPitch = entity.rotationPitch;
        entity.prevPosX = entity.posX;
        entity.prevPosY = entity.posY;
        entity.prevPosZ = entity.posZ;
        entity.lastTickPosX = entity.posX;
        entity.lastTickPosY = entity.posY;
        entity.lastTickPosZ = entity.posZ;
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            living.renderYawOffset = entity.rotationYaw;
            living.prevRenderYawOffset = entity.rotationYaw;
            living.rotationYawHead = entity.rotationYaw;
            living.prevRotationYawHead = entity.rotationYaw;
        }
        return entity;
    }

    public static boolean isLitematicFormat(NBTTagCompound tagCompound) {
        return tagCompound.hasKey("Version")
            && tagCompound.hasKey("Regions")
            && tagCompound.hasKey("Metadata");
    }
}
