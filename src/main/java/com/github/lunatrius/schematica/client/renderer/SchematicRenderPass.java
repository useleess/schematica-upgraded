package com.github.lunatrius.schematica.client.renderer;

import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.client.ForgeHooksClient;

/** Sets Forge's current render layer while schematic blocks are compiled, as chunk rendering does for canRenderInLayer. */
public final class SchematicRenderPass implements AutoCloseable {

    public SchematicRenderPass(BlockRenderLayer layer) {
        ForgeHooksClient.setRenderLayer(layer);
    }

    @Override
    public void close() {
        ForgeHooksClient.setRenderLayer(null);
    }
}
