// Server placement capability regression coverage, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.CommonProxy;
import com.github.lunatrius.schematica.proxy.ServerProxy;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccuratePlacementClientTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void handshakeDoesNotSkipClientOrientationForUnsupportedBlocks() {
        CommonProxy previous = SchematicaPlus.proxy;
        SchematicaPlus.proxy = new ServerProxy() {
            @Override public java.io.File getDataDirectory() { return new java.io.File("build/tmp"); }
        };
        String protocol = ConfigurationHandler.easyPlaceProtocolVersion;
        try {
            SchematicaPlus.proxy.supportsAccuratePlacement = true;
            ConfigurationHandler.easyPlaceProtocolVersion = "auto";
            assertTrue(AccuratePlacementClient.active(Blocks.OAK_STAIRS));
            assertFalse(AccuratePlacementClient.active(Blocks.STONE));
            ConfigurationHandler.easyPlaceProtocolVersion = "slabs_only";
            assertFalse(AccuratePlacementClient.active(Blocks.OAK_STAIRS));
            assertTrue(AccuratePlacementClient.active(Blocks.STONE_SLAB));
            ConfigurationHandler.easyPlaceProtocolVersion = "none";
            assertFalse(AccuratePlacementClient.active(Blocks.OAK_STAIRS));
            ConfigurationHandler.easyPlaceProtocolVersion = "auto";
            SchematicaPlus.proxy.supportsAccuratePlacement = false;
            assertFalse(AccuratePlacementClient.active(Blocks.OAK_STAIRS));
        } finally {
            SchematicaPlus.proxy = previous;
            ConfigurationHandler.easyPlaceProtocolVersion = protocol;
        }
    }
}
