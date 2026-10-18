package com.github.lunatrius.schematica.client.renderer;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3d;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Constants;
import com.github.lunatrius.schematica.reference.Reference;

/** Draws every visible placement: block lists, overlays, tile entities, entities and the placement boxes. */
@SideOnly(Side.CLIENT)
public class RendererSchematicGlobal {

    public static final RendererSchematicGlobal INSTANCE = new RendererSchematicGlobal();

    private static final int PASSES = 2;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final Profiler profiler = this.minecraft.mcProfiler;

    private final SchematicFrustum frustum = new SchematicFrustum();
    private final Vector3d cameraPosition = new Vector3d();
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final float[] projection = new float[16];
    private final float[] modelView = new float[16];
    private final RendererSchematicChunkComparator rendererSchematicChunkComparator = new RendererSchematicChunkComparator();
    private final RenderUpdateScheduler<RendererSchematicChunk> updateScheduler = new RenderUpdateScheduler<>();
    private final RenderBudget budget = new RenderBudget();

    /** Per-placement chunk renderers. */
    private final Map<SchematicWorld, List<RendererSchematicChunk>> renderDataMap = new HashMap<>();
    /** The same renderers by piece grid position, so a world change only looks at the pieces it touches. */
    private final Map<SchematicWorld, PieceGrid> pieceGrids = new HashMap<>();

    private static final class PieceGrid {
        final int width, height, length;
        final Map<Integer, RendererSchematicChunk> pieces = new HashMap<>();

        PieceGrid(int width, int height, int length) {
            this.width = width;
            this.height = height;
            this.length = length;
        }
    }

    private RendererSchematicGlobal() {}

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        EntityPlayerSP player = this.minecraft.player;
        if (player == null || this.minecraft.world == null) return;
        float partialTicks = event.getPartialTicks();
        ClientProxy.setPlayerData(player, partialTicks);

        this.profiler.startSection("schematica");
        boolean anyRendering = false;
        for (SchematicWorld sw : ClientProxy.visiblePlacements()) {
            if (sw.isEnabled()) {
                anyRendering = true;
                break;
            }
        }

