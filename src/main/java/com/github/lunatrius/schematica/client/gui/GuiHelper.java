package com.github.lunatrius.schematica.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class GuiHelper {

    private static final ResourceLocation STAT_ICONS = new ResourceLocation("textures/gui/container/stats_icons.png");

    public static void drawItemStack(TextureManager textureManager, FontRenderer fontRenderer, int x, int y,
        ItemStack itemStack) {
        drawItemStackSlot(textureManager, x, y);

        if (itemStack != null && !itemStack.isEmpty()) {
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            RenderHelper.enableGUIStandardItemLighting();
            Minecraft.getMinecraft().getRenderItem().renderItemIntoGUI(itemStack, x + 2, y + 2);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        }
    }

    public static void drawItemStackSlot(TextureManager textureManager, int x, int y) {
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        textureManager.bindTexture(STAT_ICONS);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x + 1 + 0, y + 1 + 18, 0).tex(0 * 0.0078125f, 18 * 0.0078125f).endVertex();
        buffer.pos(x + 1 + 18, y + 1 + 18, 0).tex(18 * 0.0078125f, 18 * 0.0078125f).endVertex();
        buffer.pos(x + 1 + 18, y + 1 + 0, 0).tex(18 * 0.0078125f, 0 * 0.0078125f).endVertex();
        buffer.pos(x + 1 + 0, y + 1 + 0, 0).tex(0 * 0.0078125f, 0 * 0.0078125f).endVertex();
        tessellator.draw();
    }
}
