package com.github.lunatrius.schematica.handler.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;


import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.tool.ToolManager;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class OverlayHandler {

    private final Minecraft minecraft = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onText(RenderGameOverlayEvent.Text event) {
        if (this.minecraft.gameSettings.showDebugInfo && ConfigurationHandler.showDebugInfo) {
            final SchematicWorld schematic = ClientProxy.schematic;
            if (schematic != null && schematic.isRenderingEnabled()) {
                event.getLeft().add("");
                event.getLeft().add("[\u00a76" + Reference.NAME + "\u00a7r] " + schematic.getDebugDimensions());
                event.getLeft().add("[\u00a76" + UiTranslations.format("litematica.hud.selected_mode") + "\u00a7r] " + ToolManager.getCurrentMode().getDisplayName());

                final net.minecraft.util.math.RayTraceResult hit = ClientProxy.movingObjectPosition;
                if (hit != null && hit.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.BLOCK) {
                    event.getRight().add("");
                    event.getRight().add(com.github.lunatrius.schematica.world.schematic.BlockStates.toString(schematic.getBlockState(hit.getBlockPos())) + " [\u00a76S\u00a7r]");
                }
            }
        }
    }

    /** The material list HUD inside GUIs (renderMaterialListInGuis) and the looked at block in inventories. */
    private net.minecraft.client.gui.GuiScreen scannedContainer;

    @SubscribeEvent
    public void onDrawScreen(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post event) {
        net.minecraft.client.gui.GuiScreen gui = event.getGui();
        // materialListContainerScan: once per opened container screen, at its first frame
        if (gui instanceof net.minecraft.client.gui.inventory.GuiContainer && gui != this.scannedContainer
            && ConfigurationHandler.materialListContainerScan) {
            this.scannedContainer = gui;
            com.github.lunatrius.schematica.client.gui.material.MaterialItemCache.scan(
                ((net.minecraft.client.gui.inventory.GuiContainer) gui).inventorySlots.inventorySlots);
        }
        if (this.minecraft.world == null || !com.github.lunatrius.schematica.handler.VisualSettings.rendering) return;
        if (gui instanceof net.minecraft.client.gui.inventory.GuiContainer) {
            com.github.lunatrius.schematica.client.renderer.hud.MaterialListHud.highlightInventory(this.minecraft,
                (net.minecraft.client.gui.inventory.GuiContainer) gui);
        }
        int height = com.github.lunatrius.schematica.client.renderer.hud.MaterialListHud.render(this.minecraft, 0, true);
        com.github.lunatrius.schematica.client.printer.PrinterMissingMaterials.render(this.minecraft, height);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        if (!com.github.lunatrius.schematica.handler.VisualSettings.rendering) {
            com.github.lunatrius.schematica.client.renderer.hud.BlockInfoHud.INSTANCE.clear();
            return;
        }
        boolean verifier = com.github.lunatrius.schematica.client.renderer.hud.VerifierHud.render(this.minecraft, event.getPartialTicks());
        com.github.lunatrius.schematica.client.renderer.hud.BlockInfoHud.INSTANCE.render(this.minecraft, event.getPartialTicks(), !verifier);
        if (this.minecraft.currentScreen == null && !this.minecraft.gameSettings.hideGUI) {
            int height = com.github.lunatrius.schematica.client.renderer.hud.VerifierHud.textHeight();
            height += com.github.lunatrius.schematica.client.renderer.hud.MaterialListHud.render(this.minecraft, height, false);
            com.github.lunatrius.schematica.client.printer.PrinterMissingMaterials.render(this.minecraft, height);
        }

        if (this.minecraft.currentScreen != null || this.minecraft.gameSettings.hideGUI) return;
        com.github.lunatrius.schematica.client.renderer.hud.ToolHud.render(this.minecraft);
    }
}
