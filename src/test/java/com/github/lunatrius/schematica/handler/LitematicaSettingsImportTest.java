package com.github.lunatrius.schematica.handler;

import java.io.File;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.proxy.CommonProxy;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class LitematicaSettingsImportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private java.lang.reflect.Field minecraftHome;
    private Object previousHome;
    private Configuration previous;
    private CommonProxy previousProxy;

    @Before public void initializeForgeDirectory() throws Exception {
        net.minecraft.init.Bootstrap.register();
        minecraftHome = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        previousHome = minecraftHome.get(null);
        minecraftHome.set(null, temporary.getRoot());
        previousProxy = SchematicaPlus.proxy;
        File data = temporary.getRoot();
        if (SchematicaPlus.proxy == null) SchematicaPlus.proxy = new CommonProxy() {
            @Override public File getDataDirectory() { return data; }
            @Override public boolean loadSchematic(EntityPlayer player, File directory, String filename) { return false; }
            @Override public boolean isPlayerQuotaExceeded(EntityPlayer player) { return false; }
            @Override public File getPlayerSchematicDirectory(EntityPlayer player, boolean privateDirectory) { return data; }
        };
        previous = ConfigurationHandler.configuration;
    }

    @After public void reset() throws Exception {
        ConfigurationHandler.configuration = previous;
        if (previous != null) ConfigurationHandler.loadConfiguration();
        SchematicaPlus.proxy = previousProxy;
        minecraftHome.set(null, previousHome);
    }

    @Test public void malilibOptionsAreCopiedWhereWeHaveThem() {
        ConfigurationHandler.configuration = new Configuration(new File(temporary.getRoot(), "schematica_plus.cfg"));
        ConfigurationHandler.loadConfiguration();
        JsonObject root = new JsonParser().parse("{\"config_version\":1,"
            + "\"Generic\":{\"toolItem\":\"minecraft:bone\",\"pasteCommandLimit\":32,\"customSchematicDirectory\":{\"enabled\":true,\"directory\":\"builds\"},"
            + "\"easyPlaceMode\":{\"enabled\":true,\"hotkey\":{\"keys\":\"X\"}},\"pasteReplaceBehavior\":\"all\",\"unknownOption\":true},"
            + "\"InfoOverlays\":{\"infoHudAlignment\":\"top_left\",\"infoHudOffset\":{\"x\":7,\"y\":9},\"blockInfoLinesFontScale\":0.75,"
            + "\"verifierErrorHighlightAlpha\":0.4,\"infoHudScale\":99.0,\"blockInfoOverlay\":{\"enabled\":false,\"hotkey\":{\"keys\":\"\"}}},"
            + "\"Visuals\":{\"mainRenderingToggle\":{\"enabled\":false,\"hotkey\":{\"keys\":\"M,R\"}},\"translucentSchematicRendering\":{\"b\":true,\"d\":0.3},"
            + "\"schematicOverlayOutlineWidth\":2.5,\"schematicOverlaySides\":false},"
            + "\"Colors\":{\"schematicOverlayMissing\":\"#11223344\",\"verifierCorrect\":{\"color1\":1,\"color2\":2}},"
            + "\"Internal\":{\"createPlacementOnLoad\":false}}").getAsJsonObject();
        assertTrue(LitematicaSettingsImport.apply(ConfigurationHandler.configuration, root) >= 15);
        ConfigurationHandler.loadConfiguration();
        assertEquals("minecraft:bone", ConfigurationHandler.toolItem);
        assertEquals(com.github.lunatrius.schematica.tool.ReplaceBehavior.ALL, ConfigurationHandler.pasteReplaceBehavior);
        assertEquals(com.github.lunatrius.schematica.util.HudAlignment.parse("top_left"), InfoHudSettings.alignment);
        assertEquals(7, InfoHudSettings.offsetX); assertEquals(9, InfoHudSettings.offsetY);
        assertEquals(1.0, InfoHudSettings.scale, 0); // out of our range: kept
        assertEquals(0.75, BlockInfoHudSettings.scale, 1e-9);
        assertEquals(0.4, VerifierOverlaySettings.alpha, 1e-9);
        assertFalse(VisualSettings.blockInfoOverlay); assertFalse(VisualSettings.rendering);
        assertEquals(2.5, VisualSettings.outlineWidth, 1e-9);
        assertEquals(0x11223344, RenderColors.MISSING.color());
        assertEquals(32, ConfigurationHandler.configuration.getCategory("tool").get("commandLimitPerTick").getInt());
        assertTrue(ConfigurationHandler.configuration.getCategory("tool").get("easyPlaceMode").getBoolean());
        assertFalse(ConfigurationHandler.configuration.getCategory("render").get("drawQuads").getBoolean());
        assertTrue(ConfigurationHandler.configuration.getCategory("render").get("alphaEnabled").getBoolean());
        assertEquals(0.3, ConfigurationHandler.configuration.getCategory("render").get("alpha").getDouble(), 1e-9);
        assertTrue(ConfigurationHandler.configuration.getCategory("general").get("schematicDirectory").getString().endsWith("builds"));
    }
}
