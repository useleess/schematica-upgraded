package com.github.lunatrius.schematica.client.renderer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.github.lunatrius.schematica.client.renderer.shader.ShaderProgram;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.RenderColors;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3d;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3f;
import com.github.lunatrius.schematica.reference.Constants;
import com.github.lunatrius.schematica.reference.Reference;

/**
 * One 16x16x16 piece of a placement. Its blocks are compiled into GL display lists (pass 0: opaque and cut-out layers,
 * pass 1: translucent) and its overlay (missing, wrong and extra blocks) into another; tile entities draw every frame.
 */
public class RendererSchematicChunk {

    private static final ShaderProgram SHADER_ALPHA = new ShaderProgram("schematica", null, "shaders/alpha.frag");

    private static final BlockRenderLayer[][] PASS_LAYERS = {
        {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED, BlockRenderLayer.CUTOUT},
        {BlockRenderLayer.TRANSLUCENT}};

    public boolean isInFrustrum = false;
    /** Within the render distance (RenderBudget); chunks outside are neither drawn nor rebuilt. */
    public boolean inRange = true;

    public final Vector3d centerPosition = new Vector3d();

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final Profiler profiler = this.minecraft.mcProfiler;
    private final SchematicWorld schematic;
    private final List<TileEntity> tileEntities = new ArrayList<>();

    private final AxisAlignedBB boundingBox;

    private boolean needsUpdate = true;
    private long layerRevision = -1;
    private int originX, originY, originZ, localLayer;
    private boolean localLayerEnabled;
    private int glList = -1;
    private int glListHighlight = -1;
    /** Whether each compiled list holds anything; empty lists are not called. */
    private final boolean[] hasBlocks = new boolean[PASS_LAYERS.length];
    private boolean hasOverlay;

    public RendererSchematicChunk(SchematicWorld schematicWorld, int baseX, int baseY, int baseZ) {
        this(schematicWorld, baseX, baseY, baseZ, schematicWorld.getTileEntities());
    }

    /** @param candidates tile entities that may lie in this piece (those outside it are ignored) */
    public RendererSchematicChunk(SchematicWorld schematicWorld, int baseX, int baseY, int baseZ, List<TileEntity> candidates) {
        this.schematic = schematicWorld;
        this.boundingBox = new AxisAlignedBB(
            baseX * Constants.SchematicChunk.WIDTH,
            baseY * Constants.SchematicChunk.HEIGHT,
            baseZ * Constants.SchematicChunk.LENGTH,
            (baseX + 1) * Constants.SchematicChunk.WIDTH,
            (baseY + 1) * Constants.SchematicChunk.HEIGHT,
            (baseZ + 1) * Constants.SchematicChunk.LENGTH);

        this.centerPosition.x = (int) ((baseX + 0.5) * Constants.SchematicChunk.WIDTH);
        this.centerPosition.y = (int) ((baseY + 0.5) * Constants.SchematicChunk.HEIGHT);
        this.centerPosition.z = (int) ((baseZ + 0.5) * Constants.SchematicChunk.LENGTH);

        for (TileEntity tileEntity : candidates) {
            BlockPos pos = tileEntity.getPos();
            if (pos.getX() < this.boundingBox.minX || pos.getX() >= this.boundingBox.maxX
                || pos.getZ() < this.boundingBox.minZ || pos.getZ() >= this.boundingBox.maxZ
                || pos.getY() < this.boundingBox.minY || pos.getY() >= this.boundingBox.maxY) {
                continue;
            }
            this.tileEntities.add(tileEntity);
        }

        this.glList = GLAllocation.generateDisplayLists(PASS_LAYERS.length);
        this.glListHighlight = GLAllocation.generateDisplayLists(1);
    }

    public void delete() {
        if (this.glList != -1) {
            GLAllocation.deleteDisplayLists(this.glList, PASS_LAYERS.length);
            this.glList = -1;
        }
        if (this.glListHighlight != -1) {
            GLAllocation.deleteDisplayLists(this.glListHighlight, 1);
            this.glListHighlight = -1;
        }
    }

