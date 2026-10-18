package com.github.lunatrius.schematica.client.gui.framework;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Draws the interface with Minecraft. All state goes through GlStateManager (1.12 caches GL state there; raw attribute
 * push/pop would leave its cache wrong), and the state the screens expect is put back when drawing ends.
 */
public final class MinecraftUiDraw implements UiDraw, AutoCloseable {

    private final Minecraft minecraft;
    private final int scale;
    private final Deque<UiBounds> clips = new ArrayDeque<>();
    private final boolean scissorWasEnabled;
    private final int[] previousScissor = new int[4];

    public MinecraftUiDraw(Minecraft minecraft) {
        this.minecraft = minecraft;
        scale = new ScaledResolution(minecraft).getScaleFactor();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        UiBounds framebuffer = new UiBounds(0, 0, minecraft.displayWidth, minecraft.displayHeight);
        scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorWasEnabled) {
            IntBuffer box = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, box);
            for (int i = 0; i < 4; i++) previousScissor[i] = box.get(i);
            framebuffer = framebuffer.intersect(new UiBounds(box.get(0), box.get(1), box.get(2), box.get(3)));
        }
        clips.push(framebuffer);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01F);
    }

    @Override
    public void fill(UiBounds bounds, int color) {
        if (!bounds.isEmpty()) Gui.drawRect(bounds.x, bounds.y, bounds.right(), bounds.bottom(), color);
    }

    private static void untexturedBlend() {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    }

    @Override
    public void colorGrid(UiBounds bounds, int columns, int rows, int[] colors) {
        if (columns < 1 || rows < 1 || colors.length != (columns + 1) * (rows + 1)) {
            throw new IllegalArgumentException("Invalid color grid");
        }
        if (bounds.isEmpty()) return;
        untexturedBlend();
        GlStateManager.disableAlpha();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        try {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
                double left = bounds.x + (double) bounds.width * x / columns;
                double right = bounds.x + (double) bounds.width * (x + 1) / columns;
                double top = bounds.y + (double) bounds.height * y / rows;
                double bottom = bounds.y + (double) bounds.height * (y + 1) / rows;
                int i = y * (columns + 1) + x;
                colorVertex(buffer, left, top, colors[i]);
                colorVertex(buffer, left, bottom, colors[i + columns + 1]);
                colorVertex(buffer, right, bottom, colors[i + columns + 2]);
                colorVertex(buffer, right, top, colors[i + 1]);
            }
            tessellator.draw();
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    private static void colorVertex(BufferBuilder buffer, double x, double y, int color) {
        buffer.pos(x, y, 0).color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).endVertex();
    }

    @Override
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        untexturedBlend();
        try {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
            colorVertex(buffer, x1, y1, color);
            colorVertex(buffer, x2, y2, color);
            colorVertex(buffer, x3, y3, color);
            tessellator.draw();
        } finally {
            GlStateManager.enableTexture2D();
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    private void prepareTextured() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void text(String text, int x, int y, int color) {
        prepareTextured();
        minecraft.fontRenderer.drawStringWithShadow(text, x, y, color);
    }

    @Override
    public void flatText(String text, int x, int y, int color) {
        prepareTextured();
        minecraft.fontRenderer.drawString(text, x, y, color);
    }

    @Override
    public int textWidth(String text) {
        return minecraft.fontRenderer.getStringWidth(text);
    }

    @Override
    public String trim(String text, int width) {
        return minecraft.fontRenderer.trimStringToWidth(text, Math.max(0, width));
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        RenderItem itemRenderer = minecraft.getRenderItem();
        float z = itemRenderer.zLevel;
        float brightnessX = OpenGlHelper.lastBrightnessX;
        float brightnessY = OpenGlHelper.lastBrightnessY;
        GlStateManager.pushMatrix();
        try {
            prepareTextured();
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableRescaleNormal();
            RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.zLevel = 200;
            itemRenderer.renderItemAndEffectIntoGUI(stack, x, y);
        } finally {
            itemRenderer.zLevel = z;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableDepth();
            GlStateManager.disableLighting();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightnessX, brightnessY);
            GlStateManager.popMatrix();
        }
    }

    @Override
    public void texture(String texture, UiBounds destination, int u, int v, int sourceWidth,
        int sourceHeight, int textureWidth, int textureHeight) {
        texture(new ResourceLocation(texture), destination, u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
    }

    public void texture(ResourceLocation texture, UiBounds destination, int u, int v, int sourceWidth,
        int sourceHeight, int textureWidth, int textureHeight) {
        prepareTextured();
        minecraft.getTextureManager().bindTexture(texture);
        double left = (double) u / textureWidth;
        double top = (double) v / textureHeight;
        double right = (double) (u + sourceWidth) / textureWidth;
        double bottom = (double) (v + sourceHeight) / textureHeight;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(destination.x, destination.bottom(), 0).tex(left, bottom).endVertex();
        buffer.pos(destination.right(), destination.bottom(), 0).tex(right, bottom).endVertex();
        buffer.pos(destination.right(), destination.y, 0).tex(right, top).endVertex();
        buffer.pos(destination.x, destination.y, 0).tex(left, top).endVertex();
        tessellator.draw();
    }

    @Override
    public Clip clip(UiBounds bounds) {
        UiBounds scissor = clips.peek().intersect(bounds.toScissor(scale, minecraft.displayWidth, minecraft.displayHeight));
        clips.push(scissor);
        applyClip(scissor);
        return () -> {
            clips.pop();
            applyClip(clips.peek());
        };
    }

    private void applyClip(UiBounds bounds) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    public void close() {
        if (scissorWasEnabled) {
            GL11.glScissor(previousScissor[0], previousScissor[1], previousScissor[2], previousScissor[3]);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }
}
