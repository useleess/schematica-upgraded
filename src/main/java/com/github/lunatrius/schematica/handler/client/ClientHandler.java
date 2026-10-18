package com.github.lunatrius.schematica.handler.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.client.input.HotkeyHooks;
import com.github.lunatrius.schematica.client.world.SchematicUpdater;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.handler.QueueTickHandler;
import com.github.lunatrius.schematica.network.message.MessageCapabilities;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;

/**
 * Client events: watches the real world for changes (to refresh the overlay), forgets the placements when the world
 * changes, resets the server settings on connection changes, and keeps the "looking at" ray trace current.
 */
@SideOnly(Side.CLIENT)
public class ClientHandler {

    public static final ClientHandler INSTANCE = new ClientHandler();

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private volatile boolean pendingReset;

    private ClientHandler() {}

    @SubscribeEvent
    public void onLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote) {
            Reference.logger.debug("Adding world listener to {}", world);
            world.addEventListener(SchematicUpdater.INSTANCE);
        }
    }

    @SubscribeEvent
    public void onUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote) {
            world.removeEventListener(SchematicUpdater.INSTANCE);
            WorldHandler.INSTANCE.unload(world);
        }
    }

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Reference.logger.info("Scheduling client settings reset.");
        pendingReset = true;
        com.github.lunatrius.schematica.client.printer.LagMonitor.install(event.getManager());
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        DownloadHandler.INSTANCE.beginDownload(null);
        com.github.lunatrius.schematica.handler.client.CommandEditQueue.INSTANCE.cancel();
        MessageCapabilities.clearPending();
        QueueTickHandler.INSTANCE.clear();
        Reference.logger.info("Scheduling client settings reset.");
        pendingReset = true;
    }

    private int printerTicks;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) com.github.lunatrius.schematica.client.gui.material.MaterialLists.startTick();
        if (event.phase != TickEvent.Phase.END) return;
        HotkeyHooks.tick();
        com.github.lunatrius.schematica.tool.ToolManager.tick();
        this.minecraft.mcProfiler.startSection("schematica");
        if (pendingReset) {
            SchematicaPlus.proxy.resetSettings();
            pendingReset = false;
        }
        MessageCapabilities.applyPending();
        WorldHandler.INSTANCE.updateWorld(this.minecraft);
        if (this.minecraft.world != null && this.minecraft.player != null) {
            QueueTickHandler.INSTANCE.clientTick(this.minecraft.world, this.minecraft.player);
        }
        com.github.lunatrius.schematica.client.world.GridPlacements.INSTANCE.tick(this.minecraft);
        com.github.lunatrius.schematica.client.verifier.VerificationManager.INSTANCE.tick(this.minecraft);
        com.github.lunatrius.schematica.client.align.AutoAlignJob.tick(this.minecraft);
        com.github.lunatrius.schematica.client.projects.SchematicProjects.syncSelections();
        com.github.lunatrius.schematica.client.container.ContainerLabels.tick(this.minecraft);
        com.github.lunatrius.schematica.client.world.WorldFingerprints.tick(this.minecraft);
        com.github.lunatrius.schematica.tool.RebuildJobs.tick(this.minecraft);
        com.github.lunatrius.schematica.client.gui.material.MaterialLists.tick();
        com.github.lunatrius.schematica.proxy.ClientProxy.tickMissingBlocksPrompt(this.minecraft);
        com.github.lunatrius.schematica.client.printer.EasyPlace.tick(this.minecraft);
        com.github.lunatrius.schematica.client.printer.DestroyAssist.tick(this.minecraft);
        com.github.lunatrius.schematica.client.printer.ManualMining.tick(this.minecraft);
        com.github.lunatrius.schematica.client.printer.NextBlockHint.tick(this.minecraft);
        if (this.minecraft.player != null) {
            this.minecraft.mcProfiler.startSection("printer");
            com.github.lunatrius.schematica.client.printer.SchematicPrinter printer = com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE;
            if (com.github.lunatrius.schematica.handler.ConfigurationHandler.printerAutoDisable
                && (this.minecraft.player.isDead || this.minecraft.player.getHealth() <= 0)) {
                printer.stopWithMessage("schematica.message.printer.died");
            }
            printer.showPendingMessage();
            printer.tickIdle();
            // placeDelay is the interval between passes in ticks (0 and 1: every tick)
            if (printer.isEnabled() && printer.isPrinting()
                && ++this.printerTicks >= Math.max(1, com.github.lunatrius.schematica.handler.ConfigurationHandler.placeDelay)) {
                this.printerTicks = 0;
                printer.print();
            }
            this.minecraft.mcProfiler.endSection();
        }
        this.minecraft.mcProfiler.endSection();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        SchematicWorld schematic = ClientProxy.schematic;
        ClientProxy.movingObjectPosition = schematic != null ? rayTrace(schematic, 1.0f) : null;
    }

    private RayTraceResult rayTrace(SchematicWorld schematic, float partialTicks) {
        Entity view = this.minecraft.getRenderViewEntity();
        if (view == null) return null;

        // A large fixed distance, so the targeting is not limited to the player's reach.
        final double rayTraceDistance = 256.0;

        Vec3d position = view.getPositionEyes(partialTicks).addVector(-schematic.position.x, -schematic.position.y, -schematic.position.z);
        Vec3d look = view.getLook(partialTicks);
        Vec3d end = position.addVector(look.x * rayTraceDistance, look.y * rayTraceDistance, look.z * rayTraceDistance);
        return schematic.rayTraceRendered(position, end);
    }
}
