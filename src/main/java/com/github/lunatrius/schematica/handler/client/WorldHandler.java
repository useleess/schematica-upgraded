package com.github.lunatrius.schematica.handler.client;

import static com.github.lunatrius.schematica.client.util.WorldServerName.worldServerName;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.client.util.WorldSession;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;

/**
 * The per-world session: when the player enters another world, server or dimension, the placements and render layers
 * of the one they leave are saved and those of the new one are restored.
 */
@SideOnly(Side.CLIENT)
public class WorldHandler {

    public static final WorldHandler INSTANCE = new WorldHandler();
    private final WorldSession<World> session = new WorldSession<>(key -> {
        ClientProxy.saveLoadedSchematics(key);
        com.github.lunatrius.schematica.client.selection.AreaSelections.save(key);
        com.github.lunatrius.schematica.client.projects.SchematicProjects.saveSession(key);
        RenderLayerSettings.save(key);
        com.github.lunatrius.schematica.tool.ToolManager.saveMode(key);
    }, ClientProxy::clearWorldState, key -> {
        ClientProxy.lastWorldServerName = key;
        ClientProxy.restoreLoadedSchematics(key);
        com.github.lunatrius.schematica.client.selection.AreaSelections.restore(key);
        com.github.lunatrius.schematica.client.projects.SchematicProjects.restoreSession(key);
        RenderLayerSettings.restore(key);
        com.github.lunatrius.schematica.tool.ToolManager.restoreMode(key);
    });

    private WorldHandler() {}

    public void updateWorld(Minecraft minecraft) {
        try {
            World world = minecraft.world;
            session.update(world, world == null ? null : worldServerName(minecraft, world));
        } catch (Exception e) {
            Reference.logger.error("Could not switch schematic world session", e);
        }
    }

    public void unload(World world) {
        session.unload(world);
    }

    public void closeSession() {
        session.update(null, null);
    }

    public void saveSession() {
        session.save();
    }
}
