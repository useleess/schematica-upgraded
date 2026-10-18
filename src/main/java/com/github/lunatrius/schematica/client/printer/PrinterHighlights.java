// Printer block highlights after the behavior of litematica-printer: placed, broken and failed positions fade out, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayDeque;
import java.util.Iterator;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.handler.ConfigurationHandler;

public final class PrinterHighlights {
    public enum Type {
        PLACE(1f, 1f, 1f), BREAK(1f, 0f, 0f), FAILED(0.5f, 0.5f, 0.5f);
        final float r, g, b;
        Type(float r, float g, float b) { this.r = r; this.g = g; this.b = b; }
    }

    private static final class Entry {
        final int x, y, z;
        final Type type;
        final long time;
        Entry(int x, int y, int z, Type type) { this.x = x; this.y = y; this.z = z; this.type = type; this.time = System.currentTimeMillis(); }
    }

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private PrinterHighlights() {}

    public static synchronized void add(int x, int y, int z, Type type) {
        if (!ConfigurationHandler.printHighlight) return;
        ENTRIES.removeIf(entry -> entry.x == x && entry.y == y && entry.z == z);
        ENTRIES.add(new Entry(x, y, z, type));
        while (ENTRIES.size() > 512) ENTRIES.removeFirst();
    }

    public static synchronized void clear() { ENTRIES.clear(); }

    /** Outlines at 50% alpha fading out over printHighlightFade tenths of a second; world coordinates minus the camera. */
    public static synchronized void render(double cx, double cy, double cz) {
        if (ENTRIES.isEmpty()) return;
        long now = System.currentTimeMillis(), duration = Math.max(1, ConfigurationHandler.printHighlightFade) * 100L;
        begin(2f, ConfigurationHandler.printHighlightThroughWalls);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext();) {
            Entry entry = it.next();
            long age = now - entry.time;
            if (age > duration) { it.remove(); continue; }
            box(buffer, entry.x, entry.y, entry.z, 0.002, cx, cy, cz, entry.type.r, entry.type.g, entry.type.b, 0.5f * (1f - age / (float) duration));
        }
        Tessellator.getInstance().draw();
        end(ConfigurationHandler.printHighlightThroughWalls);
    }

    /** Line state shared with the next block hint: untextured, blended, no depth writes, optionally through walls. */
    static void begin(float width, boolean throughWalls) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        if (throughWalls) GlStateManager.disableDepth();
        GL11.glLineWidth(width);
    }

    static void end(boolean throughWalls) {
        GL11.glLineWidth(1f);
        if (throughWalls) GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** The twelve edges of a block grown by e on each side. */
    static void box(BufferBuilder buffer, int bx, int by, int bz, double e, double cx, double cy, double cz, float r, float g, float b, float a) {
        double x0 = bx - e - cx, y0 = by - e - cy, z0 = bz - e - cz;
        double x1 = bx + 1 + e - cx, y1 = by + 1 + e - cy, z1 = bz + 1 + e - cz;
        for (double y : new double[] {y0, y1}) for (double z : new double[] {z0, z1}) line(buffer, x0, y, z, x1, y, z, r, g, b, a);
        for (double x : new double[] {x0, x1}) for (double z : new double[] {z0, z1}) line(buffer, x, y0, z, x, y1, z, r, g, b, a);
        for (double x : new double[] {x0, x1}) for (double y : new double[] {y0, y1}) line(buffer, x, y, z0, x, y, z1, r, g, b, a);
    }

    private static void line(BufferBuilder buffer, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        buffer.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.pos(x1, y1, z1).color(r, g, b, a).endVertex();
    }
}