    public AxisAlignedBB getBoundingBox() {
        return this.boundingBox;
    }

    public void setDirty() {
        this.needsUpdate = true;
    }

    public boolean getDirty() {
        return this.needsUpdate
            || layerRevision != RenderLayerSettings.RANGE.revision()
            || originX != schematic.position.x || originY != schematic.position.y || originZ != schematic.position.z
            || localLayer != schematic.renderingLayer || localLayerEnabled != schematic.isRenderingLayer;
    }

    /** Recompiles the display lists of this piece. */
    public void updateRenderer() {
        if (!getDirty() || this.glList == -1) return;
        this.needsUpdate = false;
        layerRevision = RenderLayerSettings.RANGE.revision();
        originX = schematic.position.x; originY = schematic.position.y; originZ = schematic.position.z;
        localLayer = schematic.renderingLayer; localLayerEnabled = schematic.isRenderingLayer;

        int minX = (int) this.boundingBox.minX;
        int maxX = Math.min((int) this.boundingBox.maxX, this.schematic.getWidth());
        int minY = (int) this.boundingBox.minY;
        int maxY = Math.min((int) this.boundingBox.maxY, this.schematic.getHeight());
        int minZ = (int) this.boundingBox.minZ;
        int maxZ = Math.min((int) this.boundingBox.maxZ, this.schematic.getLength());

        int[] limits = schematic.renderBounds();
        minX = Math.max(minX, limits[0]); maxX = Math.min(maxX, limits[3]);
        minY = Math.max(minY, limits[1]); maxY = Math.min(maxY, limits[4]);
        minZ = Math.max(minZ, limits[2]); maxZ = Math.min(maxZ, limits[5]);

        Cells cells = collectCells(minX, minY, minZ, maxX, maxY, maxZ);
        for (int pass = 0; pass < PASS_LAYERS.length; pass++) {
            this.hasBlocks[pass] = false;
            if (cells.count == 0) continue;
            GL11.glNewList(this.glList + pass, GL11.GL_COMPILE);
            try {
                this.hasBlocks[pass] = renderBlocks(pass, cells);
            } catch (RuntimeException e) {
                Reference.logger.error("Failed to compile schematic blocks", e);
            } finally {
                GL11.glEndList();
            }
        }

        this.hasOverlay = false;
        if (!ConfigurationHandler.highlight || cells.count == 0 && !ConfigurationHandler.highlightAir) return;
        RenderHelper.createBuffers();
        try {
            if (ConfigurationHandler.highlightAir) buildOverlay(minX, minY, minZ, maxX, maxY, maxZ);
            else buildOverlay(cells);
            this.hasOverlay = RenderHelper.getQuadCount() > 0 || RenderHelper.getLineCount() > 0;
            if (this.hasOverlay) {
                GL11.glNewList(this.glListHighlight, GL11.GL_COMPILE);
                try {
                    drawOverlayBuffers();
                } finally {
                    GL11.glEndList();
                }
            }
        } catch (RuntimeException e) {
            this.hasOverlay = false;
            Reference.logger.error("Failed to compile the schematic overlay", e);
        } finally {
            RenderHelper.destroyBuffers();
        }
    }

    /** The shown, non-air blocks of a box in this piece. */
    private static final class Cells {
        int count;
        int[] xs = new int[64], ys = new int[64], zs = new int[64];
        IBlockState[] states = new IBlockState[64];

        void add(int x, int y, int z, IBlockState state) {
            if (count == xs.length) {
                int size = count * 2;
                xs = java.util.Arrays.copyOf(xs, size); ys = java.util.Arrays.copyOf(ys, size); zs = java.util.Arrays.copyOf(zs, size);
                states = java.util.Arrays.copyOf(states, size);
            }
            xs[count] = x; ys[count] = y; zs[count] = z; states[count] = state;
            count++;
        }
    }

