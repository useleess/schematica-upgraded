// SPDX-License-Identifier: LGPL-3.0-only
// Litematica MaterialListBase / MaterialListPlacement / MaterialListSchematic / MaterialListAreaAnalyzer, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.selection.AreaSelectionLibrary;
import com.github.lunatrius.schematica.client.selection.AreaSelectionLibrary.Area;
import com.github.lunatrius.schematica.client.selection.AreaSelections;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicLibrary;
import com.github.lunatrius.schematica.client.world.SchematicSourceData;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.google.gson.JsonObject;

/** A material list that outlives its screen: the last viewed one is reopened by the hotkey and drawn by the info HUD. */
public final class MaterialList {
    public enum Kind { PLACEMENT, SCHEMATIC, AREA, COMBINED, CUSTOM }

    public final Kind kind;
    private SchematicWorld schematic;
    private final Area area;
    private final AreaSelectionLibrary library;
    private final String fileName;
    private final List<SchematicRegion> fileRegions;
    private final WorldClient openedWorld;
    private final MaterialListModel<MaterialItemKey> model = new MaterialListModel<>();
    private boolean renderLayers;
    private boolean hud;
    private MaterialScanner scan;
    private boolean hasResult;
    private int skipped, unverified;
    private String scanError = "";
    private String language = "";
    private int[] geometry;
    private long layerRevision;
    private Object source;
    private boolean placementEnabled;
    private List<String> transforms = Collections.emptyList();
    private int revision;
    private int ticks;
    private long lastTick = -1;
    private String customName;
    private java.io.File customFile;
    private List<CustomMaterialListFile.Item> customItems = Collections.emptyList();
    /** SCHEMATIC: the file the list was opened from, and whether Replace changed it since it was last written. */
    private java.io.File schematicFile;
    private boolean schematicUnsaved;

    private MaterialList(Kind kind, SchematicWorld schematic, Area area, String fileName, List<SchematicRegion> fileRegions) {
        this.kind = kind;
        this.schematic = schematic;
        this.area = area;
        library = area == null ? null : AreaSelections.library();
        this.fileName = fileName;
        this.fileRegions = fileRegions == null ? null : new ArrayList<>(fileRegions);
        openedWorld = Minecraft.getMinecraft().world;
        model.restoreSort(ConfigurationHandler.sortType);
    }

    /** The material list of a placement, kept with it like SchematicPlacement.getMaterialList(). */
    public static MaterialList placement(SchematicWorld placement) {
        if (placement.materialList == null || placement.materialList.openedWorld != Minecraft.getMinecraft().world) {
            MaterialList list = new MaterialList(Kind.PLACEMENT, placement, null, null, null);
            list.fromJson(placement.materialList != null ? placement.materialList.toJson() : placement.materialListData);
            placement.materialList = list;
        }
        return placement.materialList;
    }

    /** MaterialListSchematic: the items of a schematic file that is not placed. */
    public static MaterialList schematic(SchematicWorld schematic, String name, List<SchematicRegion> regions, java.io.File file) {
        MaterialList list = new MaterialList(Kind.SCHEMATIC, schematic, null, name, regions);
        list.schematicFile = file;
        return list;
    }

    private SchematicLibrary.Source<SchematicSourceData> source() {
        return kind == Kind.PLACEMENT && schematic != null ? ClientProxy.SCHEMATICS.sourceOf(schematic) : null;
    }

    /** Whether Replace can change the blocks of this list's schematic (a placement's source or an opened file). */
    public boolean replaceable() {
        return kind == Kind.SCHEMATIC ? schematicFile != null : source() != null;
    }

    /** The file Save overwrites, or null when there is none to overwrite. */
    public java.io.File saveFile() {
        java.io.File file;
        if (kind == Kind.SCHEMATIC) file = schematicFile;
        else {
            SchematicLibrary.Source<SchematicSourceData> source = source();
            file = source == null || source.memory() ? null : source.file();
        }
        return file != null && file.isFile() && writable(file.getName()) ? file : null;
    }

    /** The folder and file extension Save as writes to. */
    public java.io.File saveDirectory() {
        java.io.File file = kind == Kind.SCHEMATIC ? schematicFile : source() == null ? null : source().file();
        java.io.File directory = file == null ? null : file.getParentFile();
        return directory != null && directory.isDirectory() ? directory : ConfigurationHandler.schematicDirectory;
    }

