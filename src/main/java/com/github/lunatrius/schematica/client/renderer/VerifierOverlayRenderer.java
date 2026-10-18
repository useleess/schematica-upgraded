// SPDX-License-Identifier: LGPL-3.0-only
// Litematica verifier overlays, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.client.verifier.VerificationManager;
import com.github.lunatrius.schematica.client.verifier.VerificationScan.Marker;
import com.github.lunatrius.schematica.client.verifier.VerificationScan.Type;
import com.github.lunatrius.schematica.handler.RenderColors;
import com.github.lunatrius.schematica.handler.VerifierOverlaySettings;
import com.github.lunatrius.schematica.util.VoxelRayTrace;

public final class VerifierOverlayRenderer {
    private static final int[] EDGES = {0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7};
    private static final int[] FACES = {0, 1, 3, 2, 4, 6, 7, 5, 0, 4, 5, 1, 2, 3, 7, 6, 0, 2, 6, 4, 1, 5, 7, 3};

    private VerifierOverlayRenderer() {}

    public static Marker target(Minecraft mc, VerificationManager.Session session, float partialTicks) {
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return null;
        Vec3d eye = camera.getPositionEyes(partialTicks), look = camera.getLook(partialTicks);
        return VoxelRayTrace.trace(eye, eye.addVector(look.x * 128, look.y * 128, look.z * 128), session.markers::at);
    }

    public static void render(Minecraft mc, float partialTicks, double cameraX, double cameraY, double cameraZ) {
        VerificationManager.Session session = VerificationManager.INSTANCE.overlay(mc);
        if (session == null || !VerifierOverlaySettings.enabled || mc.gameSettings.hideGUI || session.markers.markers().isEmpty()) return;
        Marker target = target(mc, session, partialTicks);
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
            if (VerifierOverlaySettings.sides && VerifierOverlaySettings.alpha > 0) {
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                for (Marker marker : session.markers.markers()) box(buffer, marker, FACES, VerifierOverlaySettings.alpha, cameraX, cameraY, cameraZ);
                tessellator.draw();
            }
            GL11.glLineWidth(2);
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            Marker previous = null;
            for (Marker marker : session.markers.markers()) {
                if (marker != target) box(buffer, marker, EDGES, 1, cameraX, cameraY, cameraZ);
                if (VerifierOverlaySettings.connections && previous != null) {
                    int[] c = tint(marker, 1);
                    buffer.pos(previous.x + 0.5 - cameraX, previous.y + 0.5 - cameraY, previous.z + 0.5 - cameraZ).color(c[0], c[1], c[2], c[3]).endVertex();
                    buffer.pos(marker.x + 0.5 - cameraX, marker.y + 0.5 - cameraY, marker.z + 0.5 - cameraZ).color(c[0], c[1], c[2], c[3]).endVertex();
                }
                previous = marker;
            }
            tessellator.draw();
            if (target != null) {
                GL11.glLineWidth(6);
                buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                box(buffer, target, EDGES, 1, cameraX, cameraY, cameraZ);
                tessellator.draw();
            }
        } finally {
            GL11.glLineWidth(1);
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

    private static void box(BufferBuilder buffer, Marker marker, int[] vertices, double alpha, double cameraX, double cameraY, double cameraZ) {
        int[] c = tint(marker, alpha);
        for (int vertex : vertices) buffer.pos(marker.x - cameraX + ((vertex & 1) == 0 ? -0.002 : 1.002),
            marker.y - cameraY + ((vertex & 2) == 0 ? -0.002 : 1.002), marker.z - cameraZ + ((vertex & 4) == 0 ? -0.002 : 1.002))
            .color(c[0], c[1], c[2], c[3]).endVertex();
    }

    private static int[] tint(Marker marker, double alpha) {
        Type type = marker.group.type;
        int color = (type == Type.WRONG_BLOCK ? RenderColors.WRONG_BLOCK : type == Type.WRONG_STATE ? RenderColors.WRONG_STATE
            : type == Type.DIFF_BLOCK ? RenderColors.DIFFERENT_TYPE : type == Type.EXTRA ? RenderColors.EXTRA : RenderColors.MISSING).color();
        return new int[] {color >>> 16 & 255, color >>> 8 & 255, color & 255, (int) (alpha * 255)};
    }
}
