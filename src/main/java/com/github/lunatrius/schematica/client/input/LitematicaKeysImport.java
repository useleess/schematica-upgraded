package com.github.lunatrius.schematica.client.input;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import com.github.lunatrius.schematica.handler.LitematicaSettingsImport;
import com.github.lunatrius.schematica.reference.Reference;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One-time copy of the keybinds of an installed Litematica (malilib config) on the first start. */
public final class LitematicaKeysImport {
    /** Litematica (malilib 0.5x) hotkey names that differ from ours; older Litematica uses our names. */
    private static final Map<String, String> RENAMED = new HashMap<>();
    static {
        String[] pairs = {
            "openMainMenuScreen", "openGuiMainMenu", "openConfigScreen", "openGuiSettings",
            "openMaterialListScreen", "openGuiMaterialList", "openPlacementsListScreen", "openGuiSchematicPlacements",
            "openSchematicVerifierScreen", "openGuiSchematicVerifier", "openAreaSelectionBrowserScreen", "openGuiSelectionManager",
            "openAreaEditorScreen", "openGuiAreaSettings", "openPlacementSettingsScreen", "openGuiPlacementSettings",
            "openLoadedSchematicsListScreen", "openGuiLoadedSchematics", "openSchematicVCSScreen", "openGuiSchematicProjects",
            "saveSchematicToFile", "saveAreaAsSchematicToFile", "createSchematicInMemory", "saveAreaAsInMemorySchematic",
            "easyPlaceActivation", "easyPlaceUseKey", "renderBlockInfoOverlay", "renderInfoOverlay",
            "refreshSchematicRenderer", "rerenderSchematic", "subModeCycle", "selectionModeCycle",
            "invertSchematicRenderState", "invertGhostBlockRenderState", "translucentSchematicRendering", "toggleTranslucentRendering",
            "openLoadSchematicsScreen", "openGuiLoadSchematics", "openSchematicManagerScreen", "openGuiSchematicManager",
            "pickBlockAuto", "pickBlockAutoToggle",
            // on/off options that carry a hotkey
            "easyPlaceMode", "easyPlaceToggle", "pickBlock", "pickBlockToggle", "placementRestriction", "togglePlacementRestriction",
            "signTextPaste", "toggleSignTextPaste", "toolItemEnabled", "toolEnabledToggle",
            "mainRenderingToggle", "toggleAllRendering", "schematicRendering", "toggleSchematicRendering",
            "schematicBlocksRendering", "toggleSchematicBlockRendering", "schematicOverlayRendering", "toggleOverlayRendering",
            "areaSelectionRendering", "toggleAreaSelectionBoxesRendering", "placementBoundingBoxRendering", "togglePlacementBoxesRendering",
            "schematicOverlayRenderThrough", "schematicOverlayRenderThroughBlocks",
            "blockInfoOverlay", "toggleInfoOverlayRendering", "verifierOverlay", "toggleVerifierOverlayRendering"};
        for (int i = 0; i < pairs.length; i += 2) RENAMED.put(pairs[i], pairs[i + 1]);
    }
    private static final Map<String, Integer> NAMES = new HashMap<>();
    static {
        name(Keyboard.KEY_LMENU, "L_ALT", "LMENU", "L_MENU", "LALT", "LEFT_ALT");
        name(Keyboard.KEY_RMENU, "R_ALT", "RMENU", "R_MENU", "RALT", "RIGHT_ALT");
        name(Keyboard.KEY_LSHIFT, "L_SHIFT", "LSHIFT", "LEFT_SHIFT");
        name(Keyboard.KEY_RSHIFT, "R_SHIFT", "RSHIFT", "RIGHT_SHIFT");
        name(Keyboard.KEY_LCONTROL, "L_CTRL", "LCTRL", "LEFT_CTRL", "LCONTROL", "L_CONTROL", "LEFT_CONTROL");
        name(Keyboard.KEY_RCONTROL, "R_CTRL", "RCTRL", "RIGHT_CTRL", "RCONTROL", "R_CONTROL", "RIGHT_CONTROL");
        name(Keyboard.KEY_PRIOR, "PAGE_UP"); name(Keyboard.KEY_NEXT, "PAGE_DOWN");
        name(-100, "LEFT_MOUSE", "MOUSE0", "LMB", "MOUSE_LEFT");
        name(-99, "RIGHT_MOUSE", "MOUSE1", "RMB", "MOUSE_RIGHT");
        name(-98, "MIDDLE_MOUSE", "MOUSE2", "MMB", "MOUSE_MIDDLE");
    }

