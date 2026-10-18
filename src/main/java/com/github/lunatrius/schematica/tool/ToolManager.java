// SPDX-License-Identifier: LGPL-3.0-only
// Litematica tool input, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.tool;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.input.Hotkeys;
import com.github.lunatrius.schematica.client.projects.SchematicProjects;
import com.github.lunatrius.schematica.client.selection.AreaSelectionLibrary;
import com.github.lunatrius.schematica.client.selection.AreaSelectionLibrary.Area;
import com.github.lunatrius.schematica.client.selection.AreaSelections;
import com.github.lunatrius.schematica.client.selection.SelectionRayTrace;
import com.github.lunatrius.schematica.client.util.UiState;
import com.github.lunatrius.schematica.client.renderer.RendererSchematicGlobal;
import com.github.lunatrius.schematica.client.world.PlacementSettings;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.client.world.SubRegionPlacements;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.handler.client.InputHandler;
import com.github.lunatrius.schematica.handler.client.WorldHandler;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;

public final class ToolManager {
    public static final ToolManager INSTANCE = new ToolManager();
    private static ToolMode currentMode = ToolMode.AREA_SELECTION;
    private static Area grabbed;
    private static Object grabWorld;
    private static Vector3i grabPoint;
    private static double grabDistance;

    private ToolManager() {}
    private static Minecraft mc() { return Minecraft.getMinecraft(); }
    private static Entity camera() { return mc().getRenderViewEntity() == null ? mc().player : mc().getRenderViewEntity(); }
    private static Vec3d eyes() { return camera().getPositionEyes(1); }
    private static Vec3d look() { return camera().getLook(1); }

    public static boolean isHoldingToolItem() {
        ItemStack held = mc().player == null ? ItemStack.EMPTY : mc().player.getHeldItemMainhand();
        return !held.isEmpty() && held.getItem() == ConfigurationHandler.toolItemType
            && (ConfigurationHandler.toolItemMeta < 0 || held.getItemDamage() == ConfigurationHandler.toolItemMeta);
    }
    public static boolean toolActive() { return VisualSettings.rendering && ConfigurationHandler.toolItemEnabled && isHoldingToolItem(); }
    public static ToolMode getCurrentMode() { return currentMode; }
    public static void setCurrentMode(ToolMode mode) { releaseGrab(); currentMode = mode == null ? ToolMode.AREA_SELECTION : mode; }
    public void cycleMode() { cycleMode(true); }
    public static void cycleMode(boolean forward) { setCurrentMode(currentMode.cycle(forward)); WorldHandler.INSTANCE.saveSession(); }

    /** DataManager's operation_mode: the tool mode is remembered per world. */
    public static void saveMode(String world) { UiState.setToolMode(worldName(world), currentMode.name()); }

    public static void restoreMode(String world) {
        String mode = UiState.toolMode(worldName(world));
        setCurrentMode(mode == null ? ToolMode.AREA_SELECTION : ToolMode.fromString(mode));
    }

    private static String worldName(String key) { return key == null || !key.contains("|") ? key : key.substring(0, key.lastIndexOf('|')); }
    public static boolean currentModeUsesSchematic() { return currentMode.getUsesSchematic(); }
    public static boolean currentModeUsesAreaSelection() { return currentMode.getUsesAreaSelection(); }

    /** Flips a boolean option of the tool category, as KeyCallbackToggleBooleanConfigWithMessage when message is set. */
    public static boolean toggleConfig(String name, boolean message) {
        net.minecraftforge.common.config.Property p = ConfigurationHandler.configuration.getCategory("tool").get(name);
        boolean value = !p.getBoolean();
        p.set(value);
        ConfigurationHandler.loadConfiguration();
        ConfigurationHandler.configuration.save();
        if (message) InputHandler.printToggle(InputHandler.prettyName("generic", name), value);
        return value;
    }