    /** Lists the shown, non-air blocks of the box; with sectioned storage only allocated (not all air) sections are read. */
    private Cells collectCells(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Cells cells = new Cells();
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return cells;
        com.github.lunatrius.schematica.api.ISchematic stored = this.schematic.getSchematic();
        if (stored instanceof com.github.lunatrius.schematica.world.storage.Schematic) {
            com.github.lunatrius.schematica.world.storage.Schematic sectioned = (com.github.lunatrius.schematica.world.storage.Schematic) stored;
            // pieces and storage sections are both 16^3 from the schematic's corner, so the box lies in one section
            sectioned.forEachBlockInSection(sectioned.sectionAt(minX, minY, minZ), new int[] {minX, minY, minZ, maxX, maxY, maxZ},
                (x, y, z, state) -> addCell(cells, x, y, z, state));
            return cells;
        }
        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    addCell(cells, x, y, z, stored.getBlockState(x, y, z));
                }
            }
        }
        return cells;
    }

    private void addCell(Cells cells, int x, int y, int z, IBlockState state) {
        if (!schematic.isBlockRendered(x, y, z)) return;
        if (state.getBlock().isAir(state, this.schematic, new BlockPos(x, y, z))) return;
        cells.add(x, y, z, state);
    }

    private void drawOverlayBuffers() {
        int quadCount = RenderHelper.getQuadCount();
        int lineCount = RenderHelper.getLineCount();
        if (quadCount == 0 && lineCount == 0) return;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        if (quadCount > 0) {
            GL11.glVertexPointer(3, 0, RenderHelper.getQuadVertexBuffer());
            GL11.glColorPointer(4, 0, RenderHelper.getQuadColorBuffer());
            GL11.glDrawArrays(GL11.GL_QUADS, 0, quadCount);
        }

        if (lineCount > 0) {
            GL11.glVertexPointer(3, 0, RenderHelper.getLineVertexBuffer());
            GL11.glColorPointer(4, 0, RenderHelper.getLineColorBuffer());
            GL11.glDrawArrays(GL11.GL_LINES, 0, lineCount);
        }

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        // drawing with a colour array leaves the current colour at the last vertex's (translucent blue)
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    boolean hasTileEntities() {
        return !this.tileEntities.isEmpty();
    }

    /**
     * Draws one pass of a placement's pieces; the matrix is already translated to the placement. The GL state is set
     * once for all pieces (not per piece), and pieces outside the view or without anything compiled are skipped.
     */
    static void renderPass(List<RendererSchematicChunk> chunks, int renderPass, boolean reverse, float partialTicks) {
        if (renderPass >= PASS_LAYERS.length) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        Profiler profiler = minecraft.mcProfiler;
        int size = chunks.size();
        int previousProgram = OpenGlHelper.shadersSupported ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        profiler.startSection("blocks");
        try {
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableCull();
            GlStateManager.color(1, 1, 1, 1);
            boolean alpha = OpenGlHelper.shadersSupported && ConfigurationHandler.enableAlpha && SHADER_ALPHA.getProgram() > 0;
            GlStateManager.depthMask(renderPass == 0 && (!alpha || ConfigurationHandler.alpha >= 1));
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            if (VisualSettings.frameBlocks) {
                boolean any = false;
                for (int i = 0; i < size && !any; i++) {
                    RendererSchematicChunk chunk = chunks.get(i);
                    any = chunk.isInFrustrum && chunk.glList != -1 && chunk.hasBlocks[renderPass];
                }
                if (any) {
                    minecraft.entityRenderer.enableLightmap();
                    if (alpha) {
                        GL20.glUseProgram(SHADER_ALPHA.getProgram());
                        GL20.glUniform1f(GL20.glGetUniformLocation(SHADER_ALPHA.getProgram(), "alpha_multiplier"),
                            ConfigurationHandler.alpha);
                    }
                    for (int i = 0; i < size; i++) {
                        RendererSchematicChunk chunk = chunks.get(reverse ? size - 1 - i : i);
                        if (chunk.isInFrustrum && chunk.glList != -1 && chunk.hasBlocks[renderPass]) {
                            GlStateManager.callList(chunk.glList + renderPass);
                        }
                    }
                    if (alpha) GL20.glUseProgram(previousProgram);
                    minecraft.entityRenderer.disableLightmap();
                }
            }
            if (renderPass == 1) {
                profiler.endStartSection("highlight");
                GlStateManager.depthMask(false);
                if (VisualSettings.frameOverlay) {
                    if (VisualSettings.frameThrough) GlStateManager.disableDepth();
                    GL11.glLineWidth(VisualSettings.frameOutlineWidth());
                    for (int i = 0; i < size; i++) {
                        RendererSchematicChunk chunk = chunks.get(reverse ? size - 1 - i : i);
                        if (chunk.isInFrustrum && chunk.glListHighlight != -1 && chunk.hasOverlay) {
                            GlStateManager.callList(chunk.glListHighlight);
                        }
                    }
                    GlStateManager.enableDepth();
                }
            }
            if (renderPass == 0) {
                profiler.endStartSection("tileEntities");
                if (VisualSettings.frameSchematic && VisualSettings.renderTileEntities) {
                    for (int i = 0; i < size; i++) {
                        RendererSchematicChunk chunk = chunks.get(i);
                        if (chunk.isInFrustrum && chunk.glList != -1 && chunk.hasTileEntities()) chunk.renderTileEntities(partialTicks);
                    }
                }
            }
        } finally {
            if (OpenGlHelper.shadersSupported) GL20.glUseProgram(previousProgram);
            // the lists' vertex colours leave the GL colour tinted while the cache still says white
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
            profiler.endSection();
        }
    }

    public static boolean isFluid(Block block) {
        return block instanceof BlockLiquid || block instanceof IFluidBlock;
    }

    /** Air in the world for the overlay: air, an extra air block, or a fluid with ignoreExistingFluids. */
    private static boolean worldAir(World world, IBlockState state, BlockPos pos) {
        return state.getBlock().isAir(state, world, pos) || ConfigurationHandler.isExtraAirBlock(state.getBlock())
            || VisualSettings.ignoreExistingFluids && isFluid(state.getBlock());
    }

    /** The overlay at a schematic position, or null. */
    private RenderColors overlayAt(World world, int x, int y, int z) {
        if (!schematic.isBlockRendered(x, y, z)) return null;
        IBlockState state = schematic.getBlockState(new BlockPos(x, y, z));
        boolean air = schematic.isAirBlock(new BlockPos(x, y, z));
        if (!air && !VisualSettings.fluids && isFluid(state.getBlock())) return null;
        BlockPos worldPos = new BlockPos(schematic.position.x + x, schematic.position.y + y, schematic.position.z + z);
        IBlockState real = world.getBlockState(worldPos);
        if (worldAir(world, real, worldPos)) return air || !VisualSettings.overlayMissing ? null : RenderColors.MISSING;
        if (air) return ConfigurationHandler.highlightAir ? RenderColors.EXTRA : null;
        return overlayColor(state, real);
    }

    /** The overlay of a schematic block standing where another block is, or null when that overlay type is off. */
    static RenderColors overlayColor(IBlockState expected, IBlockState found) {
        switch (BlockCompare.compare(expected, found)) {
            case WRONG_BLOCK: return VisualSettings.overlayWrongBlock ? RenderColors.WRONG_BLOCK : null;
            case WRONG_STATE: return VisualSettings.overlayWrongState ? RenderColors.WRONG_STATE : null;
            case DIFFERENT_BLOCK: return VisualSettings.overlayDiffBlock ? RenderColors.DIFFERENT_TYPE : null;
            default: return null;
        }
    }

    private static final EnumFacing[] SIDE_ORDER = {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
    private static final int[] QUADS = {RenderHelper.QUAD_DOWN, RenderHelper.QUAD_UP, RenderHelper.QUAD_NORTH, RenderHelper.QUAD_SOUTH,
        RenderHelper.QUAD_WEST, RenderHelper.QUAD_EAST};

    /**
     * The overlay box: the block's model bounds with schematicOverlayModelSides/Outline, else the full block; faces hidden by
     * the schematic's neighbours are culled (enableSchematicOverlayCulling), and with overlayReducedInnerSides also the faces
     * between two overlays of the same type.
     */
    private void drawOverlay(World world, RenderColors color, IBlockState state, boolean air, int x, int y, int z, Vector3f zero, Vector3f size) {
        int sides = 0;
        BlockPos pos = new BlockPos(x, y, z);
        for (int i = 0; i < 6; i++) {
            EnumFacing side = SIDE_ORDER[i];
            int nx = x + side.getFrontOffsetX(), ny = y + side.getFrontOffsetY(), nz = z + side.getFrontOffsetZ();
            boolean visible = air || !VisualSettings.overlayCulling || !schematic.isBlockRendered(nx, ny, nz)
                || state.shouldSideBeRendered(this.schematic, pos, side);
            if (visible && VisualSettings.reducedInnerSides && color == overlayAt(world, nx, ny, nz)) visible = false;
            if (visible) sides |= QUADS[i];
        }
        float[] full = {x, y, z, x + 1, y + 1, z + 1}, model = full;
        if (!air && (VisualSettings.modelSides || VisualSettings.modelOutline)) {
            try {
                AxisAlignedBB box = state.getBoundingBox(this.schematic, pos);
                float[] bounds = {x + (float) box.minX, y + (float) box.minY, z + (float) box.minZ,
                    x + (float) box.maxX, y + (float) box.maxY, z + (float) box.maxZ};
                if (bounds[3] > bounds[0] && bounds[4] > bounds[1] && bounds[5] > bounds[2]) model = bounds;
            } catch (RuntimeException ignored) {}
        }
        if (ConfigurationHandler.drawQuads) {
            float[] box = VisualSettings.modelSides ? model : full;
            zero.set(box[0], box[1], box[2]);
            size.set(box[3], box[4], box[5]);
            RenderHelper.drawCuboidSurface(zero, size, box == full ? sides : RenderHelper.QUAD_ALL, color.color());
        }
        if (ConfigurationHandler.drawLines) {
            float[] box = VisualSettings.modelOutline ? model : full;
            zero.set(box[0], box[1], box[2]);
            size.set(box[3], box[4], box[5]);
            RenderHelper.drawCuboidOutline(zero, size, air ? RenderHelper.LINE_ALL : box == full ? sides : RenderHelper.LINE_ALL, color.color());
        }
    }

    private void buildOverlay(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        World world = this.minecraft.world;
        if (world == null) return;
        Vector3f zero = new Vector3f();
        Vector3f size = new Vector3f();
        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    if (!schematic.isBlockRendered(x, y, z)) continue;
                    try {
                        IBlockState state = this.schematic.getBlockState(new BlockPos(x, y, z));
                        boolean air = this.schematic.isAirBlock(new BlockPos(x, y, z));
                        RenderColors color = overlayAt(world, x, y, z);
                        if (color != null) drawOverlay(world, color, state, air, x, y, z, zero, size);
                    } catch (Exception e) {
                        Reference.logger.error("Failed to build an overlay!", e);
                    }
                }
            }
        }
    }

    /** The overlay of the listed (non-air) blocks: without highlightAir, air never gets one. */
    private void buildOverlay(Cells cells) {
        World world = this.minecraft.world;
        if (world == null) return;
        Vector3f zero = new Vector3f();
        Vector3f size = new Vector3f();
        for (int i = 0; i < cells.count; i++) {
            int x = cells.xs[i], y = cells.ys[i], z = cells.zs[i];
            try {
                RenderColors color = overlayAt(world, x, y, z);
                if (color != null) drawOverlay(world, color, cells.states[i], false, x, y, z, zero, size);
            } catch (Exception e) {
                Reference.logger.error("Failed to build an overlay!", e);
            }
        }
    }

    /** Compiles the listed blocks of one pass into the current display list; returns whether any block was drawn. */
    private boolean renderBlocks(int pass, Cells cells) {
        World world = this.minecraft.world;
        BlockRendererDispatcher dispatcher = this.minecraft.getBlockRendererDispatcher();
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        boolean drawn = false;

        int ambientOcclusion = this.minecraft.gameSettings.ambientOcclusion;
        if (!VisualSettings.aoModern) this.minecraft.gameSettings.ambientOcclusion = 0;
        boolean renderedView = this.schematic.setRenderedView(true);
        try {
            for (BlockRenderLayer layer : PASS_LAYERS[pass]) {
                int first = -1;
                for (int i = 0; i < cells.count && first < 0; i++) if (cells.states[i].getBlock().canRenderInLayer(cells.states[i], layer)) first = i;
                if (first < 0) continue;
                try (SchematicRenderPass context = new SchematicRenderPass(layer)) {
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    try {
                        for (int i = first; i < cells.count; i++) {
                            if (renderBlock(dispatcher, buffer, world, layer, cells.xs[i], cells.ys[i], cells.zs[i], cells.states[i])) drawn = true;
                        }
                    } finally {
                        Tessellator.getInstance().draw();
                    }
                }
            }
        } finally {
            this.schematic.setRenderedView(renderedView);
            this.minecraft.gameSettings.ambientOcclusion = ambientOcclusion;
        }
        return drawn;
    }

    private boolean renderBlock(BlockRendererDispatcher dispatcher, BufferBuilder buffer, World world, BlockRenderLayer layer, int x, int y, int z,
        IBlockState state) {
        try {
            Block block = state.getBlock();
            if (state.getRenderType() == EnumBlockRenderType.INVISIBLE) return false;
            if (!block.canRenderInLayer(state, layer)) return false;
            // enableSchematicFluidRendering off: no fluid and no overlay where the fluid is
            if (!VisualSettings.fluids && isFluid(block)) return false;

            if (world != null) {
                BlockPos worldPos = new BlockPos(this.schematic.position.x + x, this.schematic.position.y + y, this.schematic.position.z + z);
                IBlockState real = world.getBlockState(worldPos);
                boolean realAir = worldAir(world, real, worldPos);
                if (!realAir && !(VisualSettings.renderColliding && BlockCompare.compare(state, real) != BlockCompare.Result.SAME)) return false;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (VisualSettings.translucentInnerSides && ConfigurationHandler.enableAlpha && state.getRenderType() == EnumBlockRenderType.MODEL) {
                // translucentBlockInnerSides: every model face, also those hidden by the neighbouring schematic blocks
                IBlockState actual = state.getActualState(this.schematic, pos);
                dispatcher.getBlockModelRenderer().renderModel(this.schematic, dispatcher.getModelForState(actual),
                    actual.getBlock().getExtendedState(actual, this.schematic, pos), pos, buffer, false);
            } else dispatcher.renderBlock(state, pos, this.schematic, buffer);
            return true;
        } catch (Exception e) {
            Reference.logger.error("Failed to render block!", e);
            return false;
        }
    }

    /** Draws the tile entities of this piece that stand where the world has air. */
    public void renderTileEntities(float partialTicks) {
        World world = this.minecraft.world;
        if (world == null) return;
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        for (TileEntity tileEntity : this.tileEntities) {
            BlockPos pos = tileEntity.getPos();
            if (!this.schematic.isBlockRendered(pos.getX(), pos.getY(), pos.getZ())) continue;
            BlockPos worldPos = pos.add(this.schematic.position.x, this.schematic.position.y, this.schematic.position.z);
            if (!world.isAirBlock(worldPos)) continue;
            if (dispatcher.getRenderer(tileEntity) == null) continue;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.color(1.0f, 1.0f, 1.0f, ConfigurationHandler.alpha);
                // Opaque models need depth writes; transparent chest faces must not hide their contents.
                GlStateManager.depthMask(true);
                int light = this.schematic.getCombinedLight(pos, 0);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
                dispatcher.render(tileEntity, pos.getX(), pos.getY(), pos.getZ(), partialTicks, -1, 1.0f);
            } catch (Exception e) {
                Reference.logger.error("Failed to render a tile entity!", e);
            } finally {
                GlStateManager.popMatrix();
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }
}
