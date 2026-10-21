package com.github.lunatrius.schematica.handler.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.client.gui.GuiModConfig;
import com.github.lunatrius.schematica.client.gui.GuiSchematicMainMenu;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.input.Hotkey;
import com.github.lunatrius.schematica.client.renderer.RendererSchematicGlobal;
import com.github.lunatrius.schematica.client.world.RenderLayerRange;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;

/**
 * What the hotkeys do. Hotkeys of features that are not ported yet (tools, printer, material lists, verifier,
 * projects) are not handled here, so their keys reach the game as before.
 */
@SideOnly(Side.CLIENT)
public class InputHandler {

    public static final InputHandler INSTANCE = new InputHandler();

    private final Minecraft minecraft = Minecraft.getMinecraft();

    private InputHandler() {}

    public boolean onHotkey(Hotkey key, Hotkey.Action action) {
        GuiScreen parent = minecraft.currentScreen;
        switch (key.id) {
            case "openGuiMainMenu": minecraft.displayGuiScreen(new GuiSchematicMainMenu(parent)); return true;
            case "openGuiSettings": minecraft.displayGuiScreen(new GuiModConfig(parent)); return true;
            case "uiDemo": minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.UiDemoScreen(parent)); return true;
            default: break;
        }
        if (minecraft.player == null || minecraft.world == null) return false;
        if (key.toggleGroup != null) {
            VisualSettings.Toggle toggle = VisualSettings.Toggle.byUpstream(key.id);
            if (toggle == null) return false;
            toggle(toggle);
            if (toggle != VisualSettings.Toggle.ENTITY_HITBOXES) RendererSchematicGlobal.INSTANCE.refresh();
            return true;
        }
        SchematicWorld placement = ClientProxy.schematic;
        switch (key.id) {
            case "openGuiLoadedSchematics":
                minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.placement.GuiSchematicLoadedList(parent));
                break;
            case "openGuiSchematicPlacements":
                minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.placement.GuiSchematicPlacementsList(parent));
                break;
            case "openGuiLoadSchematics":
                minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.load.GuiSchematicLoad(parent));
                break;
            case "openGuiSchematicManager":
                minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.browser.GuiSchematicManager(parent));
                break;
            case "openSchematicsFolder":
                if (!com.github.lunatrius.schematica.handler.ConfigurationHandler.schematicDirectory.isDirectory()) com.github.lunatrius.schematica.handler.ConfigurationHandler.schematicDirectory.mkdirs();
                com.github.lunatrius.schematica.client.util.FolderOpener.open(com.github.lunatrius.schematica.handler.ConfigurationHandler.schematicDirectory);
                break;
            case "rotatePlacementCW": return com.github.lunatrius.schematica.tool.PlacementActions.rotate();
            case "rotatePlacementCCW": return com.github.lunatrius.schematica.tool.PlacementActions.rotateCounterClockwise();
            case "duplicatePlacement": return com.github.lunatrius.schematica.tool.PlacementActions.duplicate();
            case "removeSelectedPlacement":
                if (placement == null) return false;
                ClientProxy.removePlacement(placement);
                break;
            case "schematicEditAcceptReplacement": return com.github.lunatrius.schematica.tool.SchematicRebuild.acceptReplacement();
            case "setHeldItemAsTool": return com.github.lunatrius.schematica.tool.PlacementActions.setHeldItemAsTool();
            case "setSchematicPreview": return com.github.lunatrius.schematica.client.gui.browser.SchematicPreview.INSTANCE.takeNow();
            case "toolModeCycleForward": com.github.lunatrius.schematica.tool.ToolManager.cycleMode(true); break;
            case "toolModeCycleBackward": com.github.lunatrius.schematica.tool.ToolManager.cycleMode(false); break;
            case "updateBlocks": return com.github.lunatrius.schematica.tool.PlacementActions.updateBlocks();
            case "pickBlockAutoToggle": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("pickBlockAuto", true); break;
            case "openGuiPlacementSettings":
                if (placement != null) minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.placement.GuiPlacementConfiguration(parent, placement));
                break;
            case "openGuiMaterialList":
                openMaterialList(minecraft, parent, placement);
                break;
            case "refreshMaterialList": {
                com.github.lunatrius.schematica.client.gui.material.MaterialList list = com.github.lunatrius.schematica.client.gui.material.MaterialLists.current();
                if (list == null && placement != null) list = placement.materialList;
                if (list == null) return false;
                list.refresh();
                if (minecraft.ingameGUI != null) minecraft.ingameGUI.setOverlayMessage(UiTranslations.format("schematica.message.material_list.refreshed"), false);
                return true;
            }
            case "openPlacementGridSettingsScreen":
                if (placement != null) minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.placement.GuiPlacementGridSettings(parent, placement));
                else minecraft.player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("litematica.message.error.no_placement_selected"));
                break;
            case "openGuiSelectionManager":
                if (!com.github.lunatrius.schematica.client.projects.SchematicProjects.hasProjectOpen()) minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.save.GuiAreaSelectionManager(parent));
                else com.github.lunatrius.schematica.client.projects.SchematicProjects.message(net.minecraft.util.text.TextFormatting.GOLD, "litematica.gui.button.hover.schematic_projects.area_browser_disabled_currently_in_projects_mode");
                break;
            case "openGuiSchematicProjects": return com.github.lunatrius.schematica.client.projects.SchematicProjects.openGui(parent);
            case "openGuiSchematicVerifier":
                if (placement != null) minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.GuiSchematicVerifier(parent, placement));
                break;
            case "schematicVersionCycleNext": if (com.github.lunatrius.schematica.client.projects.SchematicProjects.hasProjectOpen()) com.github.lunatrius.schematica.client.projects.SchematicProjects.cycleVersion(1); break;
            case "schematicVersionCyclePrevious": if (com.github.lunatrius.schematica.client.projects.SchematicProjects.hasProjectOpen()) com.github.lunatrius.schematica.client.projects.SchematicProjects.cycleVersion(-1); break;
            case "schematicVCSDeleteBlockByPlacement": return com.github.lunatrius.schematica.client.projects.SchematicProjects.deleteBlocksByPlacement();
            case "openGuiAreaSettings":
                minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.save.GuiAreaSelectionEditor(parent));
                break;
            case "saveAreaAsSchematicToFile": {
                // SchematicUtils.saveSchematic: a new project version while a project is open, else the save screen
                if (com.github.lunatrius.schematica.client.selection.AreaSelections.library().selected() == null) return false;
                com.github.lunatrius.schematica.client.projects.SchematicProject project = com.github.lunatrius.schematica.client.projects.SchematicProjects.current();
                if (project != null) minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.projects.GuiVersionPrompt(parent, project));
                else minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.save.GuiSchematicSave(parent));
                break;
            }
            case "cloneSelection": return com.github.lunatrius.schematica.tool.PlacementActions.cloneSelection();
            case "schematicEditReplaceSelection": return com.github.lunatrius.schematica.tool.SchematicRebuild.replaceSelection();
            case "saveAreaAsInMemorySchematic": return com.github.lunatrius.schematica.tool.PlacementActions.saveInMemory();
            case "schematicPlacementRotation": return com.github.lunatrius.schematica.tool.PlacementActions.rotate();
            case "schematicPlacementMirror": return com.github.lunatrius.schematica.tool.PlacementActions.mirror();
            case "pickBlockFirst": return pickBlock(true);
            case "pickBlockLast":
                if (!com.github.lunatrius.schematica.client.input.Hotkeys.boundOnlyTo("pickBlockLast", minecraft.gameSettings.keyBindUseItem.getKeyCode())) pickBlock(false);
                return false;
            case "pickBlockToggle": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("pickBlockEnabled", true); break;
            case "workingSwitch": com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.toggleWithMessage(); return true;
            case "easyPlaceUseKey": return com.github.lunatrius.schematica.client.printer.EasyPlace.handle();
            case "easyPlaceFirst": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("easyPlaceFirst", true); break;
            case "easyPlaceToggle": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("easyPlaceMode", true); break;
            case "togglePlacementRestriction": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("placementRestriction", true); break;
            case "toggleSignTextPaste": com.github.lunatrius.schematica.tool.ToolManager.toggleConfig("signTextPaste", true); break;
            case "layerNext": moveLayer(1); break;
            case "layerPrevious": moveLayer(-1); break;
            case "layerModeNext": cycleLayer(1); break;
            case "layerModePrevious": cycleLayer(-1); break;
            case "layerSetHere": RenderLayerSettings.RANGE.setValue(false, MathHelper.floor(layerCoordinate())); showLayer(); break;
            case "rerenderSchematic": RendererSchematicGlobal.INSTANCE.refresh(); break;
            case "unloadCurrentSchematic": SchematicaPlus.proxy.unloadSchematic(); break;
            case "toggleAllRendering": renderToggle(VisualSettings.Toggle.ALL, false); break;
            case "toggleSchematicRendering": renderToggle(VisualSettings.Toggle.SCHEMATIC, false); break;
            case "toggleSchematicBlockRendering": toggle(VisualSettings.Toggle.BLOCKS); break;
            case "toggleOverlayRendering": renderToggle(VisualSettings.Toggle.OVERLAY, true); break;
            case "toggleOverlayOutlineRendering": renderToggle(VisualSettings.Toggle.OVERLAY_OUTLINES, true); break;
            case "toggleOverlaySideRendering": renderToggle(VisualSettings.Toggle.OVERLAY_SIDES, true); break;
            case "toggleTranslucentRendering": renderToggle(VisualSettings.Toggle.TRANSLUCENT, false); break;
            case "toggleAreaSelectionBoxesRendering": toggle(VisualSettings.Toggle.AREA_BOXES); break;
            case "togglePlacementBoxesRendering": toggle(VisualSettings.Toggle.PLACEMENT_BOXES); break;
            case "toggleInfoOverlayRendering": toggle(VisualSettings.Toggle.INFO_OVERLAY); break;
            case "toggleVerifierOverlayRendering": toggle(VisualSettings.Toggle.VERIFIER_OVERLAY); break;
            case "executeOperation":
                return (!com.github.lunatrius.schematica.handler.ConfigurationHandler.executeRequireTool
                    || com.github.lunatrius.schematica.tool.ToolManager.toolActive())
                    && com.github.lunatrius.schematica.tool.ToolHandler.onExecute(minecraft.player);
            default: return com.github.lunatrius.schematica.tool.ToolManager.hotkey(key.id);
        }
        return true;
    }

    private static void openMaterialList(net.minecraft.client.Minecraft minecraft, net.minecraft.client.gui.GuiScreen parent, SchematicWorld placement) {
        com.github.lunatrius.schematica.client.gui.material.MaterialList list = com.github.lunatrius.schematica.client.gui.material.MaterialLists.current();
        // No last-viewed material list currently stored, try to get one for the currently selected placement, if any
        if (list == null) {
            if (placement == null) {
                minecraft.player.sendMessage(new net.minecraft.util.text.TextComponentTranslation("litematica.message.error.no_placement_selected"));
                return;
            }
            list = com.github.lunatrius.schematica.client.gui.material.MaterialList.placement(placement);
            list.refresh();
        }
        minecraft.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.control.GuiSchematicMaterials(parent, list));
    }

    /** Flips a visual option and shows MaLiLib's toggle message on the action bar. */
    public static boolean toggle(VisualSettings.Toggle toggle) {
        boolean value = VisualSettings.toggle(toggle);
        printToggle(prettyName(toggle.group, toggle.upstream), value);
        return value;
    }

    public static String prettyName(String group, String name) {
        String key = "litematica.config." + group + ".prettyName." + name;
        String text = UiTranslations.format(key);
        return text.equals(key) ? name : text;
    }

    /** MaLiLib's printBooleanConfigToggleMessage on the action bar. */
    public static void printToggle(String prettyName, boolean value) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return;
        String state = (value ? TextFormatting.GREEN : TextFormatting.RED) + UiTranslations.format("malilib.message.value." + (value ? "on" : "off"))
            + TextFormatting.RESET;
        mc.ingameGUI.setOverlayMessage(UiTranslations.format("malilib.message.toggled", prettyName, state), false);
    }

    /** Litematica's RenderToggle: rebuilds schematic geometry when turned on, or on every change for geometry baked into chunks. */
    private void renderToggle(VisualSettings.Toggle toggle, boolean baked) {
        if (toggle(toggle) || baked) RendererSchematicGlobal.INSTANCE.refresh();
    }

    private double layerCoordinate() {
        Entity camera = minecraft.getRenderViewEntity();
        if (camera == null) return 0;
        return RenderLayerSettings.RANGE.axis() == RenderLayerRange.Axis.X ? camera.posX
            : RenderLayerSettings.RANGE.axis() == RenderLayerRange.Axis.Y ? camera.posY : camera.posZ;
    }

    private void cycleLayer(int direction) {
        RenderLayerRange.Mode[] modes = RenderLayerRange.Mode.values();
        RenderLayerSettings.RANGE.setMode(modes[Math.floorMod(RenderLayerSettings.RANGE.mode().ordinal() + direction, modes.length)]);
        showLayer();
    }

    private void moveLayer(int amount) {
        RenderLayerRange range = RenderLayerSettings.RANGE;
        if (range.mode() != RenderLayerRange.Mode.ALL) {
            if (minecraft.getRenderViewEntity() != null) range.move(amount, layerCoordinate());
            showLayer();
        } else {
            SchematicWorld schematic = ClientProxy.schematic;
            if (schematic != null && schematic.isRenderingLayer) {
                schematic.renderingLayer = MathHelper.clamp(schematic.renderingLayer + amount, 0, schematic.getHeight() - 1);
                if (minecraft.ingameGUI != null) minecraft.ingameGUI.setOverlayMessage(UiTranslations.format("litematica.hud.misc.render_layer_mode",
                    TextFormatting.GREEN + UiTranslations.format(RenderLayerRange.Mode.SINGLE_LAYER.translationKey()) + TextFormatting.RESET,
                    TextFormatting.GREEN + "y = " + (schematic.position.y + schematic.renderingLayer) + TextFormatting.RESET), false);
            } else showLayer();
        }
    }

    /** The current layer mode and range on the action bar, plus the status HUD for a while. */
    private void showLayer() {
        if (minecraft.ingameGUI != null) minecraft.ingameGUI.setOverlayMessage(com.github.lunatrius.schematica.client.renderer.hud.StatusInfoHud.layerLine(), false);
        com.github.lunatrius.schematica.client.renderer.hud.StatusInfoHud.startOverride();
    }

    /** Litematica's EntityUtils.shouldPickBlock. */
    public static boolean shouldPickBlock() {
        return com.github.lunatrius.schematica.handler.ConfigurationHandler.pickBlockEnabled
            && (!com.github.lunatrius.schematica.handler.ConfigurationHandler.toolItemEnabled
                || !com.github.lunatrius.schematica.tool.ToolManager.isHoldingToolItem())
            && VisualSettings.rendering && VisualSettings.schematic;
    }

    /** Litematica's doSchematicWorldPickBlock: the closest schematic block, or the farthest one before the targeted real block. */
    public boolean pickBlock(boolean closest) {
        if (minecraft.player == null || !shouldPickBlock()) return false;
        try {
            double range = com.github.lunatrius.schematica.tool.SchematicTargets.validBlockRange();
            com.github.lunatrius.schematica.tool.SchematicTargets.Hit hit = closest
                ? com.github.lunatrius.schematica.tool.SchematicTargets.closest(range, false)
                : com.github.lunatrius.schematica.tool.SchematicTargets.furthestBeforeVanilla(range);
            return hit != null && pickBlock(hit);
        } catch (Exception error) {
            com.github.lunatrius.schematica.reference.Reference.logger.error("Could not pick block!", error);
            return false;
        }
    }

    private boolean pickBlock(com.github.lunatrius.schematica.tool.SchematicTargets.Hit target) {
        SchematicWorld schematic = target.world;
        net.minecraft.util.math.BlockPos local = target.localPos();
        net.minecraft.block.state.IBlockState state = schematic.getBlockState(local);
        if (state.getBlock().isAir(state, schematic, local)) return false;
        net.minecraft.util.math.RayTraceResult hit = new net.minecraft.util.math.RayTraceResult(new net.minecraft.util.math.Vec3d(
            target.hitX - schematic.position.x, target.hitY - schematic.position.y, target.hitZ - schematic.position.z), target.face(), local);
        net.minecraft.item.ItemStack stack = state.getBlock().getPickBlock(state, hit, schematic, local, minecraft.player);
        if (stack == null || stack.isEmpty()) return false;
        // InventoryUtils.schematicWorldPickBlock: hotbar first, else a pick-blockable slot. When the item could not be
        // put in the hand (not carried in survival, no free slot) the click goes on to vanilla's pick block.
        return com.github.lunatrius.schematica.client.printer.PickBlockSlots.pickToHand(this.minecraft, stack, true);
    }
}
