package com.github.lunatrius.schematica.client.world;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.gui.placement.PlacementTransform;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3f;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.SchematicTransform;
import com.github.lunatrius.schematica.world.WorldDummy;
import com.github.lunatrius.schematica.world.schematic.BlockStateTransform;
import com.github.lunatrius.schematica.world.storage.SaveHandlerSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;
import com.github.lunatrius.schematica.world.storage.SchematicCopies;

/**
 * A schematic placed in the client's world: a World of its own (read through local coordinates, drawn translated by
 * {@link #position}) that gives the renderer, tile entity renderers and ray traces blockstates, lighting and biomes.
 */
@SideOnly(Side.CLIENT)
public class SchematicWorld extends World {

    private static final WorldSettings WORLD_SETTINGS = new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.FLAT);

    public static final ItemStack DEFAULT_ICON = new ItemStack(Blocks.GRASS);

    public String name = "";

    private ISchematic schematic;
    public final List<String> transformOperations = new ArrayList<>();
    private SchematicSourceData placementSource;
    private SubRegionPlacements subregions;
    private com.github.lunatrius.schematica.world.storage.SparseBits visibleRegionBlocks;
    private int placementRevision;
    private int contentRevision;
    private PlacementSettings placementSettings = PlacementSettings.DEFAULT;

    public final Vector3i position = new Vector3i();
    /** Grid/repeat settings of a normal placement (GridSettings of the 1.12.2 Litematica). */
    public final GridSettings grid = new GridSettings();
    /** For a repeated grid copy, its base placement; null for normal placements. */
    public final SchematicWorld gridBase;
    /** The placement's bounding box color (SchematicPlacement.boxesBBColor), one of the Kelly colors; saved with the placement. */
    public Integer boxColor;
    /** worldFingerprinting: the bedrock under the placement where it was put, and whether this world has other bedrock there. */
    public WorldFingerprint fingerprint;
    public boolean otherWorld;
    /** The placement's saved material list settings (SchematicPlacement material_list), kept until the material list is ported. */
    public com.github.lunatrius.schematica.client.gui.material.MaterialList materialList;
    public JsonObject materialListData;

    public boolean isRendering;
    public boolean isRenderingLayer;
    public int renderingLayer;
    public boolean isRenderingEntities = false;
    /** Whether to paste block NBT (tile entity data) when pasting this schematic. */
    public boolean isPastingBlockNBT = true;
    public int rotationStateX, rotationStateY, rotationStateZ;
    public int flipStateX, flipStateY, flipStateZ;

    /** The directory this schematic was loaded from (for persistence). */
    public File sourceDirectory;
    /** The filename this schematic was loaded from (for persistence). */
    public String sourceFilename;
    /** A schematic project's checked-out version, which the world session does not save. */
    public boolean projectVersion;

    private boolean tracingRenderedBlocks;

    public SchematicWorld(ISchematic schematic) {
        this(schematic, (SchematicWorld) null);
    }

    public SchematicWorld(ISchematic schematic, String filename) {
        this(schematic);
        // Strip any known schematic extension from the display name
        this.name = filename.replaceAll("(?i)\\.(schematic|litematic|schemplus|nbt|schem)$", "");
    }

    /** gridBase: the placement this is a grid copy of; copies share its blocks and keep its block entities bound to it. */
    private SchematicWorld(ISchematic schematic, SchematicWorld gridBase) {
        super(new SaveHandlerSchematic(), new WorldInfo(WORLD_SETTINGS, "Schematica"), new WorldProviderSurface(), new Profiler(), true);
        // vanilla world classes link their provider to themselves; without it the provider has no world to read
        this.provider.setWorld(this);
        this.schematic = schematic;
        this.gridBase = gridBase;
        if (gridBase != null) return;

        for (TileEntity tileEntity : schematic.getTileEntities()) bindTileEntity(tileEntity);
        for (TileEntity tileEntity : schematic.getTileEntities()) validateTileEntity(tileEntity);
        refreshChests();

        // Entities are shown by default when the schematic has some
        if (!schematic.getEntities().isEmpty()) {
            this.isRenderingEntities = true;
        }
    }

    /** SchematicPlacement.createRepeatedCopy: shares the blocks and settings, at the given position. */
    public static SchematicWorld repeatedCopy(SchematicWorld base, int x, int y, int z) {
        SchematicWorld copy = new SchematicWorld(base.schematic, base);
        copy.name = base.name;
        copy.placementSource = base.placementSource;
        copy.subregions = base.subregions;
        copy.visibleRegionBlocks = base.visibleRegionBlocks;
        copy.transformOperations.addAll(base.transformOperations);
        copy.placementSettings = base.placementSettings;
        copy.isRendering = base.isRendering;
        copy.isRenderingEntities = base.isRenderingEntities;
        copy.isRenderingLayer = base.isRenderingLayer;
        copy.renderingLayer = base.renderingLayer;
        copy.isPastingBlockNBT = base.isPastingBlockNBT;
        copy.boxColor = base.boxColor;
        copy.sourceDirectory = base.sourceDirectory;
        copy.sourceFilename = base.sourceFilename;
        copy.position.set(x, y, z);
        return copy;
    }

    public ISchematic getSchematic() { return this.schematic; }

    public PlacementSettings placementSettings() { return placementSettings; }

    public void setPlacementSettings(PlacementSettings settings) {
        placementSettings = Objects.requireNonNull(settings);
        placementRevision++;
    }

    public boolean isEnabled() { return placementSettings.enabled; }

    public boolean isRenderingEnabled() { return placementSettings.renders(isRendering); }

    public void setPlacementSource(SchematicSourceData source) {
        placementSource = source;
        subregions = SubRegionPlacements.create(schematic);
    }

    public SubRegionPlacements subregions() { return subregions; }

    public boolean hasEnabledRegions() { return subregions == null || subregions.hasEnabled(); }

    /** Replaces one composed cell after its source cell was edited; the placement geometry stays unchanged. */
    public void writeCell(int x, int y, int z, CellState state) {
        if (!schematic.containsBlock(x, y, z)) return;
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState previous = schematic.getBlockState(x, y, z);
        setBlockState(pos, state.state, 0);
        if (state.tile != null) setTileEntity(pos, SchematicCopies.tile(state.tile, -x, -y, -z));
        else if (previous.getBlock() != state.state.getBlock() || state.isAir()) removeTileEntity(pos);
        contentRevision++;
    }

    public void selectSubregion(String name) {
        subregions = subregions.select(name);
        placementRevision++;
    }

    public SchematicOrigin subregionPosition(String name) {
        SchematicOrigin relative = SubRegionPlacements.vector(subregions.get(name).position, transformOperations, false);
        SchematicOrigin origin = originPosition();
        return relative.atMinimum(origin.x, origin.y, origin.z);
    }

    public SchematicRegion subregionBounds(String name) {
        int[] corners = subregionCorners(name);
        return new SchematicRegion(name, corners[0], corners[1], corners[2], corners[3], corners[4], corners[5]);
    }

    /** A sub-region's two corners in world coordinates: its own origin corner first, like Litematica's box pos1/pos2. */
    public int[] subregionCorners(String name) {
        SchematicRegion box = subregions.get(name).bounds();
        SchematicOrigin origin = originPosition();
        SchematicOrigin a = SubRegionPlacements.vector(new SchematicOrigin(box.minX, box.minY, box.minZ), transformOperations, false)
            .atMinimum(origin.x, origin.y, origin.z);
        SchematicOrigin b = SubRegionPlacements.vector(new SchematicOrigin(box.maxX, box.maxY, box.maxZ), transformOperations, false)
            .atMinimum(origin.x, origin.y, origin.z);
        return new int[] {a.x, a.y, a.z, b.x, b.y, b.z};
    }

    public void moveSubregionTo(String name, int x, int y, int z) {
        SchematicOrigin origin = originPosition();
        SchematicOrigin relative = SubRegionPlacements.vector(
            new SchematicOrigin(Math.subtractExact(x, origin.x), Math.subtractExact(y, origin.y), Math.subtractExact(z, origin.z)),
            transformOperations, true);
        changeSubregions(subregions.replace(subregions.get(name).position(relative)));
    }

    public void changeSubregions(SubRegionPlacements next) {
        if (!placementSettings.allowsRegionChange(subregions, next)) throw new IllegalStateException(PlacementSettings.LOCKED_MESSAGE);
        rebuildRegions(next, new ArrayList<>(transformOperations));
    }

    public void resetSubregions(String name) {
        if (placementSettings.locked) throw new IllegalStateException(PlacementSettings.LOCKED_MESSAGE);
        changeSubregions(name == null ? subregions.reset() : subregions.replace(subregions.get(name).reset()));
    }

    public void restoreSubregions(JsonObject saved) {
        if (saved == null || subregions == null) return;
        SubRegionPlacements next = subregions.restore(saved);
        if (!subregions.modified() && !next.modified()) {
            subregions = next;
            placementRevision++;
        } else changeSubregions(next);
    }

    /** Recomposes the placement from its source with changed sub-region placements or operations. */
    private void rebuildRegions(SubRegionPlacements next, List<String> operations) {
        SchematicOrigin origin = originPosition();
        SubRegionPlacements.Layout layout = next.layout();
        SchematicOrigin offset = PlacementTransform.transformOrigin(
            new SchematicOrigin(-layout.minimum.x, -layout.minimum.y, -layout.minimum.z),
            layout.width, layout.height, layout.length, String.join("", operations));
        SchematicOrigin minimum = offset.minimumAt(origin);
        int[] size = PlacementTransform.transformedSize(layout.width, layout.height, layout.length, String.join("", operations));
        for (int i = 0; i < 3; i++) if (minimum.coordinates()[i] < -30000000 || (long) minimum.coordinates()[i] + size[i] > 30000000) {
            throw new IllegalArgumentException("Subregion placement outside coordinate limits");
        }
        RegionComposer composed;
        try { composed = RegionComposer.build(placementSource.composeSource(), next, operations); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Unable to restore schematic source", e); }
        this.schematic = composed.world.getSchematic();
        this.visibleRegionBlocks = composed.visible;
        this.subregions = next;
        this.position.set(minimum.x, minimum.y, minimum.z);
        this.transformOperations.clear();
        this.transformOperations.addAll(operations);
        rotationStateX = composed.world.rotationStateX; rotationStateY = composed.world.rotationStateY; rotationStateZ = composed.world.rotationStateZ;
        flipStateX = composed.world.flipStateX; flipStateY = composed.world.flipStateY; flipStateZ = composed.world.flipStateZ;
        renderingLayer = Math.min(renderingLayer, getHeight() - 1);
        for (TileEntity tile : schematic.getTileEntities()) bindTileEntity(tile);
        for (TileEntity tile : schematic.getTileEntities()) validateTileEntity(tile);
        refreshChests();
        placementRevision++;
        contentRevision++;
    }

    public SchematicSourceData sourceData() { return placementSource; }

    public boolean isRepeatedPlacement() { return gridBase != null; }

    public int placementRevision() { return placementRevision; }

    public int contentRevision() { return contentRevision; }

    /** The placement's origin in the world. */
    public SchematicOrigin originPosition() {
        return schematic.getOrigin().atMinimum(position.x, position.y, position.z);
    }

    public void moveOriginTo(int x, int y, int z) {
        SchematicOrigin minimum = schematic.getOrigin().minimumAt(
            placementSettings.constrainOrigin(originPosition(), new SchematicOrigin(x, y, z)));
        position.set(minimum.x, minimum.y, minimum.z);
    }

    public void moveMinimumTo(int x, int y, int z) {
        SchematicOrigin origin = schematic.getOrigin().atMinimum(x, y, z);
        moveOriginTo(origin.x, origin.y, origin.z);
    }

    /** The world box around the placement: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public int[] enclosingBox() {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (SchematicRegion region : enabledRegionBounds()) {
            box[0] = Math.min(box[0], region.minX); box[1] = Math.min(box[1], region.minY); box[2] = Math.min(box[2], region.minZ);
            box[3] = Math.max(box[3], region.maxX); box[4] = Math.max(box[4], region.maxY); box[5] = Math.max(box[5], region.maxZ);
        }
        if (box[0] > box[3]) return new int[] {position.x, position.y, position.z,
            position.x + getWidth() - 1, position.y + getHeight() - 1, position.z + getLength() - 1};
        return box;
    }

    /** The world boxes of the schematic's regions, or the whole placement when it has none. */
    public List<SchematicRegion> enabledRegionBounds() {
        List<SchematicRegion> boxes = new ArrayList<>();
        if (subregions != null) {
            for (SubRegionPlacements.Region region : subregions.regions()) if (region.enabled) boxes.add(subregionBounds(region.name()));
            if (!boxes.isEmpty() || !subregions.regions().isEmpty()) return boxes;
        }
        List<SchematicRegion> regions = schematic.getRegions();
        if (regions.isEmpty()) {
            boxes.add(new SchematicRegion(name.isEmpty() ? "all" : name, position.x, position.y, position.z,
                position.x + getWidth() - 1, position.y + getHeight() - 1, position.z + getLength() - 1));
        } else {
            for (SchematicRegion region : regions) boxes.add(region.offset(position.x, position.y, position.z));
        }
        return boxes;
    }

    // ---- what is drawn

    public boolean isBlockRendered(int x, int y, int z) {
        return isBlockInRange(x, y, z) && (visibleRegionBlocks == null || visibleRegionBlocks.get(x + getWidth() * (z + getLength() * y)));
    }

    public boolean isBlockInRange(int x, int y, int z) {
        return isEnabled() && schematic.containsBlock(x, y, z) && (!isRenderingLayer || renderingLayer == y)
            && RenderLayerSettings.RANGE.contains((long) position.x + x, (long) position.y + y, (long) position.z + z);
    }

    /** The local block range to draw: {minX, minY, minZ, maxX, maxY, maxZ} (maximums exclusive). */
    public int[] renderBounds() {
        return RenderLayerSettings.RANGE.localBounds(position.x, position.y, position.z,
            getWidth(), getHeight(), getLength(), isRenderingLayer, renderingLayer);
    }

    /**
     * While on, blocks that are not shown (outside the layer or range) read as air, so the faces next to a cut are
     * drawn. Returns the previous setting.
     */
    public boolean setRenderedView(boolean on) {
        boolean previous = tracingRenderedBlocks;
        tracingRenderedBlocks = on;
        return previous;
    }

    public RayTraceResult rayTraceRendered(Vec3d start, Vec3d end) {
        return rayTraceRendered(start, end, false);
    }

    /** A ray trace (in local coordinates) that only hits the blocks that are shown. */
    public RayTraceResult rayTraceRendered(Vec3d start, Vec3d end, boolean fluids) {
        if (!isRenderingEnabled()) return null;
        boolean previous = tracingRenderedBlocks;
        tracingRenderedBlocks = true;
        try {
            return rayTraceBlocks(start, end, fluids, false, false);
        } finally {
            tracingRenderedBlocks = previous;
        }
    }

    // ---- the World interface, over the schematic

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        if (tracingRenderedBlocks && !isBlockRendered(pos.getX(), pos.getY(), pos.getZ())) {
            return Blocks.AIR.getDefaultState();
        }
        return this.schematic.getBlockState(pos);
    }

    @Override
    public boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
        IBlockState previous = this.schematic.getBlockState(pos);
        boolean changed = this.schematic.setBlockState(pos, state);
        if (changed && previous != state) {
            placementRevision++;
            contentRevision++;
        }
        return changed;
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return this.schematic.getTileEntity(pos);
    }

    @Override
    public void setTileEntity(BlockPos pos, @Nullable TileEntity tileEntity) {
        if (tileEntity != null) tileEntity.setPos(pos.toImmutable());
        this.schematic.setTileEntity(pos, tileEntity);
        initializeTileEntity(tileEntity);
    }

    @Override
    public void removeTileEntity(BlockPos pos) {
        this.schematic.removeTileEntity(pos);
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        IBlockState state = getBlockState(pos);
        return state.getBlock().isAir(state, this, pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        if (VisualSettings.fakeLighting) {
            int level = VisualSettings.fakeLightLevel;
            return level << 20 | Math.max(level, lightValue) << 4;
        }
        World real = Minecraft.getMinecraft().world;
        return real == null ? 15 << 20 | lightValue << 4 : real.getCombinedLight(pos.add(position.x, position.y, position.z), lightValue);
    }

    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        if (VisualSettings.fakeLighting) return VisualSettings.fakeLightLevel;
        World real = Minecraft.getMinecraft().world;
        return real == null ? 15 : real.getLightFor(type, pos.add(position.x, position.y, position.z));
    }

    @Override
    public int getLightFromNeighbors(BlockPos pos) {
        return getLightFor(EnumSkyBlock.SKY, pos);
    }

    @Override
    public int getLight(BlockPos pos) {
        return getLightFor(EnumSkyBlock.SKY, pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return Biomes.JUNGLE;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        if (!isBlockLoaded(pos)) return _default;
        IBlockState state = getBlockState(pos);
        return state.getBlock().isSideSolid(state, this, pos, side);
    }

    @Override
    public boolean isBlockNormalCube(BlockPos pos, boolean _default) {
        return isBlockLoaded(pos) ? getBlockState(pos).isNormalCube() : _default;
    }

    @Override
    public boolean isBlockLoaded(BlockPos pos) {
        return this.schematic != null && pos.getX() >= 0 && pos.getX() < getWidth() && pos.getY() >= 0 && pos.getY() < getHeight()
            && pos.getZ() >= 0 && pos.getZ() < getLength();
    }

    @Override
    public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) {
        return isBlockLoaded(pos);
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return this.schematic != null && x >= 0 && z >= 0 && (x << 4) < getWidth() && (z << 4) < getLength();
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    /** The schematic's own entities: this world has no chunks to look them up in (placement checks such as mayPlace ask). */
    @Override
    public List<Entity> getEntitiesInAABBexcluding(@Nullable Entity except, net.minecraft.util.math.AxisAlignedBB box,
                                                   @Nullable com.google.common.base.Predicate<? super Entity> filter) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : this.schematic.getEntities()) {
            if (entity != except && entity.getEntityBoundingBox().intersects(box) && (filter == null || filter.apply(entity))) found.add(entity);
        }
        return found;
    }

    @Override
    public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> type, net.minecraft.util.math.AxisAlignedBB box,
                                                            @Nullable com.google.common.base.Predicate<? super T> filter) {
        List<T> found = new ArrayList<>();
        for (Entity entity : this.schematic.getEntities()) {
            if (!type.isInstance(entity) || !entity.getEntityBoundingBox().intersects(box)) continue;
            T typed = type.cast(entity);
            if (filter == null || filter.apply(typed)) found.add(typed);
        }
        return found;
    }

    public int getWidth() { return this.schematic.getWidth(); }

    public int getLength() { return this.schematic.getLength(); }

    @Override
    public int getHeight() { return this.schematic.getHeight(); }

    // ---- tile entities

    public void initializeTileEntity(@Nullable TileEntity tileEntity) {
        if (tileEntity == null) return;
        bindTileEntity(tileEntity);
        validateTileEntity(tileEntity);
    }

    private void bindTileEntity(TileEntity tileEntity) {
        tileEntity.setWorld(this);
        tileEntity.updateContainingBlockInfo();
        tileEntity.getBlockType();
    }

    private void validateTileEntity(TileEntity tileEntity) {
        try {
            tileEntity.validate();
        } catch (Exception e) {
            Reference.logger.error("TileEntity validation for {} failed!", tileEntity.getClass(), e);
        }
    }

    public void setIcon(ItemStack icon) { this.schematic.setIcon(icon); }

    public ItemStack getIcon() { return this.schematic.getIcon(); }

    public List<TileEntity> getTileEntities() { return this.schematic.getTileEntities(); }

    public List<Entity> getEntities() { return this.schematic.getEntities(); }

    public boolean toggleRendering() {
        this.isRendering = !this.isRendering;
        return this.isRendering;
    }

    /** Chests decide to be single or double from their neighbors; ask them again after a change. */
    public void refreshChests() {
        for (TileEntity tileEntity : this.schematic.getTileEntities()) {
            if (tileEntity instanceof TileEntityChest) {
                ((TileEntityChest) tileEntity).adjacentChestChecked = false;
                ((TileEntityChest) tileEntity).checkForAdjacentChests();
            }
        }
    }

    // ---- rotating and mirroring

    public void flip(EnumFacing.Axis axis) {
        transform(axis, true);
    }

    public void rotate(EnumFacing.Axis axis) {
        transform(axis, false);
    }

    private void transform(EnumFacing.Axis axis, boolean mirror) {
        char operation = axis == EnumFacing.Axis.X ? 'X' : axis == EnumFacing.Axis.Y ? 'Y' : 'Z';
        transform(mirror ? Character.toLowerCase(operation) : operation);
    }

    /** Applies one operation (X, Y, Z: 90 degrees about that axis; x, y, z: mirror across it) to the whole placement. */
    public void transform(char operation) {
        if (placementSettings.locked) return;
        if (subregions != null && subregions.modified()) {
            List<String> operations = new ArrayList<>(transformOperations);
            operations.add(String.valueOf(operation));
            rebuildRegions(subregions, operations);
            return;
        }
        int w = getWidth(), h = getHeight(), l = getLength();
        List<Entity> entities = new ArrayList<>(this.schematic.getEntities());
        if (Character.isUpperCase(operation) && operation != 'Y' || operation == 'y') {
            // Hanging entities cannot face up or down, so the schematic cannot be turned that way with them in it.
            for (Entity entity : entities) {
                if (entity instanceof EntityHanging) {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player != null) mc.player.sendMessage(new TextComponentTranslation("schematica.message.transform.hanging_entities"));
                    return;
                }
            }
        }
        int[] size = PlacementTransform.transformedSize(w, h, l, String.valueOf(operation));
        Schematic result = new Schematic(this.schematic.getIcon(), size[0], size[1], size[2]);
        SchematicOrigin transformedOrigin = schematic.getOrigin().transform(operation, w, h, l);
        SchematicOrigin minimum = transformedOrigin.minimumAt(originPosition());

        // a schematic has few distinct states but may have millions of blocks: turn each state once
        java.util.Map<IBlockState, IBlockState> turned = new java.util.IdentityHashMap<>();
        Schematic.OriginVisitor move = (x, y, z, state, origin) -> {
            double[] p = SchematicTransform.point(operation, x, y, z, w - 1, h - 1, l - 1);
            result.setBlockState((int) p[0], (int) p[1], (int) p[2], turned.computeIfAbsent(state, s -> BlockStateTransform.transform(s, operation)), origin);
        };
        if (this.schematic instanceof Schematic) {
            ((Schematic) this.schematic).forEachBlock(move);
        } else {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    for (int x = 0; x < w; x++) {
                        IBlockState state = this.schematic.getBlockState(x, y, z);
                        if (state.getBlock() != Blocks.AIR) move.visit(x, y, z, state, null);
                    }
                }
            }
        }
        List<SchematicRegion> transformed = new ArrayList<>();
        for (SchematicRegion region : this.schematic.getRegions()) transformed.add(region.transform(operation, w, h, l));
        result.setOrigin(transformedOrigin);
        if (!transformed.isEmpty()) result.setRegions(transformed);

        for (TileEntity tile : new ArrayList<>(this.schematic.getTileEntities())) {
            try {
                NBTTagCompound tag = NBTHelper.writeTileEntityToCompound(tile);
                BlockPos at = tile.getPos();
                double[] p = SchematicTransform.point(operation, at.getX(), at.getY(), at.getZ(), w - 1, h - 1, l - 1);
                tag.setInteger("x", (int) p[0]);
                tag.setInteger("y", (int) p[1]);
                tag.setInteger("z", (int) p[2]);
                if (tag.hasKey("Rot", Constants.NBT.TAG_BYTE) && "minecraft:skull".equals(tag.getString("id"))) {
                    tag.setByte("Rot", (byte) rotation16(tag.getByte("Rot"), operation));
                }
                TileEntity moved = NBTHelper.readTileEntityFromCompound(tag);
                if (moved != null) result.setTileEntity(moved.getPos(), moved);
            } catch (RuntimeException e) {
                Reference.logger.warn("Could not transform tile entity {}", tile, e);
            }
        }
        for (Entity entity : entities) {
            try {
                Entity moved = transformedEntity(entity, operation, w, h, l);
                if (moved != null) result.addEntity(moved);
            } catch (RuntimeException e) {
                Reference.logger.warn("Could not transform entity {}", entity, e);
            }
        }

        this.schematic = result;
        position.set(minimum.x, minimum.y, minimum.z);
        for (TileEntity tileEntity : this.schematic.getTileEntities()) bindTileEntity(tileEntity);
        for (TileEntity tileEntity : this.schematic.getTileEntities()) validateTileEntity(tileEntity);
        refreshChests();
        recordState(operation);
        this.transformOperations.add(String.valueOf(operation));
        this.visibleRegionBlocks = null;
        this.renderingLayer = Math.max(0, Math.min(this.renderingLayer, getHeight() - 1));
        placementRevision++;
        contentRevision++;
    }

    private void recordState(char operation) {
        switch (operation) {
            case 'X': rotationStateX = (rotationStateX + 1) & 3; break;
            case 'Y': rotationStateY = (rotationStateY + 1) & 3; break;
            case 'Z': rotationStateZ = (rotationStateZ + 1) & 3; break;
            case 'x': flipStateX ^= 1; break;
            case 'y': flipStateY ^= 1; break;
            default: flipStateZ ^= 1; break;
        }
    }

    /** A 16-step rotation (skulls, banners, signs) after an operation: the direction it faces is moved with the operation. */
    static int rotation16(int rotation, char operation) {
        double angle = Math.toRadians(rotation * 22.5);
        double[] direction = SchematicTransform.point(operation, -Math.sin(angle), 0, Math.cos(angle), 0, 0, 0);
        if (direction[0] == 0 && direction[2] == 0) return rotation;
        double turned = Math.toDegrees(Math.atan2(-direction[0], direction[2]));
        return Math.floorMod((int) Math.round(turned / 22.5), 16);
    }

    /** A copy of an entity moved with the operation: position, facing, motion, and the wall position of hanging entities. */
    private Entity transformedEntity(Entity entity, char operation, int w, int h, int l) {
        NBTTagCompound tag = NBTHelper.writeEntityToCompound(entity);
        if (tag == null) return null;
        double[] p = SchematicTransform.point(operation, entity.posX, entity.posY, entity.posZ, w, h, l);
        NBTTagList pos = new NBTTagList();
        for (double value : p) pos.appendTag(new net.minecraft.nbt.NBTTagDouble(value));
        tag.setTag("Pos", pos);

        double yaw = Math.toRadians(entity.rotationYaw), pitch = Math.toRadians(entity.rotationPitch);
        double[] look = SchematicTransform.point(operation, -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
            Math.cos(yaw) * Math.cos(pitch), 0, 0, 0);
        NBTTagList rotation = new NBTTagList();
        rotation.appendTag(new NBTTagFloat((float) Math.toDegrees(Math.atan2(-look[0], look[2]))));
        rotation.appendTag(new NBTTagFloat((float) Math.toDegrees(Math.atan2(-look[1], Math.hypot(look[0], look[2])))));
        tag.setTag("Rotation", rotation);

        NBTTagList motion = tag.getTagList("Motion", Constants.NBT.TAG_DOUBLE);
        if (motion.tagCount() == 3) {
            double[] m = SchematicTransform.point(operation, motion.getDoubleAt(0), motion.getDoubleAt(1), motion.getDoubleAt(2), 0, 0, 0);
            NBTTagList moved = new NBTTagList();
            for (double value : m) moved.appendTag(new net.minecraft.nbt.NBTTagDouble(value));
            tag.setTag("Motion", moved);
        }

        if (entity instanceof EntityHanging) {
            double[] anchor = SchematicTransform.point(operation, tag.getInteger("TileX"), tag.getInteger("TileY"),
                tag.getInteger("TileZ"), w - 1, h - 1, l - 1);
            tag.setInteger("TileX", (int) anchor[0]);
            tag.setInteger("TileY", (int) anchor[1]);
            tag.setInteger("TileZ", (int) anchor[2]);
            EnumFacing facing = EnumFacing.getHorizontal(tag.getByte("Facing"));
            tag.setByte("Facing", (byte) SchematicTransform.direction(operation, facing).getHorizontalIndex());
        }

        Entity copy = NBTHelper.readEntityFromCompound(tag, WorldDummy.instance());
        if (copy != null) {
            copy.prevPosX = copy.lastTickPosX = copy.posX;
            copy.prevPosY = copy.lastTickPosY = copy.posY;
            copy.prevPosZ = copy.lastTickPosZ = copy.posZ;
            copy.prevRotationYaw = copy.rotationYaw;
            copy.prevRotationPitch = copy.rotationPitch;
        }
        return copy;
    }

    public Vector3f dimensions() {
        return new Vector3f(this.schematic.getWidth(), this.schematic.getHeight(), this.schematic.getLength());
    }

    public String getDebugDimensions() {
        return "WHL: " + getWidth() + " / " + getHeight() + " / " + getLength();
    }
}
