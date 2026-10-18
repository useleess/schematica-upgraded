// 3D schematic preview for the file browsers, after the idea of Tech Utils' renderPreview and QuickCraft's 3D preview, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.gui.browser;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.BlockPos;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.client.renderer.SchematicRenderPass;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.storage.Schematic;

/**
 * A loaded schematic drawn into a square of a GUI: its blocks are compiled into display lists one 16^3 section at a
 * time within a per-frame budget, then shown turning slowly; dragging inside the square turns it, the wheel zooms.
 * Blocks drawn only by tile entity renderers (chests, signs) are not shown. Huge schematics are shown downsampled
 * (one block per step^3 cell, see of), so the whole shape fits in the block budget.
 */
final class SchematicPreview3D {
    /** Drawn blocks kept in display lists at most; larger previews stop there (GPU memory). */
    private static final int MAX_BLOCKS = 300_000;
    /** Longest side of the previewed (possibly downsampled) box. */
    private static final int MAX_SIDE = 256;
    /** Non-air blocks of the previewed box at most, before it is downsampled further. */
    private static final int MAX_CELLS = 1_000_000;
    private static final int SECTION = 16;
    private static final long BUDGET_NANOS = 6_000_000L;
    private static final BlockRenderLayer[][] PASS_LAYERS = {
        {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED, BlockRenderLayer.CUTOUT},
        {BlockRenderLayer.TRANSLUCENT}};

    private final ISchematic schematic;
    private final int step;
    private PreviewWorld world;
    private final int sectionsX, sectionsY, sectionsZ;
    private final List<int[]> lists = new ArrayList<>();
    private int next;
    private int blocks;
    private boolean failed;
    private float yaw = 45, pitch = 30, zoom = 1;
    private int dragX = -1, dragY;
    private long lastFrame;

    private SchematicPreview3D(ISchematic schematic, int step) {
        this.schematic = schematic;
        this.step = step;
        sectionsX = (schematic.getWidth() + SECTION - 1) / SECTION;
        sectionsY = (schematic.getHeight() + SECTION - 1) / SECTION;
        sectionsZ = (schematic.getLength() + SECTION - 1) / SECTION;
    }

    /**
     * The preview of a read schematic (null for null), downsampled while its box is longer than MAX_SIDE or it holds
     * more than MAX_CELLS blocks. No GL work: called on the reader thread. Never allocates per cell of the box.
     */
    static SchematicPreview3D of(ISchematic schematic) {
        if (schematic == null) return null;
        if (!(schematic instanceof Schematic)) return new SchematicPreview3D(schematic, 1);
        Schematic shown = (Schematic) schematic;
        int longest = Math.max(shown.getWidth(), Math.max(shown.getHeight(), shown.getLength()));
        int step = Math.max(1, (longest + MAX_SIDE - 1) / MAX_SIDE);
        if (step > 1) shown = downsample(shown, step);
        while (count(shown) > MAX_CELLS && Math.max(shown.getWidth(), Math.max(shown.getHeight(), shown.getLength())) > 1) {
            shown = downsample(shown, 2);
            step *= 2;
        }
        return new SchematicPreview3D(shown, step);
    }

    private static long count(Schematic schematic) {
        long[] count = {0};
        schematic.forEachBlock((x, y, z, state) -> count[0]++);
        return count[0];
    }

    /** One block per step^3 cell: the first opaque full block in it, else the first block. */
    private static Schematic downsample(Schematic source, int step) {
        Schematic target = new Schematic(source.getIcon(), (source.getWidth() + step - 1) / step,
            (source.getHeight() + step - 1) / step, (source.getLength() + step - 1) / step);
        source.forEachBlock((x, y, z, state) -> {
            int cx = x / step, cy = y / step, cz = z / step;
            IBlockState have = target.getBlockState(cx, cy, cz);
            if (have.getBlock() == Blocks.AIR || !have.isOpaqueCube() && state.isOpaqueCube()) target.setBlockState(cx, cy, cz, state);
        });
        return target;
    }

    /** The non-air blocks of one section (x fastest, then z, then y), without visiting air of sparse schematics. */
    private List<BlockPos> sectionBlocks(int index) {
        List<BlockPos> found = new ArrayList<>();
        if (schematic instanceof Schematic) {
            ((Schematic) schematic).forEachBlockInSection(index, null, (x, y, z, state) -> found.add(new BlockPos(x, y, z)));
            return found;
        }
        int sx = index % sectionsX, sz = index / sectionsX % sectionsZ, sy = index / (sectionsX * sectionsZ);
        for (int y = sy * SECTION; y < Math.min(schematic.getHeight(), (sy + 1) * SECTION); y++) {
            for (int z = sz * SECTION; z < Math.min(schematic.getLength(), (sz + 1) * SECTION); z++) {
                for (int x = sx * SECTION; x < Math.min(schematic.getWidth(), (sx + 1) * SECTION); x++) {
                    if (schematic.getBlockState(x, y, z).getBlock() != Blocks.AIR) found.add(new BlockPos(x, y, z));
                }
            }
        }
        return found;
    }

    private int sections() { return sectionsX * sectionsY * sectionsZ; }

