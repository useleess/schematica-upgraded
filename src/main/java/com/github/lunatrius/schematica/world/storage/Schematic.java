package com.github.lunatrius.schematica.world.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.util.SchematicLimits;

/**
 * A schematic held as palette indices in 16x16x16 sections that are only allocated once a non-air block is set, so a
 * large, mostly empty bounding box (spread-out litematic regions) costs memory only where blocks are; the palette is per
 * schematic, so the stored data does not depend on the registry ids of the game it is loaded in.
 */
public class Schematic implements ISchematic {

    private static final int MAX_PALETTE = 0xffff;

    private ItemStack icon;
    private static final int SECTION_BITS = 4;
    private static final int SECTION = 1 << SECTION_BITS;
    private static final int SECTION_MASK = SECTION - 1;

    /** Visits the non-air blocks of a schematic. */
    public interface BlockVisitor {
        void visit(int x, int y, int z, IBlockState state);
    }

    /** Visits the non-air blocks of a schematic with the block each one was swapped from (null for blocks of the file). */
    public interface OriginVisitor {
        void visit(int x, int y, int z, IBlockState state, String origin);
    }

    private final char[][] sections;
    private final int sectionsX, sectionsZ;
    private final List<IBlockState> palette = new ArrayList<>();
    private final Map<IBlockState, Integer> paletteIndex = new HashMap<>();
    /** Per palette entry: the block of the file this game lacks that the state stands in for, or null. */
    private final List<String> origins = new ArrayList<>();
    private final Map<String, Map<IBlockState, Integer>> originIndex = new HashMap<>();
    private final List<TileEntity> tileEntities = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final int width;
    private final int height;
    private final int length;
    private List<SchematicRegion> regions = Collections.emptyList();
    private SparseBits mask;
    private SchematicOrigin origin = SchematicOrigin.ZERO;

    public Schematic(final ItemStack icon, final int width, final int height, final int length) {
        SchematicLimits.boxVolume(width, height, length);
        this.sectionsX = (width + SECTION_MASK) >> SECTION_BITS;
        this.sectionsZ = (length + SECTION_MASK) >> SECTION_BITS;
        this.sections = new char[sectionsX * sectionsZ * ((height + SECTION_MASK) >> SECTION_BITS)][];
        this.icon = icon;
        this.width = width;
        this.height = height;
        this.length = length;
        IBlockState air = Blocks.AIR.getDefaultState();
        palette.add(air);
        origins.add(null);
        paletteIndex.put(air, 0);
    }

    @Override public SchematicOrigin getOrigin() { return origin; }

    public void setOrigin(SchematicOrigin origin) { this.origin = Objects.requireNonNull(origin); }

    @Override public List<SchematicRegion> getRegions() { return regions; }

    public void setRegions(List<SchematicRegion> regions) {
        SparseBits checked = RegionMask.sparse(regions, width, height, length);
        this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
        this.mask = checked;
        tileEntities.removeIf(tile -> !containsBlock(tile.getPos()));
        entities.removeIf(entity -> !containsBlock((int) Math.floor(entity.posX), (int) Math.floor(entity.posY), (int) Math.floor(entity.posZ)));
    }

    public void setEmpty() {
        regions = Collections.emptyList();
        mask = new SparseBits();
        tileEntities.clear();
        entities.clear();
    }

    @Override public boolean containsBlock(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length
            && (mask == null || mask.get(index(x, y, z)));
    }

    private int index(int x, int y, int z) {
        return x + width * (z + length * y);
    }

    private int section(int x, int y, int z) {
        return (x >> SECTION_BITS) + sectionsX * ((z >> SECTION_BITS) + sectionsZ * (y >> SECTION_BITS));
    }

    /** The index (for forEachBlockInSection) of the 16x16x16 section holding a position inside the schematic. */
    public int sectionAt(int x, int y, int z) {
        return section(x, y, z);
    }

    private static int inSection(int x, int y, int z) {
        return (x & SECTION_MASK) | (z & SECTION_MASK) << SECTION_BITS | (y & SECTION_MASK) << 2 * SECTION_BITS;
    }

    @Override
    public IBlockState getBlockState(final int x, final int y, final int z) {
        if (!containsBlock(x, y, z)) return palette.get(0);
        char[] section = sections[section(x, y, z)];
        return section == null ? palette.get(0) : palette.get(section[inSection(x, y, z)]);
    }

    /** The block of the file a stand-in block was swapped from (one this game does not have), or null. */
    public String getOrigin(final int x, final int y, final int z) {
        if (!containsBlock(x, y, z)) return null;
        char[] section = sections[section(x, y, z)];
        return section == null ? null : origins.get(section[inSection(x, y, z)]);
    }

    /** Whether any block is a stand-in for a block this game does not have. */
    public boolean hasOrigins() {
        for (String origin : origins) if (origin != null) return true;
        return false;
    }

    /** The origin of a cell of any schematic: only this storage keeps them. */
    public static String originOf(ISchematic schematic, int x, int y, int z) {
        return schematic instanceof Schematic ? ((Schematic) schematic).getOrigin(x, y, z) : null;
    }

    /** Sets a block of any schematic, with its origin where the storage keeps them. */
    public static boolean set(ISchematic schematic, int x, int y, int z, IBlockState state, String origin) {
        if (origin != null && schematic instanceof Schematic) return ((Schematic) schematic).setBlockState(x, y, z, state, origin);
        return schematic.setBlockState(x, y, z, state);
    }

