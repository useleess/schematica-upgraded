package com.github.lunatrius.schematica.client.gui.material;

import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.Chunk;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaterialScanTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void distinguishesUnreceivedClientChunksFromLoadedAirChunks() {
        // a chunk the client has not received is not loaded (getLoadedChunk answers null)
        assertFalse(MaterialScan.verifiedChunk(null, 64));
        Chunk loadedAir = new Chunk(null, 100, 100);
        loadedAir.markLoaded(true);
        assertTrue(MaterialScan.verifiedChunk(loadedAir, 64));
        assertFalse(MaterialScan.verifiedChunk(loadedAir, -1));
        assertFalse(MaterialScan.verifiedChunk(loadedAir, 256));
        loadedAir.markLoaded(false);
        assertFalse(MaterialScan.verifiedChunk(loadedAir, 64));
    }
}