    /** Builds display lists until the frame budget is used; true while sections are left. */
    private boolean build() {
        if (next >= sections() || blocks >= MAX_BLOCKS) return false;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        if (world == null) world = new PreviewWorld(schematic);
        Minecraft mc = Minecraft.getMinecraft();
        BlockRendererDispatcher dispatcher = mc.getBlockRendererDispatcher();
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        int ambientOcclusion = mc.gameSettings.ambientOcclusion;
        mc.gameSettings.ambientOcclusion = 0;
        try {
            while (next < sections() && blocks < MAX_BLOCKS && System.nanoTime() < deadline) {
                List<BlockPos> section = sectionBlocks(next++);
                if (section.isEmpty()) continue;
                int[] passes = {-1, -1};
                for (int pass = 0; pass < PASS_LAYERS.length; pass++) {
                    int list = GLAllocation.generateDisplayLists(1);
                    boolean any = false;
                    GL11.glNewList(list, GL11.GL_COMPILE);
                    try {
                        for (BlockRenderLayer layer : PASS_LAYERS[pass]) {
                            try (SchematicRenderPass context = new SchematicRenderPass(layer)) {
                                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                                try {
                                    for (BlockPos pos : section) {
                                        if (drawBlock(dispatcher, buffer, layer, pos)) { any = true; blocks++; }
                                    }
                                } finally {
                                    Tessellator.getInstance().draw();
                                }
                            }
                        }
                    } finally {
                        GL11.glEndList();
                    }
                    if (any) passes[pass] = list;
                    else GLAllocation.deleteDisplayLists(list, 1);
                }
                if (passes[0] >= 0 || passes[1] >= 0) lists.add(passes);
            }
        } finally {
            mc.gameSettings.ambientOcclusion = ambientOcclusion;
        }
        return next < sections() && blocks < MAX_BLOCKS;
    }

    private boolean drawBlock(BlockRendererDispatcher dispatcher, BufferBuilder buffer, BlockRenderLayer layer, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() == Blocks.AIR || state.getRenderType() == EnumBlockRenderType.INVISIBLE) return false;
        if (!state.getBlock().canRenderInLayer(state, layer)) return false;
        try {
            return dispatcher.renderBlock(state, pos, world, buffer);
        } catch (RuntimeException | LinkageError error) {
            Reference.logger.debug("Could not draw {} in the schematic preview", state, error);
            return false;
        }
    }

    /** Draws the preview into the square (GUI coordinates), building more of it first. */
    void draw(Minecraft mc, int x, int y, int size, int mouseX, int mouseY) {
        if (failed) return;
        boolean building;
        try {
            building = build();
        } catch (RuntimeException error) {
            failed = true;
            Reference.logger.warn("Could not build the schematic preview", error);
            return;
        }
        long now = System.currentTimeMillis();
        boolean inside = mouseX >= x && mouseY >= y && mouseX < x + size && mouseY < y + size;
        if (inside && Mouse.isButtonDown(0)) {
            if (dragX >= 0) { yaw += (mouseX - dragX) * 1.5f; pitch = Math.max(-90, Math.min(90, pitch + (mouseY - dragY) * 1.5f)); }
            dragX = mouseX; dragY = mouseY;
        } else {
            dragX = -1;
            if (lastFrame > 0) yaw += (now - lastFrame) * 0.02f;
        }
        lastFrame = now;

        ScaledResolution scaled = new ScaledResolution(mc);
        int factor = scaled.getScaleFactor();
        float extent = (float) Math.sqrt((double) schematic.getWidth() * schematic.getWidth()
            + (double) schematic.getHeight() * schematic.getHeight() + (double) schematic.getLength() * schematic.getLength());
        float scale = size / Math.max(1, extent) * zoom;

        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * factor, mc.displayHeight - (y + size) * factor, size * factor, size * factor);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.color(1, 1, 1, 1);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x + size / 2f, y + size / 2f, 100);
            GlStateManager.scale(scale, -scale, scale);
            GlStateManager.rotate(pitch, 1, 0, 0);
            GlStateManager.rotate(yaw, 0, 1, 0);
            GlStateManager.translate(-schematic.getWidth() / 2f, -schematic.getHeight() / 2f, -schematic.getLength() / 2f);
            for (int[] list : lists) if (list[0] >= 0) call(mc, list[0]);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.depthMask(false);
            for (int[] list : lists) if (list[1] >= 0) call(mc, list[1]);
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.disableDepth();
            GlStateManager.enableCull();
            // the lists' vertex colours leave the GL colour tinted while the cache still says white
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
            if (!scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        if (building) {
            String text = (int) ((long) next * 100 / Math.max(1, sections())) + "%";
            mc.fontRenderer.drawStringWithShadow(text, x + 3, y + size - 11, 0xFFAAAAAA);
        }
        if (step > 1) {
            String text = "1:" + step;
            mc.fontRenderer.drawStringWithShadow(text, x + size - 3 - mc.fontRenderer.getStringWidth(text), y + size - 11, 0xFFAAAAAA);
        }
    }

    /** A block renderer may have bound another texture inside a list. */
    private static void call(Minecraft mc, int list) {
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.callList(list);
    }

    /** Mouse wheel over the preview: zoom in or out. */
    void zoom(int wheel) { zoom = Math.max(0.25f, Math.min(4f, zoom * (wheel > 0 ? 1.25f : 0.8f))); }

    void delete() {
        for (int[] list : lists) for (int id : list) if (id >= 0) GLAllocation.deleteDisplayLists(id, 1);
        lists.clear();
        next = sections();
    }

    /** The schematic as a world, so block entities are bound; full light and air outside it. */
    private static final class PreviewWorld extends SchematicWorld {
        PreviewWorld(ISchematic schematic) { super(schematic); }

        private boolean inside(BlockPos pos) {
            return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0 && pos.getX() < getWidth() && pos.getY() < getHeight() && pos.getZ() < getLength();
        }

        @Override public IBlockState getBlockState(BlockPos pos) {
            return inside(pos) ? super.getBlockState(pos) : Blocks.AIR.getDefaultState();
        }

        @Override public TileEntity getTileEntity(BlockPos pos) { return inside(pos) ? super.getTileEntity(pos) : null; }

        @Override public int getCombinedLight(BlockPos pos, int light) { return 15 << 20 | 15 << 4; }
    }
}