    public static boolean hotkey(String id) {
        if (mc().player == null) return false;
        if (id.equals("toolEnabledToggle")) { toggleConfig("toolItemEnabled", true); return true; }
        if (id.equals("toolSelectElements") && ConfigurationHandler.toolItemEnabled) {
            if (currentMode.getUsesBlockPrimary() && Hotkeys.held("toolSelectModifierBlock1")) { pickState(true); return true; }
            if (currentMode.getUsesBlockSecondary() && Hotkeys.held("toolSelectModifierBlock2")) { pickState(false); return true; }
        }
        if (id.equals("toolPlaceCorner1") || id.equals("toolPlaceCorner2")) {
            if (!toolActive()) return false;
            placeCorner(id.equals("toolPlaceCorner1")); return true;
        }
        if (id.equals("toolSelectElements")) {
            if (!toolActive()) return false;
            if (currentMode.getUsesAreaSelection()) {
                if (Hotkeys.held("selectionGrabModifier")) toggleGrab();
                else selectAreaElement(mc().player);
            } else selectPlacement();
            return true;
        }
        if (id.equals("nudgeSelectionPositive") || id.equals("nudgeSelectionNegative")) { nudge(id.endsWith("Positive") ? 1 : -1); return true; }
        return ToolSelectionActions.hotkey(id);
    }

    public static boolean scroll(int wheel) {
        if (!toolActive() || mc().player == null) return false;
        int amount = wheel > 0 ? 1 : -1;
        Area area = AreaSelections.library().selected();
        if (Hotkeys.held("selectionGrabModifier") && currentMode.getUsesAreaSelection()) {
            if (grabbed != null) { grabDistance = Math.max(0, grabDistance + amount); tick(); return true; }
            if (area != null && area.originSelected()) { moveEntire(area, direction(amount)); return true; }
            if (currentMode == ToolMode.MOVE && area != null) {
                Vector3i target = area.origin(), offset = direction(amount); target.add(offset.x, offset.y, offset.z);
                WorldMoveController.moveTo(target); return true;
            }
        }
        if (Hotkeys.held("selectionGrowModifier")) {
            if (currentMode.getUsesAreaSelection()) {
                if (area == null) message("litematica.message.error.no_area_selected");
                else if (area.selectedBox() == null) message("litematica.error.area_selection.grow.no_sub_region_selected");
                else { AreaSelections.capture(); AreaSelections.library().growSelected(area, amount); saveArea(); }
            }
            return true;
        }
        if (Hotkeys.held("selectionNudgeModifier")) return nudge(amount);
        if (Hotkeys.held("operationModeChangeModifier")) { cycleMode(amount < 0 != ConfigurationHandler.reverseOperationModeDirection); return true; }
        if (Hotkeys.held("schematicVersionCycleModifier")) {
            if (SchematicProjects.hasProjectOpen()) SchematicProjects.cycleVersion(-amount);
            return true;
        }
        return false;
    }

    static Vector3i direction(int amount) {
        Vec3d look = look();
        double x = Math.abs(look.x), y = Math.abs(look.y), z = Math.abs(look.z);
        if (y >= x && y >= z) return new Vector3i(0, look.y < 0 ? -amount : amount, 0);
        return x >= z ? new Vector3i(look.x < 0 ? -amount : amount, 0, 0) : new Vector3i(0, 0, look.z < 0 ? -amount : amount);
    }
    public static void nudgeArea(EntityPlayer player, int amount) { nudge(amount); }
    /** Litematica's nudgeSelection: returns false when an area mode has no selected element to move. */
    static boolean nudge(int amount) {
        Vector3i delta = direction(amount);
        if (currentMode.getUsesAreaSelection()) {
            Area area = AreaSelections.library().selected();
            if (area == null || !SchematicaPlus.proxy.isSaveEnabled || !area.originSelected() && area.selectedBox() == null) return false;
            AreaSelections.capture(); AreaSelections.library().moveSelected(area, delta.x, delta.y, delta.z); saveArea();
        } else {
            SchematicWorld world = ClientProxy.schematic;
            if (world == null) return true;
            String region = world.subregions() == null ? null : world.subregions().selected;
            SchematicOrigin origin = region == null ? world.originPosition() : world.subregionPosition(region);
            Vector3i point;
            try {
                point = new Vector3i(Math.addExact(origin.x, delta.x), Math.addExact(origin.y, delta.y), Math.addExact(origin.z, delta.z));
            } catch (ArithmeticException e) {
                message("schematica.ui.placement.region_failed");
                return true;
            }
            movePlacement(point);
        }
        return true;
    }