    /** Visits every non-air block inside the schematic, skipping unallocated (all air) sections. */
    public void forEachBlock(BlockVisitor visitor) {
        for (int index = 0; index < sections.length; index++) forEachBlockInSection(index, null, visitor);
    }

    /** forEachBlock with the origin of each block. */
    public void forEachBlock(OriginVisitor visitor) {
        for (int index = 0; index < sections.length; index++) forEachBlockInSection(index, null, visitor);
    }

    /** The number of 16x16x16 sections, in the order forEachBlockInSection takes them (x fastest, then z, then y). */
    public int sectionCount() { return sections.length; }

    /**
     * Visits the non-air blocks of one section that lie inside box ({minX, minY, minZ, maxX, maxY, maxZ}, maximums
     * exclusive; null for no limit). Unallocated (all air) sections and sections outside the box cost nothing.
     */
    public void forEachBlockInSection(int index, int[] box, BlockVisitor visitor) {
        forEachBlockInSection(index, box, (OriginVisitor) (x, y, z, state, origin) -> visitor.visit(x, y, z, state));
    }

    /** forEachBlockInSection with the origin of each block. */
    public void forEachBlockInSection(int index, int[] box, OriginVisitor visitor) {
        char[] section = sections[index];
        if (section == null) return;
        int sx = index % sectionsX, sz = index / sectionsX % sectionsZ, sy = index / (sectionsX * sectionsZ);
        int baseX = sx << SECTION_BITS, baseY = sy << SECTION_BITS, baseZ = sz << SECTION_BITS;
        if (box != null && (baseX >= box[3] || baseY >= box[4] || baseZ >= box[5]
            || baseX + SECTION <= box[0] || baseY + SECTION <= box[1] || baseZ + SECTION <= box[2])) return;
        for (int i = 0; i < section.length; i++) {
            if (section[i] == 0) continue;
            int x = baseX | i & SECTION_MASK, z = baseZ | i >> SECTION_BITS & SECTION_MASK, y = baseY | i >> 2 * SECTION_BITS;
            if (box != null && (x < box[0] || y < box[1] || z < box[2] || x >= box[3] || y >= box[4] || z >= box[5])) continue;
            if (containsBlock(x, y, z)) visitor.visit(x, y, z, palette.get(section[i]), origins.get(section[i]));
        }
    }

    @Override
    public boolean setBlockState(final int x, final int y, final int z, final IBlockState state) {
        return setBlockState(x, y, z, state, null);
    }

    /** Sets a block that stands in for origin, a block of the file this game does not have (null: a block of the file). */
    public boolean setBlockState(final int x, final int y, final int z, final IBlockState state, final String origin) {
        if (state == null || !containsBlock(x, y, z)) return false;
        Map<IBlockState, Integer> index = origin == null || state.getBlock() == Blocks.AIR ? paletteIndex
            : originIndex.computeIfAbsent(origin, ignored -> new HashMap<>());
        Integer id = index.get(state);
        if (id == null) {
            if (palette.size() > MAX_PALETTE) throw new IllegalStateException("Too many distinct block states in one schematic");
            id = palette.size();
            palette.add(state);
            origins.add(index == paletteIndex ? null : origin);
            index.put(state, id);
        }
        int at = section(x, y, z);
        char[] section = sections[at];
        if (section == null) {
            if (id == 0) return true;
            section = sections[at] = new char[SECTION * SECTION * SECTION];
        }
        section[inSection(x, y, z)] = (char) id.intValue();
        return true;
    }

    @Override
    public TileEntity getTileEntity(final BlockPos pos) {
        if (!containsBlock(pos)) return null;
        for (final TileEntity tileEntity : this.tileEntities) {
            if (tileEntity.getPos().equals(pos)) return tileEntity;
        }
        return null;
    }

    @Override
    public List<TileEntity> getTileEntities() {
        return this.tileEntities;
    }

    @Override
    public void setTileEntity(final BlockPos pos, final TileEntity tileEntity) {
        if (!containsBlock(pos)) return;
        this.removeTileEntity(pos);
        if (tileEntity != null) this.tileEntities.add(tileEntity);
    }

    @Override
    public void removeTileEntity(final BlockPos pos) {
        this.tileEntities.removeIf(tileEntity -> tileEntity.getPos().equals(pos));
    }

    @Override
    public List<Entity> getEntities() {
        return this.entities;
    }

    @Override
    public void addEntity(final Entity entity) {
        if (entity == null || entity.getUniqueID() == null || entity instanceof EntityPlayer) return;
        if (mask != null && !containsBlock((int) Math.floor(entity.posX), (int) Math.floor(entity.posY), (int) Math.floor(entity.posZ))) return;
        for (final Entity e : this.entities) {
            if (entity.getUniqueID().equals(e.getUniqueID())) return;
        }
        this.entities.add(entity);
    }

    @Override
    public void removeEntity(final Entity entity) {
        if (entity == null || entity.getUniqueID() == null) return;
        this.entities.removeIf(e -> entity.getUniqueID().equals(e.getUniqueID()));
    }

    @Override
    public ItemStack getIcon() {
        return this.icon;
    }

    @Override
    public void setIcon(final ItemStack icon) {
        this.icon = icon != null ? icon : new ItemStack(Blocks.GRASS);
    }

    @Override public int getWidth() { return this.width; }

    @Override public int getLength() { return this.length; }

    @Override public int getHeight() { return this.height; }
}