    private LitematicaKeysImport() {}
    private static void name(int code, String... names) { for (String name : names) NAMES.put(name, code); }

    /** Copies the bindings from the first Litematica config found; returns how many hotkeys were set. */
    public static int run(Path configDirectory, Path gameDirectory, List<Hotkey> bindings) {
        JsonObject root = LitematicaSettingsImport.find(configDirectory, gameDirectory);
        if (root == null) return 0;
        int count = apply(root, bindings);
        Reference.logger.info("Copied " + count + " keybinds from Litematica");
        return count;
    }

    static int apply(JsonObject root, List<Hotkey> bindings) {
        Map<String, Hotkey> byId = new HashMap<>();
        for (Hotkey binding : bindings) byId.put(binding.id, binding);
        int count = 0;
        for (Map.Entry<String, JsonElement> section : root.entrySet()) {
            if (!section.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> entry : section.getValue().getAsJsonObject().entrySet()) {
                String keys = keysOf(section.getKey(), entry.getValue());
                if (keys == null) continue;
                String id = RENAMED.containsKey(entry.getKey()) ? RENAMED.get(entry.getKey()) : entry.getKey();
                Hotkey binding = byId.get(id);
                List<Integer> codes = codes(keys);
                if (binding == null || codes == null) continue;
                try {
                    binding.setKeys(codes);
                    Hotkey.Settings settings = settingsOf(section.getKey(), entry.getValue(), binding.settings);
                    if (settings != null) binding.settings = settings;
                    count++;
                }
                catch (IllegalArgumentException error) { Reference.logger.warn("Skipping Litematica keybind " + entry.getKey() + ": " + keys); }
            }
        }
        return count;
    }

    /** The key list of one config entry: "M,C", {"keys": "M,C"} or an on/off option {"hotkey": {"keys": ...}}. */
    private static String keysOf(String section, JsonElement value) {
        if (value.isJsonPrimitive()) return section.equals("Hotkeys") && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
        if (!value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        if (object.has("hotkey") && object.get("hotkey").isJsonObject()) object = object.getAsJsonObject("hotkey");
        else if (!section.equals("Hotkeys")) return null;
        JsonElement keys = object.get("keys");
        return keys != null && keys.isJsonPrimitive() ? keys.getAsString() : null;
    }

    /** The malilib advanced options of one entry ({"settings": {...}} beside the keys), or null when not stored. */
    private static Hotkey.Settings settingsOf(String section, JsonElement value, Hotkey.Settings current) {
        if (!value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        if (object.has("hotkey") && object.get("hotkey").isJsonObject()) object = object.getAsJsonObject("hotkey");
        if (!object.has("settings") || !object.get("settings").isJsonObject()) return null;
        JsonObject stored = object.getAsJsonObject("settings");
        Hotkey.Settings settings = current.copy();
        try {
            if (stored.has("activate_on")) settings.action = Hotkey.Action.valueOf(stored.get("activate_on").getAsString().toUpperCase(Locale.ROOT));
            if (stored.has("context")) settings.context = Hotkey.Context.valueOf(stored.get("context").getAsString().toUpperCase(Locale.ROOT));
            if (stored.has("allow_empty")) settings.allowEmpty = stored.get("allow_empty").getAsBoolean();
            if (stored.has("allow_extra_keys")) settings.allowExtra = stored.get("allow_extra_keys").getAsBoolean();
            if (stored.has("order_sensitive")) settings.ordered = stored.get("order_sensitive").getAsBoolean();
            if (stored.has("exclusive")) settings.exclusive = stored.get("exclusive").getAsBoolean();
            if (stored.has("cancel")) settings.cancel = stored.get("cancel").getAsBoolean();
            else if (stored.has("cancel_further")) settings.cancel = stored.get("cancel_further").getAsBoolean();
            return settings;
        } catch (RuntimeException error) { return null; }
    }

    /** Key codes for a malilib key list, or null when a key is unknown (that hotkey keeps its default). */
    static List<Integer> codes(String keys) {
        List<Integer> codes = new ArrayList<>();
        if (keys.trim().isEmpty()) return codes;
        for (String part : keys.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            Integer code = NAMES.get(name);
            if (code == null && name.matches("BUTTON[0-9]+")) code = -100 + Integer.parseInt(name.substring(6));
            if (code == null && name.matches("CHAR_[0-9]+")) code = Integer.parseInt(name.substring(5));
            if (code == null) { int index = Keyboard.getKeyIndex(name); if (index != Keyboard.KEY_NONE) code = index; }
            if (code == null || code == 0 || code < -100 || code > 255) return null;
            codes.add(code);
        }
        return codes;
    }
}