    public String saveExtension() {
        java.io.File file = kind == Kind.SCHEMATIC ? schematicFile : source() == null ? null : source().file();
        String name = file == null ? "" : file.getName().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot);
        return writable(extension) ? extension : ".litematic";
    }

    private static boolean writable(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".litematic") || lower.endsWith(".schematic") || lower.endsWith(".schemplus") || lower.endsWith(".nbt");
    }

    /** Whether Replace changed blocks that were not written to a file yet. */
    public boolean unsaved() {
        if (kind == Kind.SCHEMATIC) return schematicUnsaved;
        SchematicLibrary.Source<SchematicSourceData> source = source();
        return source != null && source.data().unsaved();
    }

    /** Swaps every block counted as from for to in the schematic (and the placements showing it); returns the changed block count. */
    public long replace(MaterialItemKey from, net.minecraft.block.state.IBlockState to) throws java.io.IOException {
        net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().player;
        long changed;
        if (kind == Kind.SCHEMATIC) {
            changed = SchematicBlockReplace.replace(schematic.getSchematic(), schematic, player, from, to, merged);
            if (changed > 0) schematicUnsaved = true;
        } else {
            SchematicLibrary.Source<SchematicSourceData> source = source();
            if (source == null) return 0;
            com.github.lunatrius.schematica.world.storage.Schematic edited = source.data().editable();
            changed = SchematicBlockReplace.replace(edited, new SchematicWorld(edited), player, from, to, merged);
            if (changed > 0) {
                source.data().changed();
                ClientProxy.refreshSource(source);
            }
        }
        if (changed > 0) refresh();
        return changed;
    }

    /** Writes the schematic with its replaced blocks; returns the written file (a .schematic may become .schemplus). */
    public java.io.File save(java.io.File file) throws java.io.IOException {
        Minecraft mc = Minecraft.getMinecraft();
        com.github.lunatrius.schematica.api.ISchematic content;
        SchematicLibrary.Source<SchematicSourceData> source = source();
        if (kind == Kind.SCHEMATIC) content = schematic.getSchematic();
        else if (source != null) content = source.data().modified() ? source.data().editable() : source.data().composeSource();
        else throw new java.io.IOException("Nothing to save");
        java.io.File written = com.github.lunatrius.schematica.world.schematic.SchematicFormat.saveToFile(file, content, mc.world,
            true, true, mc.player == null ? "" : mc.player.getName());
        if (written == null) throw new java.io.IOException("Could not write " + file);
        if (kind == Kind.SCHEMATIC) schematicUnsaved = false;
        else source.data().markSaved();
        return written;
    }

    public static MaterialList area(Area area) { return new MaterialList(Kind.AREA, null, area, null, null); }

    /** MaterialListCustom: items and counts from a file or another list; nothing is placed, so all are missing. */
    public static MaterialList custom(String name, java.io.File file, List<CustomMaterialListFile.Item> items) {
        MaterialList list = new MaterialList(Kind.CUSTOM, null, null, null, null);
        list.customName = name;
        list.customFile = file;
        list.customItems = new ArrayList<>(items);
        return list;
    }

    /** The items of this list with their total counts, as a custom list file writes them. */
    public List<CustomMaterialListFile.Item> customItems() {
        List<CustomMaterialListFile.Item> items = new ArrayList<>();
        for (MaterialListModel.Entry<MaterialItemKey> entry : model.entries()) {
            Object id = net.minecraft.item.Item.REGISTRY.getNameForObject(entry.key.stack().getItem());
            if (id != null) items.add(new CustomMaterialListFile.Item(id.toString(), entry.key.stack().getItemDamage(), entry.total));
        }
        return items;
    }

    private static MaterialList combined;

    /** All enabled placements counted together, one list per world. */
    public static MaterialList combined() {
        if (combined == null || combined.openedWorld != Minecraft.getMinecraft().world) combined = new MaterialList(Kind.COMBINED, null, null, null, null);
        return combined;
    }

    private List<SchematicWorld> combinedPlacements() {
        List<SchematicWorld> placements = new ArrayList<>();
        for (SchematicWorld placement : ClientProxy.loadedSchematics) if (placement.isEnabled()) placements.add(placement);
        return placements;
    }

    /** Moves a placement's list to the placement that replaced it after a reload. */
    public void rebind(SchematicWorld placement) {
        if (kind == Kind.PLACEMENT) schematic = placement;
    }

    public MaterialListModel<MaterialItemKey> model() { return model; }
    public SchematicWorld schematic() { return schematic; }
    public Area area() { return area; }
    public String fileName() { return fileName; }
    public boolean renderLayers() { return renderLayers; }
    public boolean supportsRenderLayers() { return kind != Kind.SCHEMATIC && kind != Kind.CUSTOM; }
    public boolean hud() { return hud; }
    public void setHud(boolean hud) { this.hud = hud; }
    public boolean scanning() { return scan != null; }
    public int scanPercent() { return scan == null ? 100 : scan.percent(); }
    public boolean hasResult() { return hasResult; }
    public int skipped() { return skipped; }
    public int unverified() { return unverified; }
    public String scanError() { return scanError; }
    /** Changes whenever the entries or their available counts change. */
    public int revision() { return revision; }

    public void setRenderLayers(boolean renderLayers) {
        if (!supportsRenderLayers()) return;
        this.renderLayers = renderLayers;
        refresh();
    }

    public String name() {
        if (kind == Kind.AREA) return area.name();
        if (kind == Kind.COMBINED) return UiTranslations.format("schematica.ui.material.all_placements");
        if (kind == Kind.CUSTOM) return customName;
        return kind == Kind.SCHEMATIC ? fileName : schematic == null ? "-" : schematic.name;
    }

    public String title() {
        switch (kind) {
            case AREA: return UiTranslations.format("litematica.gui.title.material_list.area_analyzer", area.name());
            case CUSTOM: return customFile != null ? UiTranslations.format("litematica.gui.title.material_list.custom_file", customName, customFile.getName())
                : UiTranslations.format("litematica.gui.title.material_list.custom", customName);
            case SCHEMATIC:
                int regions = Math.max(1, schematic.getSchematic().getRegions().size());
                return UiTranslations.format("litematica.gui.title.material_list.schematic", fileName,
                    fileRegions.isEmpty() ? regions : fileRegions.size(), regions);
            default: return UiTranslations.format("litematica.gui.title.material_list.placement", name());
        }
    }

    public boolean validContext() {
        Minecraft mc = Minecraft.getMinecraft();
        if (openedWorld == null || mc.world != openedWorld || mc.player == null) return false;
        switch (kind) {
            case SCHEMATIC: case CUSTOM: return true;
            case AREA: return AreaSelections.available(library) && library.contains(area) && SchematicaPlus.proxy.isSaveEnabled;
            case COMBINED: return !ClientProxy.loadedSchematics.isEmpty();
            default: return schematic != null && ClientProxy.loadedSchematics.contains(schematic);
        }
    }

    private int[] geometry() {
        if (kind == Kind.COMBINED) {
            List<Integer> values = new ArrayList<>();
            for (SchematicWorld placement : combinedPlacements()) {
                values.add(System.identityHashCode(placement)); values.add(System.identityHashCode(placement.getSchematic()));
                values.add(placement.position.x); values.add(placement.position.y); values.add(placement.position.z);
                values.add(placement.transformOperations.hashCode());
                values.add(renderLayers && placement.isRenderingLayer ? placement.renderingLayer : -1);
            }
            int[] result = new int[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = values.get(i);
            return result;
        }
        if (area != null) {
            int[] bounds = new int[area.boxes().size() * 6];
            int i = 0;
            for (SchematicRegion region : area.regions()) {
                bounds[i++] = region.minX; bounds[i++] = region.minY; bounds[i++] = region.minZ;
                bounds[i++] = region.maxX; bounds[i++] = region.maxY; bounds[i++] = region.maxZ;
            }
            return bounds;
        }
        return new int[] {schematic.position.x, schematic.position.y, schematic.position.z,
            schematic.getWidth(), schematic.getHeight(), schematic.getLength(),
            renderLayers && schematic.isRenderingLayer ? schematic.renderingLayer : -1};
    }

    public boolean geometryChanged() {
        if (kind == Kind.SCHEMATIC || kind == Kind.CUSTOM) return false;
        if (renderLayers && layerRevision != RenderLayerSettings.RANGE.revision()) return true;
        if (area != null || kind == Kind.COMBINED) return !Arrays.equals(geometry, geometry());
        return placementEnabled != schematic.isEnabled() || !Arrays.equals(geometry, geometry()) || source != schematic.getSchematic()
            || !transforms.equals(schematic.transformOperations);
    }

    /** reCreateMaterialList: starts a new count; the old entries are dropped. */
    public void refresh() {
        Minecraft mc = Minecraft.getMinecraft();
        scan = null;
        hasResult = false;
        skipped = 0; unverified = 0; scanError = "";
        scanned = Collections.<MaterialListModel.Entry<MaterialItemKey>>emptyList();
        model.setEntries(scanned);
        revision++;
        if (!validContext()) return;
        if (kind == Kind.SCHEMATIC) {
            scan = new SchematicFileMaterialScan(schematic, fileRegions, mc.player);
            return;
        }
        if (kind == Kind.CUSTOM) {
            // reCreateMaterialList reads the source file again
            if (customFile != null && customFile.isFile()) {
                try { customItems = new ArrayList<>(CustomMaterialListFile.read(customFile).items); }
                catch (java.io.IOException error) { com.github.lunatrius.schematica.reference.Reference.logger.warn("Could not reload {}", customFile, error); }
            }
            scan = new CustomMaterialScan(customItems, mc.player);
            return;
        }
        geometry = geometry();
        layerRevision = RenderLayerSettings.RANGE.revision();
        if (kind == Kind.COMBINED) {
            List<MaterialScanner> scans = new ArrayList<>();
            for (SchematicWorld placement : combinedPlacements()) scans.add(new MaterialScan(placement, openedWorld, mc.player, renderLayers));
            scan = new CombinedMaterialScan(scans, mc.player);
        } else if (area != null) {
            try { scan = new AreaMaterialScan(area.regions(), openedWorld, mc.player, renderLayers); }
            catch (IllegalArgumentException error) { scanError = UiTranslations.format("schematica.ui.area.analysis_invalid"); }
        } else {
            source = schematic.getSchematic();
            placementEnabled = schematic.isEnabled();
            transforms = new ArrayList<>(schematic.transformOperations);
            scan = new MaterialScan(schematic, openedWorld, mc.player, renderLayers);
        }
    }

    /** Advances the count and the available item counts; runs at most once per client tick. */
    public void tick() {
        if (lastTick == MaterialLists.tickCount()) return;
        lastTick = MaterialLists.tickCount();
        if (!validContext()) {
            if (scan != null || hasResult) refresh();
            return;
        }
        String currentLanguage = Minecraft.getMinecraft().gameSettings.language;
        if (!java.util.Objects.equals(language, currentLanguage)) {
            language = currentLanguage;
            refresh();
        }
        if (scan == null && !hasResult && scanError.isEmpty() || geometryChanged()) refresh();
        if (scan != null) {
            scan.step();
            if (scan.done()) {
                scanned = scan.result();
                model.setEntries(merged ? merge(scanned) : scanned);
                skipped = scan.skipped();
                unverified = scan.unverified();
                scan = null;
                hasResult = true;
                revision++;
            }
        } else if (++ticks % 20 == 0 && MaterialScan.updateAvailable(model.entries(), Minecraft.getMinecraft().player)) revision++;
    }

    /** The scan's rows, with blocks that stand in for missing blocks in rows of their own. */
    private List<MaterialListModel.Entry<MaterialItemKey>> scanned = Collections.emptyList();
    /** Whether those rows are shown merged into one row per item ("Merge sources"). */
    private boolean merged;

    public boolean merged() { return merged; }

    /** Whether the list has rows of blocks standing in for blocks this game does not have, which can be merged. */
    public boolean hasSources() {
        for (MaterialListModel.Entry<MaterialItemKey> entry : scanned) if (entry.key.origin() != null) return true;
        return merged;
    }

    /** Shows the rows of stand-in blocks merged into the rows of their items, or apart again; nothing in the schematic changes. */
    public void setMerged(boolean merged) {
        if (this.merged == merged) return;
        this.merged = merged;
        if (hasResult) {
            model.setEntries(merged ? merge(scanned) : scanned);
            MaterialScan.updateAvailable(model.entries(), Minecraft.getMinecraft().player);
            revision++;
        }
    }

    /** One row per item: "Stone", "Stone (from Deepslate)" and "Stone (from Tuff)" become one Stone row. */
    static List<MaterialListModel.Entry<MaterialItemKey>> merge(List<MaterialListModel.Entry<MaterialItemKey>> entries) {
        java.util.Map<MaterialItemKey, long[]> sums = new java.util.LinkedHashMap<>();
        java.util.Map<MaterialItemKey, String> registry = new java.util.HashMap<>();
        for (MaterialListModel.Entry<MaterialItemKey> entry : entries) {
            MaterialItemKey plain = entry.key.plain();
            long[] sum = sums.computeIfAbsent(plain, ignored -> new long[4]);
            sum[0] += entry.total; sum[1] += entry.missing; sum[2] += entry.mismatched; sum[3] += entry.unknown;
            registry.putIfAbsent(plain, entry.registryName);
        }
        List<MaterialListModel.Entry<MaterialItemKey>> result = new ArrayList<>();
        for (java.util.Map.Entry<MaterialItemKey, long[]> sum : sums.entrySet()) {
            long[] c = sum.getValue();
            result.add(new MaterialListModel.Entry<>(sum.getKey(), MaterialScan.name(sum.getKey()), registry.get(sum.getKey()),
                clamp(c[0]), clamp(c[1]), clamp(c[2]), clamp(c[3])));
        }
        return result;
    }

    private static int clamp(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (merged) json.addProperty("merge_sources", true);
        json.addProperty("type", renderLayers ? "render_layers" : "all");
        MaterialListModel.Sort sort = model.sort();
        json.addProperty("sort_criteria", sort == MaterialListModel.Sort.NAME || sort == MaterialListModel.Sort.CACHE_ORDER ? sort.name() : "COUNT_" + sort.name());
        json.addProperty("sort_reverse", !model.descending());
        json.addProperty("hide_available", model.hideAvailable());
        json.addProperty("multiplier", model.multiplier());
        com.google.gson.JsonArray ignored = new com.google.gson.JsonArray(), starred = new com.google.gson.JsonArray();
        for (MaterialItemKey key : model.ignored()) add(ignored, key);
        for (MaterialItemKey key : model.starred()) add(starred, key);
        if (ignored.size() > 0) json.add("ignored", ignored);
        if (starred.size() > 0) json.add("starred", starred);
        return json;
    }

    private static void add(com.google.gson.JsonArray array, MaterialItemKey key) {
        String value = key.encode();
        if (value != null) array.add(new com.google.gson.JsonPrimitive(value));
    }

    public void fromJson(JsonObject json) {
        if (json == null) return;
        try {
            if (json.has("type")) renderLayers = "render_layers".equals(json.get("type").getAsString()) && supportsRenderLayers();
            if (json.has("sort_criteria")) {
                String name = json.get("sort_criteria").getAsString().replace("COUNT_", "");
                MaterialListModel.Sort sort = MaterialListModel.Sort.TOTAL;
                for (MaterialListModel.Sort value : MaterialListModel.Sort.values()) if (value.name().equalsIgnoreCase(name)) sort = value;
                model.setSort(sort, !(json.has("sort_reverse") && json.get("sort_reverse").getAsBoolean()));
            }
            if (json.has("hide_available")) model.setHideAvailable(json.get("hide_available").getAsBoolean());
            if (json.has("merge_sources")) setMerged(json.get("merge_sources").getAsBoolean());
            if (json.has("multiplier")) model.setMultiplier(json.get("multiplier").getAsInt());
            if (json.has("ignored")) for (com.google.gson.JsonElement value : json.getAsJsonArray("ignored")) {
                MaterialItemKey key = MaterialItemKey.decode(value.getAsString());
                if (key != null) model.ignore(key);
            }
            if (json.has("starred")) for (com.google.gson.JsonElement value : json.getAsJsonArray("starred")) {
                MaterialItemKey key = MaterialItemKey.decode(value.getAsString());
                if (key != null && !model.isStarred(key)) model.toggleStar(key);
            }
        } catch (RuntimeException ignored) {}
    }
}
