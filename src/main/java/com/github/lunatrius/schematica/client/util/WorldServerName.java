package com.github.lunatrius.schematica.client.util;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;

public class WorldServerName {

    /** The world folder in single player, or the server address in multiplayer, with the dimension. */
    public static String worldServerName(Minecraft mc) {
        return worldServerName(mc, mc.world);
    }

    public static String worldServerName(Minecraft mc, World world) {
        String name;
        IntegratedServer server = mc.getIntegratedServer();
        if (mc.isSingleplayer() && server != null) {
            name = "save:" + server.getFolderName();
        } else {
            ServerData data = mc.getCurrentServerData();
            name = "server:" + (data == null ? "unknown" : data.serverIP.toLowerCase(Locale.ROOT));
        }
        return name + "|dimension:" + (world == null ? 0 : world.provider.getDimension());
    }
}