    private static void message(String key) {
        if (mc().player != null) mc().player.sendMessage(new TextComponentString(TextFormatting.RED + UiTranslations.format(key)));
    }
    static void moveEntire(Area area, Vector3i delta) {
        if (!SchematicaPlus.proxy.isSaveEnabled) return;
        AreaSelections.capture(); AreaSelections.library().moveEntire(area, delta.x, delta.y, delta.z); saveArea();
    }
    static void saveArea() { AreaSelections.apply(); AreaSelections.saveCurrent(); }
    static Vector3i playerPosition() {
        return new Vector3i(MathHelper.floor(mc().player.posX), MathHelper.floor(mc().player.getEntityBoundingBox().minY), MathHelper.floor(mc().player.posZ));
    }
    static RayTraceResult trace(double distance) {
        Vec3d eye = eyes(), look = look();
        return mc().world.rayTraceBlocks(eye, eye.addVector(look.x * distance, look.y * distance, look.z * distance));
    }
    private static Vector3i target(boolean area) {
        RayTraceResult hit = trace(200);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        if (mc().player.isSneaking() == area) pos = pos.offset(hit.sideHit);
        return new Vector3i(pos.getX(), pos.getY(), pos.getZ());
    }
    private static void placeCorner(boolean first) {
        if (currentMode == ToolMode.MOVE && Hotkeys.held("selectionGrabModifier")) {
            Vector3i point = target(false);
            if (point != null) WorldMoveController.moveTo(point);
            return;
        }
        Vector3i point = target(currentMode.getUsesAreaSelection());
        if (point == null) return;
        if (currentMode.getUsesAreaSelection()) {
            if (!SchematicaPlus.proxy.isSaveEnabled) return;
            Area area = AreaSelections.library().selected();
            if (area == null || !area.originSelected() && area.selectedBox() == null) return;
            AreaSelections.capture();
            if (area.originSelected() && Hotkeys.held("selectionGrabModifier")) {
                Vector3i old = area.origin(); AreaSelections.library().moveEntire(area, point.x - old.x, point.y - old.y, point.z - old.z);
            } else AreaSelections.library().click(area, first, point);
            AreaSelections.library().setGuide(area, true); saveArea();
        } else movePlacement(point);
    }
    private static void movePlacement(Vector3i point) {
        SchematicWorld world = ClientProxy.schematic;
        if (world == null || world.placementSettings().locked) return;
        String region = world.subregions() == null ? null : world.subregions().selected;
        // Runs from an input handler: a locked or out-of-limits move must show a message, not crash the game.
        try {
            if (region == null) world.moveOriginTo(point.x, point.y, point.z);
            else {
                world.moveSubregionTo(region, point.x, point.y, point.z);
                RendererSchematicGlobal.INSTANCE.createRendererSchematicChunks(world);
            }
        } catch (RuntimeException e) {
            if (PlacementSettings.LOCKED_MESSAGE.equals(e.getMessage())) message(PlacementSettings.LOCKED_MESSAGE);
            else {
                Reference.logger.warn("Failed to move placement {}", region == null ? "origin" : region, e);
                message("schematica.ui.placement.region_failed");
            }
            return;
        }
        ClientProxy.placementChanged();
        WorldHandler.INSTANCE.saveSession();
    }
    public void onToolUse(EntityPlayer player, SchematicWorld schematic) { placeCorner(false); }
    public void onToolAttack(EntityPlayer player, SchematicWorld schematic) { placeCorner(true); }

