// SPDX-License-Identifier: LGPL-3.0-only
// Litematica renderCollidingBlockAtCursor, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.renderer;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;

import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;

/** Draws the expected schematic block over the targeted real block when that block is wrong. */
public final class CursorBlockRenderer {
    private CursorBlockRenderer() {}

    public static void render(Minecraft mc, double cameraX, double cameraY, double cameraZ) {
        if (!VisualSettings.collidingAtCursor || !VisualSettings.frameBlocks || mc.world == null) return;
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        for (SchematicWorld schematic : ClientProxy.visiblePlacements()) {
            if (!schematic.isEnabled() || !schematic.isRenderingEnabled()) continue;
            int x = pos.getX() - schematic.position.x, y = pos.getY() - schematic.position.y, z = pos.getZ() - schematic.position.z;
            if (!schematic.getSchematic().containsBlock(x, y, z) || !schematic.isBlockRendered(x, y, z)) continue;
            BlockPos local = new BlockPos(x, y, z);
            IBlockState expected = schematic.getBlockState(local);
            if (expected.getBlock().isAir(expected, schematic, local)) return;
            if (BlockCompare.compare(expected, mc.world.getBlockState(pos)) == BlockCompare.Result.SAME) return;
            if (expected.getRenderType() != EnumBlockRenderType.MODEL) return;
            draw(mc, schematic, expected, local, cameraX, cameraY, cameraZ);
            return;
        }
    }

    private static void draw(Minecraft mc, SchematicWorld schematic, IBlockState state, BlockPos local, double cameraX, double cameraY, double cameraZ) {
        GlStateManager.pushMatrix();
        try {
            // Slightly larger than the real block so its faces are drawn in front of the real ones.
            GlStateManager.translate(schematic.position.x + local.getX() + 0.5 - cameraX, schematic.position.y + local.getY() + 0.5 - cameraY,
                schematic.position.z + local.getZ() + 0.5 - cameraZ);
            GlStateManager.scale(1.004, 1.004, 1.004);
            GlStateManager.translate(-local.getX() - 0.5, -local.getY() - 0.5, -local.getZ() - 0.5);
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.enableTexture2D();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            try {
                mc.getBlockRendererDispatcher().renderBlock(state, local, schematic, buffer);
            } finally {
                tessellator.draw();
            }
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.disableBlend();
            GlStateManager.resetColor();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.popMatrix();
        }
    }
}