        if (anyRendering && this.budget.frame(System.nanoTime()) && this.minecraft.ingameGUI != null) {
            this.minecraft.ingameGUI.setOverlayMessage(
                net.minecraft.client.resources.I18n.format("schematica.message.render_distance_reduced", VisualSettings.adaptiveMinFps), false);
        }
        if (VisualSettings.rendering && (anyRendering || ClientProxy.isRenderingGuide)) {
            VisualSettings.beginFrame();
            Entity camera = this.minecraft.getRenderViewEntity();
            if (camera == null) camera = player;
            this.cameraPosition.set(
                camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks,
                camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks,
                camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks);
            captureMatrix(GL11.GL_PROJECTION_MATRIX, this.projection);
            captureMatrix(GL11.GL_MODELVIEW_MATRIX, this.modelView);
            this.frustum.update(this.projection, this.modelView);
            int matrixDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
            // Vanilla tile entity renderers (beacon, end gateway...) and our overlays end with enableFog / enableLightmap,
            // which is right inside the world pass but not here: vanilla has switched both off before RenderWorldLast,
            // so they would stay on for the hand and the whole GUI (washed out sky-blue screen).
            boolean fog = GL11.glIsEnabled(GL11.GL_FOG);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            try {
                renderAll(partialTicks);
                RebuildOverlayRenderer.render(this.minecraft, this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
                com.github.lunatrius.schematica.client.printer.PrinterHighlights.render(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
                com.github.lunatrius.schematica.client.printer.NextBlockHint.render(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
                VerifierOverlayRenderer.render(this.minecraft, partialTicks, this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
            } catch (Throwable error) {
                renderFailed(error, matrixDepth);
            } finally {
                restoreState(fog, lightmap);
            }
        }
        this.profiler.endSection();
    }

    /** Puts fog, lightmap and colour back as they were before the schematic was drawn. Each toggle is done twice so
     *  the GL call happens even when GlStateManager's cache already (wrongly) holds the wanted value. */
    private void restoreState(boolean fog, boolean lightmap) {
        if (fog) {
            GlStateManager.disableFog();
            GlStateManager.enableFog();
        } else {
            GlStateManager.enableFog();
            GlStateManager.disableFog();
        }
        if (lightmap) {
            this.minecraft.entityRenderer.disableLightmap();
            this.minecraft.entityRenderer.enableLightmap();
        } else {
            this.minecraft.entityRenderer.enableLightmap();
            this.minecraft.entityRenderer.disableLightmap();
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** A render error must not crash the game: log it, put the GL state back, and hide the placements. */
    private void renderFailed(Throwable error, int matrixDepth) {
        Reference.logger.error("Schematic rendering failed; the placements are hidden (use /splus show to try again)", error);
        try {
            // A draw that threw between begin() and draw() leaves the shared buffer open ("Already building!" next frame).
            net.minecraft.client.renderer.BufferBuilder buffer = net.minecraft.client.renderer.Tessellator.getInstance().getBuffer();
            try {
                buffer.finishDrawing();
            } catch (IllegalStateException notBuilding) {
                // nothing was open
            }
            while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > matrixDepth) GL11.glPopMatrix();
            if (OpenGlHelper.shadersSupported) GL20.glUseProgram(0);
            this.minecraft.entityRenderer.disableLightmap();
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.resetColor();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // the GL state could not be restored; nothing more can be done here
        }
        for (SchematicWorld sw : ClientProxy.visiblePlacements()) sw.isRendering = false;
        if (this.minecraft.player != null) {
            this.minecraft.player.sendMessage(new net.minecraft.util.text.TextComponentString(
                net.minecraft.util.text.TextFormatting.RED + "Schematica Plus: rendering failed and was switched off ("
                    + error.getClass().getSimpleName() + "). See logs/latest.log."));
        }
    }

    public void renderAll(float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        this.profiler.startSection("schematic");
        for (SchematicWorld sw : ClientProxy.visiblePlacements()) {
            sw.setWorldTime(this.minecraft.world.getWorldTime());
            sw.getWorldInfo().setWorldTotalTime(this.minecraft.world.getTotalWorldTime());
        }
        updateRenderers();

        for (SchematicWorld sw : ClientProxy.visiblePlacements()) {
            if (!sw.isEnabled()) continue;

            List<RendererSchematicChunk> chunks = renderDataMap.get(sw);
            if (chunks == null || chunks.isEmpty()) continue;

            GlStateManager.pushMatrix();
            Vector3d local = this.cameraPosition.clone();
            local.sub(sw.position.toVector3d());
            GlStateManager.translate(-local.x, -local.y, -local.z);

            for (int pass = 0; sw.isRenderingEnabled() && pass < PASSES; pass++) {
                boolean reverse = pass == 1 || (pass == 0 && ConfigurationHandler.enableAlpha);
                RendererSchematicChunk.renderPass(chunks, pass, reverse, partialTicks);
            }
            GlStateManager.popMatrix();

            if (sw.isRenderingEnabled() && sw.isRenderingEntities && VisualSettings.frameSchematic && VisualSettings.renderEntities) {
                renderEntities(sw, local, partialTicks);
            }
        }

        this.profiler.endStartSection("boxes");
        new BoxRenderer(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z).render();
        com.github.lunatrius.schematica.client.container.ContainerLabels.render(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);

        this.profiler.endStartSection("projects");
        renderProjectOrigin();
        this.profiler.endSection();

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    /** OverlayRenderer render_projects: the open project's origin, whether or not area boxes are shown. */
    private void renderProjectOrigin() {
        com.github.lunatrius.schematica.client.projects.SchematicProject project = com.github.lunatrius.schematica.client.projects.SchematicProjects.current();
        if (project == null) return;
        com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i origin = project.origin();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableLighting();
        GlStateManager.disableTexture2D();
        GL11.glLineWidth(4.0f);
        net.minecraft.client.renderer.BufferBuilder buffer = net.minecraft.client.renderer.Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_LINES, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
        double x0 = origin.x - 0.001 - this.cameraPosition.x, y0 = origin.y - 0.001 - this.cameraPosition.y, z0 = origin.z - 0.001 - this.cameraPosition.z;
        double x1 = x0 + 1.002, y1 = y0 + 1.002, z1 = z0 + 1.002;
        double[][] edges = {{x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
            {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
            {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (double[] e : edges) {
            buffer.pos(e[0], e[1], e[2]).color(1f, 0.0625f, 1f, 1f).endVertex();
            buffer.pos(e[3], e[4], e[5]).color(1f, 0.0625f, 1f, 1f).endVertex();
        }
        net.minecraft.client.renderer.Tessellator.getInstance().draw();
        GL11.glLineWidth(1.0f);
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    private void updateRenderers() {
        List<List<RendererSchematicChunk>> groups = new ArrayList<>();
        for (SchematicWorld schematic : ClientProxy.visiblePlacements()) {
            if (!schematic.isRenderingEnabled()) continue;
            List<RendererSchematicChunk> chunks = renderDataMap.get(schematic);
            if (chunks == null) continue;
            double offsetX = schematic.position.x - this.cameraPosition.x;
            double offsetY = schematic.position.y - this.cameraPosition.y;
            double offsetZ = schematic.position.z - this.cameraPosition.z;
            double limit = this.budget.limit();
            for (RendererSchematicChunk chunk : chunks) {
                AxisAlignedBB box = chunk.getBoundingBox();
                chunk.inRange = limit == RenderBudget.UNLIMITED || RenderBudget.distance(0, 0, 0, box.minX + offsetX, box.minY + offsetY,
                    box.minZ + offsetZ, box.maxX + offsetX, box.maxY + offsetY, box.maxZ + offsetZ) <= limit;
                chunk.isInFrustrum = chunk.inRange && this.frustum.isVisible(
                    box.minX + offsetX, box.minY + offsetY, box.minZ + offsetZ,
                    box.maxX + offsetX, box.maxY + offsetY, box.maxZ + offsetZ);
            }
            this.rendererSchematicChunkComparator.setPosition(schematic.position, this.cameraPosition);
            chunks.sort(this.rendererSchematicChunkComparator);
            groups.add(chunks);
        }
        long deadline = System.nanoTime() + 4_000_000L;
        updateScheduler.update(groups, 32, () -> System.nanoTime() < deadline,
            chunk -> chunk.inRange && chunk.getDirty(), RendererSchematicChunk::updateRenderer);
    }

    /** Creates (or recreates) the chunk renderers of a placement. */
    public void createRendererSchematicChunks(SchematicWorld schematic) {
        int width = (schematic.getWidth() - 1) / Constants.SchematicChunk.WIDTH + 1;
        int height = (schematic.getHeight() - 1) / Constants.SchematicChunk.HEIGHT + 1;
        int length = (schematic.getLength() - 1) / Constants.SchematicChunk.LENGTH + 1;

        removeRendererSchematicChunks(schematic);

        // only pieces that touch a region get a renderer: a big, mostly empty box would otherwise need hundreds of thousands
        List<com.github.lunatrius.schematica.api.SchematicRegion> regions = schematic.getSchematic().getRegions();
        java.util.BitSet used = null;
        if (!regions.isEmpty()) {
            used = new java.util.BitSet();
            for (com.github.lunatrius.schematica.api.SchematicRegion region : regions) {
                for (int y = region.minY / Constants.SchematicChunk.HEIGHT; y <= region.maxY / Constants.SchematicChunk.HEIGHT; y++) {
                    for (int z = region.minZ / Constants.SchematicChunk.LENGTH; z <= region.maxZ / Constants.SchematicChunk.LENGTH; z++) {
                        for (int x = region.minX / Constants.SchematicChunk.WIDTH; x <= region.maxX / Constants.SchematicChunk.WIDTH; x++) {
                            used.set(x + width * (z + length * y));
                        }
                    }
                }
            }
        }
        java.util.Map<Integer, List<TileEntity>> tiles = new java.util.HashMap<>();
        for (TileEntity tile : schematic.getTileEntities()) {
            BlockPos pos = tile.getPos();
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0) continue;
            int x = pos.getX() / Constants.SchematicChunk.WIDTH, y = pos.getY() / Constants.SchematicChunk.HEIGHT, z = pos.getZ() / Constants.SchematicChunk.LENGTH;
            if (x >= width || y >= height || z >= length) continue;
            tiles.computeIfAbsent(x + width * (z + length * y), key -> new ArrayList<>()).add(tile);
        }

        List<RendererSchematicChunk> chunks = new ArrayList<>();
        PieceGrid grid = new PieceGrid(width, height, length);
        try {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        int index = x + width * (z + length * y);
                        if (used != null && !used.get(index)) continue;
                        RendererSchematicChunk chunk = new RendererSchematicChunk(schematic, x, y, z, tiles.getOrDefault(index, java.util.Collections.emptyList()));
                        chunks.add(chunk);
                        grid.pieces.put(index, chunk);
                    }
                }
            }
        } catch (RuntimeException e) {
            for (RendererSchematicChunk chunk : chunks) chunk.delete();
            throw e;
        }
        renderDataMap.put(schematic, chunks);
        pieceGrids.put(schematic, grid);
    }

    /** Makes sure a placement has chunk renderers. */
    public void selectSchematic(SchematicWorld schematic) {
        if (schematic != null && !renderDataMap.containsKey(schematic)) createRendererSchematicChunks(schematic);
    }

    public void removeRendererSchematicChunks(SchematicWorld schematic) {
        pieceGrids.remove(schematic);
        List<RendererSchematicChunk> chunks = renderDataMap.remove(schematic);
        if (chunks != null) {
            for (RendererSchematicChunk chunk : chunks) chunk.delete();
            chunks.clear();
        }
    }

    public void destroyRendererSchematicChunks() {
        for (List<RendererSchematicChunk> chunks : renderDataMap.values()) {
            for (RendererSchematicChunk chunk : chunks) chunk.delete();
            chunks.clear();
        }
        renderDataMap.clear();
        pieceGrids.clear();
    }

    public void refresh() {
        for (List<RendererSchematicChunk> chunks : renderDataMap.values()) {
            for (RendererSchematicChunk chunk : chunks) chunk.setDirty();
        }
    }

    /** Refreshes only the chunks of one placement. */
    public void refresh(SchematicWorld schematic) {
        List<RendererSchematicChunk> chunks = renderDataMap.get(schematic);
        if (chunks != null) {
            for (RendererSchematicChunk chunk : chunks) chunk.setDirty();
        }
    }

    /** The world blocks in the given box changed: their placements' chunks that overlap it are recompiled. */
    public void markDirtyAllSchematics(final int wx0, final int wy0, final int wz0, final int wx1, final int wy1, final int wz1) {
        for (Map.Entry<SchematicWorld, PieceGrid> entry : pieceGrids.entrySet()) {
            SchematicWorld sw = entry.getKey();
            PieceGrid grid = entry.getValue();
            // local box, clamped to the piece grid (long math: world boxes may be huge or far away)
            int x0 = pieceIndex((long) wx0 - sw.position.x, Constants.SchematicChunk.WIDTH, grid.width);
            int x1 = pieceIndex((long) wx1 - sw.position.x, Constants.SchematicChunk.WIDTH, grid.width);
            int y0 = pieceIndex((long) wy0 - sw.position.y, Constants.SchematicChunk.HEIGHT, grid.height);
            int y1 = pieceIndex((long) wy1 - sw.position.y, Constants.SchematicChunk.HEIGHT, grid.height);
            int z0 = pieceIndex((long) wz0 - sw.position.z, Constants.SchematicChunk.LENGTH, grid.length);
            int z1 = pieceIndex((long) wz1 - sw.position.z, Constants.SchematicChunk.LENGTH, grid.length);
            if (x1 < 0 || y1 < 0 || z1 < 0 || x0 >= grid.width || y0 >= grid.height || z0 >= grid.length) continue;
            x0 = Math.max(0, x0); y0 = Math.max(0, y0); z0 = Math.max(0, z0);
            x1 = Math.min(grid.width - 1, x1); y1 = Math.min(grid.height - 1, y1); z1 = Math.min(grid.length - 1, z1);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        RendererSchematicChunk renderer = grid.pieces.get(x + grid.width * (z + grid.length * y));
                        if (renderer != null) renderer.setDirty();
                    }
                }
            }
        }
    }

    /** The piece column/row holding a local coordinate, kept within -1..count so callers can tell "outside". */
    private static int pieceIndex(long local, int size, int count) {
        if (local < 0) return -1;
        return (int) Math.min(count, local / size);
    }

    private void captureMatrix(int matrix, float[] values) {
        this.matrixBuffer.clear();
        GL11.glGetFloat(matrix, this.matrixBuffer);
        this.matrixBuffer.position(0);
        this.matrixBuffer.get(values);
    }

    private void renderEntities(SchematicWorld schematic, Vector3d localCamera, float partialTicks) {
        RenderManager renderManager = this.minecraft.getRenderManager();
        boolean hitboxes = renderManager.isDebugBoundingBox();
        // enableSchematicEntityHitboxes: schematic entities show their hitboxes only while the hitbox renderer is on
        renderManager.setDebugBoundingBox(hitboxes && VisualSettings.entityHitboxes);
        renderManager.setRenderPosition(localCamera.x, localCamera.y, localCamera.z);
        net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
        this.minecraft.entityRenderer.enableLightmap();
        try {
            for (Entity entity : schematic.getEntities()) {
                if (!schematic.isBlockRendered(MathHelper.floor(entity.posX), MathHelper.floor(entity.posY), MathHelper.floor(entity.posZ))) continue;
                World originalWorld = entity.world;
                GlStateManager.pushMatrix();
                try {
                    entity.world = schematic;
                    GlStateManager.color(1, 1, 1, 1);
                    renderManager.renderEntityStatic(entity, partialTicks, false);
                } catch (Exception ignored) {
                } finally {
                    entity.world = originalWorld;
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            this.minecraft.entityRenderer.disableLightmap();
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            // the world render left the camera position here
            renderManager.setRenderPosition(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
            renderManager.setDebugBoundingBox(hitboxes);
        }
    }
}
