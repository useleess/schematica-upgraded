package com.github.lunatrius.schematica.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import com.github.lunatrius.schematica.reference.Reference;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** One-time copy of an installed Litematica's options (malilib config) into ours on the first start. */
public final class LitematicaSettingsImport {
    /** Litematica (malilib 0.5x) option names that differ from ours; older Litematica mostly uses our names. */
    private static final Map<String, String> RENAMED = new HashMap<>();
    static {
        String[] pairs = {
            // Generic
            "easyPlaceHold", "easyPlaceHoldEnabled", "generateLowerCaseNames", "generateLowercaseNames",
            "materialListIgnoreBlockState", "materialListIgnoreState", "materialListFromContainer", "materialListContainerScan",
            "pasteCommandInterval", "commandTaskInterval", "pasteCommandLimit", "commandLimitPerTick", "pickBlock", "pickBlockEnabled",
            "defaultAreaSelectionMode", "defaultSelectionMode", "pickBlockShulkerBoxes", "pickBlockShulkers",
            "showHelpfulReminders", "warnDisabledRendering",
            // InfoOverlays
            "blockInfoLines", "blockInfoLinesEnabled", "blockInfoOverlay", "blockInfoOverlayEnabled", "verifierOverlay", "verifierOverlayEnabled",
            "verifierErrorHighlightAlpha", "verifierErrorHilightAlpha", "verifierErrorHighlightMaxPositions", "verifierErrorHilightMaxPositions",
            // Visuals
            "areaSelectionBoxSides", "renderAreaSelectionBoxSides", "areaSelectionRendering", "enableAreaSelectionBoxesRendering",
            "mainRenderingToggle", "enableRendering", "schematicBlocksRendering", "enableSchematicBlocksRendering",
            "schematicOverlayRendering", "highlight", "schematicRendering", "enableSchematicRendering",
            "schematicOverlayRenderThrough", "schematicOverlayRenderThroughBlocks", "schematicOverlayTypeExtra", "highlightAir",
            "placementBoundingBoxRendering", "enablePlacementBoxesRendering", "schematicOverlayOutlines", "drawLines",
            "schematicOverlaySides", "drawQuads", "verifierHighlightConnections", "renderErrorMarkerConnections",
            "verifierHighlightSides", "renderErrorMarkerSides", "translucentBlockInnerSides", "renderTranslucentBlockInnerSides",
            "schematicOverlayEnableOutlines", "drawLines", "schematicOverlayEnableSides", "drawQuads",
            "enableSchematicOverlay", "highlight", "renderBlocksAsTranslucent", "alphaEnabled", "ghostBlockAlpha", "alpha",
            // Colors
            "areaSelectionBoxSide", "areaSelectionBoxSideColor", "materialListHudItemCounts", "materialListHudItemCountsColor",
            "schematicEditBreakPlaceOverlay", "schematicRebuildBreakPlaceOverlayColor", "schematicEditReplaceOverlay", "schematicRebuildReplaceOverlayColor",
            "schematicOverlayExtra", "schematicOverlayColorExtra", "schematicOverlayMissing", "schematicOverlayColorMissing",
            "schematicOverlayWrongBlock", "schematicOverlayColorWrongBlock", "schematicOverlayWrongState", "schematicOverlayColorWrongState"};
        for (int i = 0; i < pairs.length; i += 2) RENAMED.put(pairs[i], pairs[i + 1]);
    }
    /** Options stored as {"b": on/off, "d": number}: our on/off option, then our number option (or null). */
    private static final Map<String, String[]> BOOLEAN_DOUBLE = new HashMap<>();
    static {
        BOOLEAN_DOUBLE.put("translucentSchematicRendering", new String[] {"alphaEnabled", "alpha"});
        BOOLEAN_DOUBLE.put("placementBoxSides", new String[] {"renderPlacementBoxSides", "placementBoxSideAlpha"});
        BOOLEAN_DOUBLE.put("placementEnclosingBoxSides", new String[] {"renderPlacementEnclosingBoxSides", null});
    }

    private LitematicaSettingsImport() {}

