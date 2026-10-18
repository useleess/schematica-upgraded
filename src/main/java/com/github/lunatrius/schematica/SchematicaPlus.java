package com.github.lunatrius.schematica;

import java.util.Map;

import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.handler.QueueTickHandler;
import com.github.lunatrius.schematica.proxy.CommonProxy;
import com.github.lunatrius.schematica.reference.Reference;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.common.versioning.ArtifactVersion;
import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion;
import net.minecraftforge.fml.relauncher.Side;

@Mod(
    modid = Reference.MODID,
    name = Reference.NAME,
    version = Reference.VERSION,
    acceptedMinecraftVersions = Reference.MC_VERSION,
    dependencies = Reference.DEPENDENCIES,
    guiFactory = Reference.GUI_FACTORY)
public class SchematicaPlus {

    @Instance(Reference.MODID)
    public static SchematicaPlus instance;

    @SidedProxy(serverSide = Reference.PROXY_SERVER, clientSide = Reference.PROXY_CLIENT)
    public static CommonProxy proxy;

    private static final ArtifactVersion MINIMUM_CLIENT_JOIN_VERSION = new DefaultArtifactVersion("1.0.0-beta.1");

    public SchematicaPlus() {
        // The original mod shares package and asset names with Schematica, so the two cannot coexist.
        String conflict = findLegacyMod(Loader.instance().getIndexedModList().keySet());
        if (conflict != null) {
            throw new LoaderException(
                "Schematica Plus (schematica_plus) cannot load alongside " + conflict
                    + ". Remove the original Schematica jar, or remove Schematica Plus.");
        }
    }

    static String findLegacyMod(Iterable<String> modIds) {
        for (String modId : modIds) {
            if ("schematica".equalsIgnoreCase(modId)) return modId;
        }
        return null;
    }

    @NetworkCheckHandler
    public boolean checkModList(Map<String, String> versions, Side side) {
        return acceptsRemoteMods(Reference.VERSION, versions, side);
    }

    static boolean acceptsRemoteMods(String localVersion, Map<String, String> versions, Side side) {
        if (side == Side.CLIENT && versions.containsKey(Reference.MODID)) {
            String remoteVersion = versions.get(Reference.MODID);
            return localVersion.equals(remoteVersion)
                || MINIMUM_CLIENT_JOIN_VERSION.compareTo(new DefaultArtifactVersion(remoteVersion)) <= 0;
        }
        return true;
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        com.github.lunatrius.schematica.handler.WorldEditQueue.INSTANCE.rollbackBeforeShutdown(
            net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        QueueTickHandler.INSTANCE.clear();
        DownloadHandler.INSTANCE.transferMap.clear();
        com.github.lunatrius.schematica.handler.RemoteEdits.INSTANCE.clear();
        com.github.lunatrius.schematica.handler.WorldEditQueue.INSTANCE.clear();
    }
}