    private static SelectionRayTrace.Hit areaHit() {
        Vec3d eyes = eyes(), look = look();
        RayTraceResult block = trace(200);
        double distance = block == null ? 200 : eyes.distanceTo(block.hitVec) + 0.001;
        return SelectionRayTrace.trace(AreaSelections.library().selected(), eyes.x, eyes.y, eyes.z, look.x, look.y, look.z, distance);
    }
    public static void selectAreaElement(EntityPlayer player) {
        if (!SchematicaPlus.proxy.isSaveEnabled) return;
        AreaSelectionLibrary library = AreaSelections.library(); Area area = library.selected();
        if (area == null || !area.guide()) return;
        AreaSelections.capture(); SelectionRayTrace.Hit hit = areaHit();
        if (hit != null && hit.box == null) library.selectOrigin(area, true);
        else if (hit != null) library.selectCorner(area, hit.box, hit.corner);
        else if (trace(200) == null) library.selectBox(area, null);
        saveArea();
    }
    private static void selectPlacement() {
        SchematicWorld previous = ClientProxy.schematic, best = null; String regionName = null;
        Vec3d eye = eyes(), look = look();
        RayTraceResult block = trace(200);
        double distance = block == null ? 200 : eye.distanceTo(block.hitVec) + 0.001;
        for (SchematicWorld world : ClientProxy.loadedSchematics) {
            if (!world.isRenderingEnabled()) continue;
            if (world.subregions() != null) for (SubRegionPlacements.Region region : world.subregions().regions()) {
                if (!region.enabled) continue;
                SchematicRegion box = world.subregionBounds(region.name());
                double hit = SelectionRayTrace.boxDistance(eye.x, eye.y, eye.z, look.x, look.y, look.z,
                    new Vector3i(box.minX, box.minY, box.minZ), new Vector3i(box.maxX, box.maxY, box.maxZ), distance);
                if (hit <= distance) { distance = hit; best = world; regionName = region.name(); }
            }
            SchematicOrigin origin = world.originPosition(); Vector3i p = new Vector3i(origin.x, origin.y, origin.z);
            double hit = SelectionRayTrace.boxDistance(eye.x, eye.y, eye.z, look.x, look.y, look.z, p, p, distance);
            if (hit <= distance) { distance = hit; best = world; regionName = null; }
        }
        if (previous != null && previous.subregions() != null) previous.selectSubregion(null);
        if (best != null || block == null) {
            ClientProxy.selectSchematic(best);
            if (best != null && best.subregions() != null) best.selectSubregion(Hotkeys.held("selectionGrabModifier") ? regionName : null);
            WorldHandler.INSTANCE.saveSession();
        }
    }
    private static void pickState(boolean primary) {
        RayTraceResult hit = trace(200);
        IBlockState state = Blocks.AIR.getDefaultState();
        Vec3d eye = eyes(), look = look();
        double distance = hit == null ? 200 : eye.distanceTo(hit.hitVec);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) state = mc().world.getBlockState(hit.getBlockPos());
        if (VisualSettings.schematicVisible()) for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isRenderingEnabled()) continue;
            Vec3d start = eye.addVector(-world.position.x, -world.position.y, -world.position.z);
            RayTraceResult candidate = world.rayTraceRendered(start, start.addVector(look.x * distance, look.y * distance, look.z * distance));
            if (candidate != null && candidate.typeOfHit == RayTraceResult.Type.BLOCK) {
                double d = start.distanceTo(candidate.hitVec);
                if (d < distance) { distance = d; state = world.getBlockState(candidate.getBlockPos()); }
            }
        }
        if (primary) currentMode.setPrimaryState(state); else currentMode.setSecondaryState(state);
        WorldHandler.INSTANCE.saveSession();
    }
    private static void toggleGrab() {
        if (grabbed != null) { releaseGrab(); return; }
        SelectionRayTrace.Hit hit = areaHit();
        if (hit == null || hit.box == null || !SchematicaPlus.proxy.isSaveEnabled) return;
        selectAreaElement(mc().player); grabbed = AreaSelections.library().selected(); grabWorld = mc().world;
        grabDistance = hit.distance; grabPoint = grabPosition();
    }
    private static Vector3i grabPosition() {
        Vec3d eye = eyes(), look = look();
        return new Vector3i(MathHelper.floor(eye.x + look.x * grabDistance), MathHelper.floor(eye.y + look.y * grabDistance),
            MathHelper.floor(eye.z + look.z * grabDistance));
    }
    private static void releaseGrab() { if (grabbed != null && grabWorld == mc().world) AreaSelections.saveCurrent(); grabbed = null; grabWorld = null; grabPoint = null; }
    public static void tick() {
        WorldMoveController.tick();
        if (grabbed == null) return;
        if (mc().world != grabWorld || mc().currentScreen != null || !toolActive() || grabbed != AreaSelections.library().selected() || !org.lwjgl.opengl.Display.isActive()) { releaseGrab(); return; }
        Vector3i next = grabPosition();
        if (!next.equals(grabPoint)) {
            try {
                AreaSelections.library().moveSelected(grabbed, next.x - grabPoint.x, next.y - grabPoint.y, next.z - grabPoint.z);
                AreaSelections.apply(); grabPoint = next;
            } catch (IllegalArgumentException | ArithmeticException error) { releaseGrab(); }
        }
    }
}
