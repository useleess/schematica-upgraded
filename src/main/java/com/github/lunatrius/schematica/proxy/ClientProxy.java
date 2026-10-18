package com.github.lunatrius.schematica.proxy;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.client.renderer.RendererSchematicGlobal;
import com.github.lunatrius.schematica.client.world.PlacementState;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicLibrary;
import com.github.lunatrius.schematica.client.world.SchematicSourceData;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.command.CommandSchematicaPlus;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.client.ClientHandler;
import com.github.lunatrius.schematica.handler.client.WorldHandler;
import com.github.lunatrius.schematica.handler.client.InputHandler;
import com.github.lunatrius.schematica.client.input.Hotkeys;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.client.world.PlacementSettings;
import com.github.lunatrius.schematica.util.FileUtils;
import com.github.lunatrius.schematica.util.PlusDataFiles;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3d;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.schematic.ImportReport;
import com.github.lunatrius.schematica.world.schematic.ItemIdMaps;
import com.github.lunatrius.schematica.world.schematic.SchematicFileSnapshot;

/**
 * The client side: the loaded sources and placements, the selected placement, and the data the renderer reads. The
 * tools, printer and interface hang off this class and are added with the later steps of the port.
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    private static final Minecraft MINECRAFT = Minecraft.getMinecraft();

    public static final Vector3d playerPosition = new Vector3d();
    /** The corners of the selected box of the area selection (Schematica's points), and their minimum and maximum. */
    public static final com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i pointA = new com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i();
    public static final com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i pointB = new com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i();
    public static final com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i pointMin = new com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i();
    public static final com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i pointMax = new com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i();

    public static void updatePoints() {
        pointMin.x = Math.min(pointA.x, pointB.x);
        pointMin.y = Math.min(pointA.y, pointB.y);
        pointMin.z = Math.min(pointA.z, pointB.z);
        pointMax.x = Math.max(pointA.x, pointB.x);
        pointMax.y = Math.max(pointA.y, pointB.y);
        pointMax.z = Math.max(pointA.z, pointB.z);
    }
    public static boolean isRenderingGuide = false;
    public static EnumFacing orientation = EnumFacing.SOUTH;
    public static int rotationRender = 0;

    /** The currently selected placement (for tools, printer, control screens). */
    public static SchematicWorld schematic = null;
    public static final SchematicLibrary<SchematicSourceData, SchematicWorld> SCHEMATICS = new SchematicLibrary<>(SchematicSourceData::read);
    public static final List<SchematicWorld> loadedSchematics = SCHEMATICS.placements();
    /** What the player is looking at in the selected placement, or null. */
    public static RayTraceResult movingObjectPosition = null;
    /** The last known world/server name. */
    public static String lastWorldServerName = null;

    /** The placements and their grid copies, for rendering and targeting (the visible placements upstream). */
    public static List<SchematicWorld> visiblePlacements() {
        List<SchematicWorld> copies = com.github.lunatrius.schematica.client.world.GridPlacements.INSTANCE.copies();
        boolean hidden = false;
        for (SchematicWorld world : loadedSchematics) hidden |= world.otherWorld;
        if (copies.isEmpty() && !hidden) return loadedSchematics;
        List<SchematicWorld> all = new ArrayList<>(loadedSchematics.size() + copies.size());
        // worldFingerprinting: placements put in another world of the same server are left out
        for (SchematicWorld world : loadedSchematics) if (!world.otherWorld) all.add(world);
        for (SchematicWorld copy : copies) if (copy.gridBase == null || !copy.gridBase.otherWorld) all.add(copy);
        return all;
    }

    public static void setPlayerData(EntityPlayer player, float partialTicks) {
        playerPosition.x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        playerPosition.y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        playerPosition.z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;

        orientation = getOrientation(player);
        rotationRender = MathHelper.floor(player.rotationYaw / 90) & 3;
    }

    private static EnumFacing getOrientation(EntityPlayer player) {
        if (player.rotationPitch > 45) {
            return EnumFacing.DOWN;
        } else if (player.rotationPitch < -45) {
            return EnumFacing.UP;
        }
        return EnumFacing.getHorizontal(MathHelper.floor(player.rotationYaw / 90.0 + 0.5) & 3);
    }

    public static void moveSchematicToPlayer(SchematicWorld placement) {
        EntityPlayerSP player = MINECRAFT.player;
        if (placement != null && player != null) {
            placement.moveOriginTo((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        }
    }

    public static void moveSchematic(SchematicWorld placement, int x, int y, int z) {
        if (placement != null) placement.moveMinimumTo(x, y, z);
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        final Property[] sliders = { ConfigurationHandler.propAlpha, ConfigurationHandler.propBlockDelta,
            ConfigurationHandler.propPlaceDelay, ConfigurationHandler.propTimeout };
        for (Property prop : sliders) {
            prop.setConfigEntryClass(GuiConfigEntries.NumberSliderEntry.class);
        }

        com.github.lunatrius.schematica.world.schematic.MissingBlocks.load(
            new File(event.getModConfigurationDirectory(), "schematica_plus_missing_blocks.json"));
        Hotkeys.initialize(event.getModConfigurationDirectory().toPath(), MINECRAFT.mcDataDir.toPath(),
            InputHandler.INSTANCE::onHotkey, com.github.lunatrius.schematica.tool.ToolManager::scroll,
            com.github.lunatrius.schematica.tool.SchematicRebuild::click);
        VisualSettings.hotkeyHeld = Hotkeys::held;
    }

    @Override
    public void init(FMLInitializationEvent event) {
        com.github.lunatrius.schematica.client.gui.material.MaterialListModel.cachePriority = key -> key instanceof com.github.lunatrius.schematica.client.gui.material.MaterialItemKey
            ? com.github.lunatrius.schematica.client.gui.material.MaterialItemCache.priority((com.github.lunatrius.schematica.client.gui.material.MaterialItemKey) key) : Integer.MAX_VALUE;
        super.init(event);
        MinecraftForge.EVENT_BUS.register(RendererSchematicGlobal.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ClientHandler.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.client.gui.browser.SchematicPreview.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.handler.client.CommandEditQueue.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.handler.client.RemoteEditClient.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.client.printer.SignTextPaste.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.client.printer.DestroyAssist.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.client.printer.ManualMining.INSTANCE);
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.handler.client.ChatEventHandler.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new com.github.lunatrius.schematica.handler.client.OverlayHandler());
        MinecraftForge.EVENT_BUS.register(com.github.lunatrius.schematica.client.container.ContainerVerifier.INSTANCE);
        ClientCommandHandler.instance.registerCommand(new CommandSchematicaPlus());
        ClientCommandHandler.instance.registerCommand(new com.github.lunatrius.schematica.command.CommandSchematicaSetBlock());
    }

    @Override
    public void worldMoveFinished(long id, boolean success) {
        com.github.lunatrius.schematica.tool.WorldMoveController.finished(id, success);
    }

    @Override
    public void remoteEditStatus(com.github.lunatrius.schematica.network.message.MessageEditStatus status) {
        MINECRAFT.addScheduledTask(() -> com.github.lunatrius.schematica.handler.client.RemoteEditClient.INSTANCE.status(status));
    }

    @Override
    public void runOnClientThread(Runnable task) {
        MINECRAFT.addScheduledTask(task);
    }

    @Override
    public void runSaveCallback(Runnable callback) {
        MINECRAFT.addScheduledTask(callback);
    }

    @Override
    public void sendClientMessage(ITextComponent message) {
        if (MINECRAFT.player != null) MINECRAFT.player.sendMessage(message);
    }

    @Override
    public File getDataDirectory() {
        final File file = MINECRAFT.mcDataDir;
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            Reference.logger.debug("Could not canonize path!", e);
        }
        return file;
    }

    @Override
    public void resetSettings() {
        super.resetSettings();
        com.github.lunatrius.schematica.handler.client.ChatEventHandler.INSTANCE.chatLines = 0;
        com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.setPrinting(false);
        com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.setEnabled(true);
        WorldHandler.INSTANCE.closeSession();
        clearWorldState();
        playerPosition.set(0, 0, 0);
        orientation = EnumFacing.SOUTH;
        rotationRender = 0;
    }

    /** Forgets everything that belongs to the world the player was in. */
    public static void clearWorldState() {
        com.github.lunatrius.schematica.client.verifier.VerificationManager.INSTANCE.clear();
        com.github.lunatrius.schematica.client.renderer.hud.BlockInfoHud.INSTANCE.clear();
        com.github.lunatrius.schematica.client.projects.SchematicProjects.clear();
        com.github.lunatrius.schematica.client.gui.material.MaterialLists.clear();
        com.github.lunatrius.schematica.client.world.GridPlacements.INSTANCE.clear();
        com.github.lunatrius.schematica.client.selection.AreaSelections.clear();
        pointA.set(0, 0, 0);
        pointB.set(0, 0, 0);
        pointMin.set(0, 0, 0);
        pointMax.set(0, 0, 0);
        RenderLayerSettings.RANGE.load(null);
        unloadAllSchematics();
        lastWorldServerName = null;
        isRenderingGuide = false;
    }

    @Override
    public void unloadSchematic() {
        if (schematic != null) removePlacement(schematic);
    }

    public static void removePlacement(SchematicWorld world) {
        if (!SCHEMATICS.remove(world)) return;
        RendererSchematicGlobal.INSTANCE.removeRendererSchematicChunks(world);
        if (schematic == world) selectSchematic(loadedSchematics.isEmpty() ? null : loadedSchematics.get(0));
        WorldHandler.INSTANCE.saveSession();
    }

    public static void unloadSource(SchematicLibrary.Source<SchematicSourceData> source) {
        for (SchematicWorld world : SCHEMATICS.unload(source)) {
            RendererSchematicGlobal.INSTANCE.removeRendererSchematicChunks(world);
        }
        if (schematic != null && !loadedSchematics.contains(schematic)) {
            selectSchematic(loadedSchematics.isEmpty() ? null : loadedSchematics.get(0));
        }
    }

    public static void unloadAllSchematics() {
        schematic = null;
        com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.setSchematic(null);
        SCHEMATICS.clear();
        RendererSchematicGlobal.INSTANCE.destroyRendererSchematicChunks();
    }

    public static SchematicLibrary.Source<SchematicSourceData> loadSource(File file) throws IOException {
        boolean known = SCHEMATICS.find(file) != null;
        SchematicLibrary.Source<SchematicSourceData> source = SCHEMATICS.load(file);
        // read without the player asking (placements restored when joining a world): the missing blocks screen
        // waits until the player loads the file from the browser
        if (!known) reportUnknownBlocks(source);
        return source;
    }

    private static final ExecutorService SOURCE_READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Schematica Plus schematic reader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<File> READING = java.util.Collections.synchronizedSet(new HashSet<>());

    /**
     * Reads and decodes a file on a worker thread so that the game keeps running, then adds it on the client thread
     * and passes it on; false when the file is already being read. A file loaded before is passed on right away.
     */
    public static boolean loadSourceAsync(File file, Consumer<SchematicLibrary.Source<SchematicSourceData>> loaded,
        Consumer<Exception> failed) throws IOException {
        File canonical = file.getCanonicalFile();
        SchematicLibrary.Source<SchematicSourceData> existing = SCHEMATICS.find(canonical);
        if (existing != null) {
            // the file was replaced or changed since it was read: read it again instead of showing the old contents
            // (the new copy is not gone through yet, so its missing blocks are asked about)
            boolean replaced = !existing.data().unsaved() && existing.data().changedOnDisk(canonical);
            if (replaced) {
                try {
                    replaceSource(existing, false, false);
                } catch (IOException | RuntimeException e) {
                    failed.accept(e);
                    return true;
                }
            }
            loaded.accept(existing);
            queueMissingBlocksPrompt(existing);
            return true;
        }
        if (!READING.add(canonical)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        SOURCE_READER.execute(() -> {
            SchematicSourceData data = null;
            Exception error = null;
            try {
                data = SchematicSourceData.read(canonical);
            } catch (IOException | RuntimeException e) {
                error = e;
            } catch (OutOfMemoryError e) {
                error = com.github.lunatrius.schematica.util.SchematicLimits.outOfMemory(e);
            } catch (Error e) {
                // StackOverflowError, LinkageError from a broken block/tile class...: report instead of leaving the load hanging
                error = new IOException("Could not read schematic: " + e, e);
            }
            SchematicSourceData read = data;
            Exception problem = error;
            mc.addScheduledTask(() -> {
                READING.remove(canonical);
                if (problem != null) { failed.accept(problem); return; }
                if (mc.world != world) return;
                try {
                    SchematicLibrary.Source<SchematicSourceData> source = SCHEMATICS.add(canonical, read);
                    queueMissingBlocksPrompt(source);
                    reportUnknownBlocks(source);
                    loaded.accept(source);
                } catch (IOException | RuntimeException e) {
                    failed.accept(e);
                } catch (OutOfMemoryError e) {
                    failed.accept(com.github.lunatrius.schematica.util.SchematicLimits.outOfMemory(e));
                }
            });
        });
        return true;
    }

    /** Loaded files with blocks this game does not have and no pick yet; their screens open one after another. */
    private static final java.util.LinkedHashSet<SchematicLibrary.Source<SchematicSourceData>> MISSING_BLOCKS_PROMPTS = new java.util.LinkedHashSet<>();

    /**
     * Asks about a file's missing blocks on the next tick, unless the player went through them since it was read. Only for
     * files the player loads (browser, command) or reads again, never for placements restored when joining a world.
     */
    private static void queueMissingBlocksPrompt(SchematicLibrary.Source<SchematicSourceData> source) {
        if (ConfigurationHandler.missingBlocksPrompt && com.github.lunatrius.schematica.client.gui.control.GuiMissingBlocks.needed(source)) {
            MISSING_BLOCKS_PROMPTS.add(source);
        }
    }

    /** Opens the next missing blocks screen over the screen the load left open, once no other one is open. */
    public static void tickMissingBlocksPrompt(Minecraft mc) {
        if (MISSING_BLOCKS_PROMPTS.isEmpty() || mc.world == null || mc.player == null) return;
        if (mc.currentScreen instanceof com.github.lunatrius.schematica.client.gui.control.GuiMissingBlocks
            || mc.currentScreen instanceof com.github.lunatrius.schematica.client.gui.control.GuiBlockPicker
            // joining a world: the terrain screen closes itself (and anything opened over it) once the world is there
            || mc.currentScreen instanceof net.minecraft.client.gui.GuiDownloadTerrain) return;
        java.util.Iterator<SchematicLibrary.Source<SchematicSourceData>> next = MISSING_BLOCKS_PROMPTS.iterator();
        SchematicLibrary.Source<SchematicSourceData> source = next.next();
        next.remove();
        if (!SCHEMATICS.sources().contains(source) || !com.github.lunatrius.schematica.client.gui.control.GuiMissingBlocks.needed(source)) return;
        mc.displayGuiScreen(new com.github.lunatrius.schematica.client.gui.control.GuiMissingBlocks(mc.currentScreen, source));
    }

    /**
     * Lists in chat the blocks of a newly read file that this game does not have and what replaced them, and the
     * stored item stacks whose ids were translated or removed.
     */
    private static void reportUnknownBlocks(SchematicLibrary.Source<SchematicSourceData> source) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) return;
        Map<String, String> unknown = source.data().takeUnknownBlocks();
        ItemIdMaps.Result items = source.data().takeItemIds();
        if (unknown.isEmpty() && (items == null || items.removed == 0 && items.translatedWith == null)) return;
        Consumer<String> chat = line -> player.sendMessage(new TextComponentString(TextFormatting.GOLD + line));
        if (items != null && items.removed > 0) {
            Reference.logger.warn("{}: {} stored item stacks removed; unknown items: {}", source.file().getName(), items.removed, items.unknownItems);
            chat.accept(I18n.format("schematica.message.import.unknown_items", source.file().getName(), items.removed,
                String.join(", ", new ArrayList<>(items.unknownItems).subList(0, Math.min(8, items.unknownItems.size())))));
        }
        if (unknown.isEmpty()) return;
        Reference.logger.warn("{}: unknown blocks replaced: {}", source.file().getName(), unknown);
        // the missing blocks screen lists them and lets the player choose; chat only points to it
        if (MISSING_BLOCKS_PROMPTS.contains(source)) {
            chat.accept(I18n.format("schematica.message.import.unknown_blocks_prompt", source.file().getName(), unknown.size()));
            return;
        }
        chat.accept(I18n.format("schematica.message.import.unknown_blocks", source.file().getName(), unknown.size()));
        int shown = 0;
        for (Map.Entry<String, String> entry : unknown.entrySet()) {
            if (shown++ == 8) {
                chat.accept(I18n.format("schematica.message.import.unknown_more", unknown.size() - 8));
                break;
            }
            chat.accept(I18n.format("schematica.message.import.unknown_block", entry.getKey(), entry.getValue()));
        }
    }

    /** Registers a captured schematic that has no file; it is not restored in later sessions. */
    public static SchematicLibrary.Source<SchematicSourceData> addMemorySource(String name, SchematicFileSnapshot snapshot) throws IOException {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return SCHEMATICS.addMemory(new File("<memory>", (safe.isEmpty() ? "schematic" : safe) + ".schemplus"), SchematicSourceData.of(snapshot));
    }

    private static SchematicWorld instantiate(SchematicLibrary.Source<SchematicSourceData> source, SchematicSourceData data) throws IOException {
        SchematicWorld world;
        try {
            world = new SchematicWorld(data.instantiate(), source.file().getName());
        } catch (OutOfMemoryError e) {
            throw com.github.lunatrius.schematica.util.SchematicLimits.outOfMemory(e);
        }
        world.setPlacementSource(data);
        world.sourceDirectory = source.file().getParentFile();
        world.sourceFilename = source.file().getName();
        world.isRendering = true;
        return world;
    }

    public static SchematicWorld createPlacement(SchematicLibrary.Source<SchematicSourceData> source) throws IOException {
        SchematicWorld world = SCHEMATICS.create(source, (data, previous) -> instantiate(source, data));
        String base = world.name;
        int suffix = 2;
        Set<String> names = new HashSet<>();
        for (SchematicWorld other : loadedSchematics) if (other != world) names.add(other.name);
        while (names.contains(world.name)) world.name = base + " #" + suffix++;
        com.github.lunatrius.schematica.client.renderer.hud.StatusInfoHud.startOverride();
        return world;
    }

    public static void reloadSource(SchematicLibrary.Source<SchematicSourceData> source) throws IOException {
        replaceSource(source, false, true);
    }

    /** Reads a file again after the missing blocks screen changed picks; it does not ask again. */
    public static void reloadSourceWithPicks(SchematicLibrary.Source<SchematicSourceData> source) throws IOException {
        replaceSource(source, false, false);
    }

    /** Recomposes all placements of a source from its edited in-memory data. */
    public static void refreshSource(SchematicLibrary.Source<SchematicSourceData> source) throws IOException {
        replaceSource(source, true, false);
    }

    private static void replaceSource(SchematicLibrary.Source<SchematicSourceData> source, boolean memory, boolean ask) throws IOException {
        List<SchematicWorld> prepared = new ArrayList<>();
        Map<SchematicWorld, SchematicWorld> changes;
        try {
            SchematicLibrary.Factory<SchematicSourceData, SchematicWorld> factory = (data, previous) -> {
                SchematicWorld next = instantiate(source, data);
                PlacementState.copy(previous, next);
                prepared.add(next);
                RendererSchematicGlobal.INSTANCE.createRendererSchematicChunks(next);
                return next;
            };
            changes = memory ? SCHEMATICS.refresh(source, factory) : SCHEMATICS.reload(source, factory);
        } catch (IOException | RuntimeException e) {
            for (SchematicWorld world : prepared) RendererSchematicGlobal.INSTANCE.removeRendererSchematicChunks(world);
            throw e;
        }
        SchematicWorld selected = changes.containsKey(schematic) ? changes.get(schematic) : schematic;
        for (Map.Entry<SchematicWorld, SchematicWorld> entry : changes.entrySet()) {
            RendererSchematicGlobal.INSTANCE.removeRendererSchematicChunks(entry.getKey());
        }
        if (selected != schematic) select(selected);
        if (!memory) {
            if (ask) queueMissingBlocksPrompt(source);
            reportUnknownBlocks(source);
        }
    }

    /**
     * Writes a file's schematic (read with the missing block picks) over the file itself, without a backup, then reads
     * it again: it holds 1.12 blocks only from then on. A format that cannot be written (.schem) becomes a .litematic
     * of the same name and the original is deleted. Returns the written file.
     */
    public static File saveConverted(SchematicLibrary.Source<SchematicSourceData> source) throws IOException {
        File original = source.file();
        String name = original.getName(), lower = name.toLowerCase(java.util.Locale.ROOT);
        File target = original;
        if (!(lower.endsWith(".litematic") || lower.endsWith(".schematic") || lower.endsWith(".schemplus") || lower.endsWith(".nbt"))) {
            int dot = name.lastIndexOf('.');
            String stem = dot < 0 ? name : name.substring(0, dot);
            target = new File(original.getParentFile(), stem + ".litematic");
            for (int suffix = 1; target.exists(); suffix++) target = new File(original.getParentFile(), stem + "-" + suffix + ".litematic");
        }
        Minecraft mc = Minecraft.getMinecraft();
        File written = com.github.lunatrius.schematica.world.schematic.SchematicFormat.saveToFile(target, source.data().composeSource(), mc.world,
            true, true, mc.player == null ? "" : mc.player.getName());
        if (written == null) throw new IOException("Could not write " + target.getName());
        written = written.getCanonicalFile();
        if (!written.equals(original)) {
            java.nio.file.Files.deleteIfExists(original.toPath());
            sourceRenamed(original, written);
        }
        // read the written file, so the placements and the material list show what is on disk now
        replaceSource(source, false, false);
        source.data().markMissingBlocksAnswered();
        WorldHandler.INSTANCE.saveSession();
        return written;
    }

    public static void sourceRenamed(File previous, File next) throws IOException {
        SCHEMATICS.renamed(previous, next);
        for (SchematicWorld world : loadedSchematics) {
            File file = SCHEMATICS.sourceOf(world).file();
            world.sourceDirectory = file.getParentFile();
            world.sourceFilename = file.getName();
        }
    }

    @Override
    public boolean loadSchematic(EntityPlayer player, File directory, String filename) {
        SchematicWorld world = null;
        try {
            world = createPlacement(loadSource(new File(directory, filename)));
            selectSchematic(world);
            return true;
        } catch (IOException | RuntimeException e) {
            if (world != null) removePlacement(world);
            Reference.logger.error("Failed to load schematic", e);
            return false;
        }
    }

    /** Selects a placement as the active one for tools, printer and control screens. */
    public static void selectSchematic(SchematicWorld world) {
        // Forget the last viewed material list when changing the placement selection
        if (world != schematic) com.github.lunatrius.schematica.client.gui.material.MaterialLists.clear();
        select(world);
    }

    private static void select(SchematicWorld world) {
        ClientProxy.schematic = world;
        com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.setSchematic(world);
        RendererSchematicGlobal.INSTANCE.selectSchematic(world);
    }

    /** Cycles to the next loaded placement. */
    public static void cycleSchematic() {
        if (loadedSchematics.isEmpty()) return;
        if (schematic == null) {
            selectSchematic(loadedSchematics.get(0));
            return;
        }
        int next = (loadedSchematics.indexOf(schematic) + 1) % loadedSchematics.size();
        selectSchematic(loadedSchematics.get(next));
    }


    // ---- remembering the placements of each world

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SESSION_TYPE = new TypeToken<Map<String, List<LoadedSchematicEntry>>>() {}.getType();

    /** One remembered placement (or a source loaded without one) in config/schematica_plus/LoadedSchematics.json. */
    private static class LoadedSchematicEntry {
        public String displayName;
        public String filename;
        public String directory;
        public int X, Y, Z;
        public int RotationX, RotationY, RotationZ;
        public int FlipX, FlipY, FlipZ;
        public boolean isActive;
        public List<String> transforms;
        public Boolean visible, entities, blockNBT, sourceOnly, layerMode;
        public Integer layer;
        public int[] origin;
        public JsonObject subregions;
        public JsonObject placementSettings;
        public JsonObject grid;
        public JsonObject materialList;
        public Integer bb_color;
        public JsonObject fingerprint;

        LoadedSchematicEntry() {}
    }

    private static Map<String, List<LoadedSchematicEntry>> readSessions(File file) throws IOException {
        if (!file.exists()) return new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, List<LoadedSchematicEntry>> data = GSON.fromJson(reader, SESSION_TYPE);
            return data == null ? new HashMap<>() : data;
        } catch (RuntimeException e) {
            throw new IOException("Existing schematic settings are unreadable; preserving the file", e);
        }
    }

    /** Saves the loaded placements of a world or server. */
    public static void saveLoadedSchematics(String worldServerName) {
        if (worldServerName == null || worldServerName.isEmpty()) return;
        try {
            File file = PlusDataFiles.file("LoadedSchematics.json");
            Map<String, List<LoadedSchematicEntry>> allData = readSessions(file);

            List<LoadedSchematicEntry> entries = new ArrayList<>();
            for (SchematicWorld sw : loadedSchematics) {
                if (SCHEMATICS.sourceOf(sw).memory() || sw.projectVersion) continue;
                LoadedSchematicEntry entry = new LoadedSchematicEntry();
                entry.displayName = sw.name;
                entry.filename = sw.sourceFilename;
                entry.directory = sw.sourceDirectory != null ? sw.sourceDirectory.getAbsolutePath() : "";
                entry.X = sw.position.x;
                entry.Y = sw.position.y;
                entry.Z = sw.position.z;
                entry.origin = sw.originPosition().coordinates();
                entry.subregions = sw.subregions() == null ? null : sw.subregions().toJson();
                entry.placementSettings = sw.placementSettings().toJson();
                entry.materialList = sw.materialList != null ? sw.materialList.toJson() : sw.materialListData;
                entry.fingerprint = sw.fingerprint == null ? null : sw.fingerprint.toJson();
                entry.grid = sw.grid.isEnabled() || !sw.grid.isAtDefaultValues() ? sw.grid.toJson() : null;
                entry.bb_color = sw.boxColor;
                entry.RotationX = sw.rotationStateX;
                entry.RotationY = sw.rotationStateY;
                entry.RotationZ = sw.rotationStateZ;
                entry.FlipX = sw.flipStateX;
                entry.FlipY = sw.flipStateY;
                entry.FlipZ = sw.flipStateZ;
                entry.isActive = sw == schematic;
                entry.transforms = new ArrayList<>(sw.transformOperations);
                entry.visible = sw.isRendering;
                entry.entities = sw.isRenderingEntities;
                entry.blockNBT = sw.isPastingBlockNBT;
                entry.layerMode = sw.isRenderingLayer;
                entry.layer = sw.renderingLayer;
                entries.add(entry);
            }
            for (SchematicLibrary.Source<SchematicSourceData> source : SCHEMATICS.sources()) {
                boolean placed = false;
                for (SchematicWorld world : loadedSchematics) if (SCHEMATICS.sourceOf(world) == source) placed = true;
                if (placed || source.memory()) continue;
                LoadedSchematicEntry entry = new LoadedSchematicEntry();
                entry.filename = source.file().getName();
                entry.directory = source.file().getParentFile().getAbsolutePath();
                entry.sourceOnly = true;
                entries.add(entry);
            }
            allData.put(worldServerName, entries);

            FileUtils.writeUtf8Atomically(file, GSON.toJson(allData, SESSION_TYPE));
            Reference.logger.info("Saved {} loaded schematics for '{}'", entries.size(), worldServerName);
        } catch (Exception e) {
            Reference.logger.error("Failed to save loaded schematics", e);
        }
    }

    /** Restores the placements remembered for a world or server. */
    public static void restoreLoadedSchematics(String worldServerName) {
        if (worldServerName == null || worldServerName.isEmpty()) return;
        try {
            List<LoadedSchematicEntry> entries = readSessions(PlusDataFiles.file("LoadedSchematics.json")).get(worldServerName);
            if (entries == null || entries.isEmpty()) return;

            SchematicWorld activeWorld = null;
            for (LoadedSchematicEntry entry : entries) {
                try {
                    if (entry == null || entry.filename == null || entry.filename.isEmpty()) continue;
                    File dir = entry.directory != null && !entry.directory.isEmpty() ? new File(entry.directory) : ConfigurationHandler.schematicDirectory;
                    SchematicLibrary.Source<SchematicSourceData> source = loadSource(new File(dir, entry.filename));
                    if (Boolean.TRUE.equals(entry.sourceOnly)) continue;
                    SchematicWorld world = SCHEMATICS.create(source, (data, previous) -> {
                        SchematicWorld restored = instantiate(source, data);
                        if (entry.displayName != null && !entry.displayName.trim().isEmpty()) restored.name = entry.displayName;
                        List<String> operations = entry.transforms;
                        if (operations == null) {
                            operations = new ArrayList<>();
                            int[] values = {entry.RotationX, entry.RotationY, entry.RotationZ, entry.FlipX, entry.FlipY, entry.FlipZ};
                            String[] axes = {"X", "Y", "Z", "x", "y", "z"};
                            for (int i = 0; i < values.length; i++) {
                                for (int j = 0; j < Math.floorMod(values[i], i < 3 ? 4 : 2); j++) operations.add(axes[i]);
                            }
                        }
                        PlacementState.applyTransforms(restored, operations);
                        restored.restoreSubregions(entry.subregions);
                        SchematicOrigin minimum = restored.getSchematic().getOrigin().restoredMinimum(entry.X, entry.Y, entry.Z, entry.origin);
                        restored.position.set(minimum.x, minimum.y, minimum.z);
                        if (entry.visible != null) restored.isRendering = entry.visible;
                        if (entry.entities != null) restored.isRenderingEntities = entry.entities;
                        if (entry.blockNBT != null) restored.isPastingBlockNBT = entry.blockNBT;
                        restored.isRenderingLayer = Boolean.TRUE.equals(entry.layerMode);
                        if (entry.layer != null) restored.renderingLayer = Math.max(0, Math.min(entry.layer, restored.getHeight() - 1));
                        restored.setPlacementSettings(PlacementSettings.fromJson(entry.placementSettings));
                        restored.grid.fromJson(entry.grid);
                        restored.boxColor = entry.bb_color;
                        restored.materialListData = entry.materialList;
                        restored.fingerprint = com.github.lunatrius.schematica.client.world.WorldFingerprint.fromJson(entry.fingerprint);
                        return restored;
                    });
                    RendererSchematicGlobal.INSTANCE.createRendererSchematicChunks(world);
                    if (entry.isActive) activeWorld = world;
                } catch (Exception | OutOfMemoryError e) {
                    Reference.logger.warn("Could not restore schematic session entry", e);
                }
            }
            selectSchematic(activeWorld);

            Reference.logger.info("Restored {} schematics for '{}'", loadedSchematics.size(), worldServerName);
        } catch (Exception e) {
            Reference.logger.error("Failed to restore loaded schematics", e);
        }
    }


    /** Placement contents or geometry changed; the printer (a later step of the port) listens here. */
    /** A placement changed: the printer forgets its cooldowns and re-reads it. */
    public static void placementChanged() { com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.refresh(); }

    // ---- the positions Schematica remembered per world (Coordinates.json in the schematic directory, read only)

    private static final Type COORDINATES_TYPE = new TypeToken<Map<String, Map<String, SchematicData>>>() {}.getType();

    private static class SchematicData {
        public int X, Y, Z;
        // the Y rotation is stored as "Rotation" for compatibility with older files
        public int RotationX, Rotation, RotationZ;
        public int FlipX, FlipY, FlipZ;

        SchematicData() {}
    }

    /** The position, rotation and flips remembered for a schematic in a world, or null. */
    public static com.github.lunatrius.schematica.util.Coordinates getCoordinates(String worldServerName, String schematicName) {
        File file = new File(ConfigurationHandler.schematicDirectory, com.github.lunatrius.schematica.reference.Constants.Files.Coordinates + ".json");
        if (!file.isFile()) return null;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, Map<String, SchematicData>> coordinates = GSON.fromJson(reader, COORDINATES_TYPE);
            Map<String, SchematicData> world = coordinates == null ? null : coordinates.get(worldServerName);
            SchematicData data = world == null ? null : world.get(schematicName);
            if (data == null) return null;
            return new com.github.lunatrius.schematica.util.Coordinates(data.RotationX, data.Rotation, data.RotationZ,
                data.FlipX, data.FlipY, data.FlipZ, data.X, data.Y, data.Z);
        } catch (Exception e) {
            Reference.logger.debug("Could not read the remembered schematic coordinates", e);
            return null;
        }
    }

    @Override
    public boolean isPlayerQuotaExceeded(EntityPlayer player) {
        return false;
    }

    @Override
    public File getPlayerSchematicDirectory(EntityPlayer player, boolean privateDirectory) {
        return ConfigurationHandler.schematicDirectory;
    }

    /** The result of the last import, for callers that want to show it. */
    public static java.util.Map<String, String> lastImportReport() {
        return ImportReport.last();
    }
}