    /** The first Litematica config found (LiteLoader, then Forge/Fabric location), or null. */
    public static JsonObject find(Path configDirectory, Path gameDirectory) {
        for (Path file : new Path[] {gameDirectory.resolve("liteconfig/common/litematica.json"), configDirectory.resolve("litematica.json")}) {
            if (!Files.isRegularFile(file)) continue;
            try {
                if (Files.size(file) > 4 * 1024 * 1024) continue;
                return new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException | RuntimeException error) { Reference.logger.warn("Could not read Litematica settings from " + file, error); }
        }
        return null;
    }

    /** Copies every Litematica option we also have; returns how many were set. */
    public static int apply(Configuration config, JsonObject root) {
        int count = 0;
        for (Map.Entry<String, JsonElement> section : root.entrySet()) {
            if (!section.getValue().isJsonObject() || section.getKey().equals("Hotkeys") || section.getKey().equals("Internal")) continue;
            for (Map.Entry<String, JsonElement> entry : section.getValue().getAsJsonObject().entrySet()) {
                String name = entry.getKey();
                JsonElement value = entry.getValue();
                String ours = RENAMED.containsKey(name) ? RENAMED.get(name) : name;
                if (value.isJsonPrimitive()) { if (set(config, ours, value.getAsJsonPrimitive())) count++; continue; }
                if (!value.isJsonObject()) continue;
                JsonObject object = value.getAsJsonObject();
                if (name.equals("customSchematicDirectory")) {
                    if (bool(object, "enabled") && primitive(object, "directory") != null && set(config, "schematicDirectory", primitive(object, "directory"))) count++;
                } else if (BOOLEAN_DOUBLE.containsKey(name)) {
                    String[] targets = BOOLEAN_DOUBLE.get(name);
                    if (primitive(object, "b") != null && set(config, targets[0], primitive(object, "b"))) count++;
                    if (targets[1] != null && primitive(object, "d") != null && set(config, targets[1], primitive(object, "d"))) count++;
                } else if (primitive(object, "x") != null && primitive(object, "y") != null) {
                    if (set(config, ours + "X", primitive(object, "x"))) count++;
                    if (set(config, ours + "Y", primitive(object, "y"))) count++;
                } else if (primitive(object, "enabled") != null) {
                    if (set(config, ours, primitive(object, "enabled"))) count++;
                }
            }
        }
        return count;
    }

    private static JsonPrimitive primitive(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
    }
    private static boolean bool(JsonObject object, String key) {
        JsonPrimitive value = primitive(object, key);
        return value != null && value.isBoolean() && value.getAsBoolean();
    }

    /** Sets our option of that name (in whichever category has it) when the value fits its type and limits. */
    private static boolean set(Configuration config, String key, JsonPrimitive value) {
        Property property = null;
        for (String name : config.getCategoryNames()) {
            ConfigCategory category = config.getCategory(name);
            if (category.containsKey(key)) { property = category.get(key); break; }
        }
        if (property == null || property.isList()) return false;
        try {
            switch (property.getType()) {
                case BOOLEAN:
                    if (!value.isBoolean()) return false;
                    property.set(value.getAsBoolean()); return true;
                case INTEGER:
                    if (!value.isNumber()) return false;
                    int number = value.getAsInt();
                    if (!inRange(property, number)) return false;
                    property.set(number); return true;
                case DOUBLE:
                    if (!value.isNumber()) return false;
                    double decimal = value.getAsDouble();
                    if (!Double.isFinite(decimal) || !inRange(property, decimal)) return false;
                    property.set(decimal); return true;
                default:
                    if (!value.isString()) return false;
                    String text = value.getAsString();
                    String[] valid = property.getValidValues();
                    if (valid != null && valid.length > 0) {
                        String match = Arrays.stream(valid).filter(text::equalsIgnoreCase).findFirst().orElse(null);
                        if (match == null) return false;
                        text = match;
                    }
                    if (property.getValidationPattern() != null && !property.getValidationPattern().matcher(text).matches()) return false;
                    property.set(text); return true;
            }
        } catch (NumberFormatException error) { return false; }
    }
    private static boolean inRange(Property property, double value) {
        return (property.getMinValue() == null || value >= Double.parseDouble(property.getMinValue()))
            && (property.getMaxValue() == null || value <= Double.parseDouble(property.getMaxValue()));
    }
}
