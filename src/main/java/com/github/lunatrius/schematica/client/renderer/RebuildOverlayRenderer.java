// SPDX-License-Identifier: LGPL-3.0-only
// Litematica rebuild targeting overlay and MaLiLib block targeting overlay, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;

import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.client.input.Hotkeys;
import com.github.lunatrius.schematica.handler.RenderColors;
import com.github.lunatrius.schematica.tool.RebuildDirection;
import com.github.lunatrius.schematica.tool.SchematicTargets;
import com.github.lunatrius.schematica.tool.ToolManager;
import com.github.lunatrius.schematica.tool.ToolMode;

public final class RebuildOverlayRenderer {
    private static final double OFFSET = 0.01;
    private static final double[][] PARTS = {
        {0.25, 0.25, 0.75, 0.25, 0.75, 0.75, 0.25, 0.75},
        {0, 0, 0.25, 0.25, 0.25, 0.75, 0, 1},
        {1, 0, 0.75, 0.25, 0.75, 0.75, 1, 1},
        {0, 0, 0.25, 0.25, 0.75, 0.25, 1, 0},
        {0, 1, 0.25, 0.75, 0.75, 0.75, 1, 1}};

    private RebuildOverlayRenderer() {}

    public static void render(Minecraft mc, double cameraX, double cameraY, double cameraZ) {
        if (ToolManager.getCurrentMode() != ToolMode.REBUILD || mc.gameSettings.hideGUI || mc.currentScreen != null || mc.player == null) return;
        RenderColors color;
        boolean direction = false;
        if (Hotkeys.held("schematicEditBreakPlaceAll")) color = RenderColors.REBUILD_BREAK;
        else if (Hotkeys.held("schematicEditBreakAllExcept")) color = RenderColors.REBUILD_EXCEPT;
        else if (Hotkeys.held("schematicEditBreakPlaceDirection")) { color = RenderColors.REBUILD_BREAK; direction = true; }
        else if (Hotkeys.held("schematicEditReplaceAll") || Hotkeys.held("schematicEditReplaceBlock")) color = RenderColors.REBUILD_REPLACE;
        else if (Hotkeys.held("schematicEditReplaceDirection")) { color = RenderColors.REBUILD_REPLACE; direction = true; }
        else return;
        SchematicTargets.Hit target = SchematicTargets.closest(20, true);
        if (target == null) return;
        EnumFacing side = target.face(), facing = SchematicTargets.facing();
        double ox = target.x - cameraX, oy = target.y - cameraY, oz = target.z - cameraZ;
        int argb = color.color();
        mc.entityRenderer.disableLightmap();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        try {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            if (direction) {
                double[] hit = RebuildDirection.facePosition(side, facing, target.hitX - target.x, target.hitY - target.y, target.hitZ - target.z);
                quad(buffer, side, facing, ox, oy, oz, 0x2EFFFFFF, 0, 0, 1, 0, 1, 1, 0, 1);
                double[] p = PARTS[RebuildDirection.part(hit[0], hit[1]).ordinal()];
                quad(buffer, side, facing, ox, oy, oz, argb, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
            } else {
                quad(buffer, side, facing, ox, oy, oz, argb, 0, 0, 1, 0, 1, 1, 0, 1);
            }
            tessellator.draw();
            GL11.glLineWidth(1.6F);
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            if (direction) {
                loop(buffer, side, facing, ox, oy, oz, 0.25, 0.75);
                double[][] corners = {{0, 0, 0.25, 0.25}, {0, 1, 0.25, 0.75}, {1, 0, 0.75, 0.25}, {1, 1, 0.75, 0.75}};
                for (double[] c : corners) line(buffer, side, facing, ox, oy, oz, c[0], c[1], c[2], c[3]);
            } else loop(buffer, side, facing, ox, oy, oz, 0.125, 0.875);
            tessellator.draw();
        } finally {
            GL11.glLineWidth(1F);
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.enableAlpha();
            GlStateManager.enableDepth();
            GlStateManager.enableFog();
            GlStateManager.enableTexture2D();
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
            mc.entityRenderer.enableLightmap();
        }
    }

    private static void quad(BufferBuilder buffer, EnumFacing side, EnumFacing facing, double x, double y, double z, int argb, double... hv) {
        for (int i = 0; i < 8; i += 2) vertex(buffer, side, facing, x, y, z, hv[i], hv[i + 1], argb);
    }

    private static void loop(BufferBuilder buffer, EnumFacing side, EnumFacing facing, double x, double y, double z, double min, double max) {
        line(buffer, side, facing, x, y, z, min, min, max, min);
        line(buffer, side, facing, x, y, z, max, min, max, max);
        line(buffer, side, facing, x, y, z, max, max, min, max);
        line(buffer, side, facing, x, y, z, min, max, min, min);
    }

    private static void line(BufferBuilder buffer, EnumFacing side, EnumFacing facing, double x, double y, double z,
                             double h1, double v1, double h2, double v2) {
        vertex(buffer, side, facing, x, y, z, h1, v1, 0xFFFFFFFF);
        vertex(buffer, side, facing, x, y, z, h2, v2, 0xFFFFFFFF);
    }

    private static void vertex(BufferBuilder buffer, EnumFacing side, EnumFacing facing, double x, double y, double z, double h, double v, int argb) {
        double[] point = RebuildDirection.facePoint(side, facing, h, v, OFFSET);
        buffer.pos(x + point[0], y + point[1], z + point[2]).color(argb >>> 16 & 255, argb >>> 8 & 255, argb & 255, argb >>> 24).endVertex();
    }
}
