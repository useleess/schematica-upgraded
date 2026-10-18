// Hiding placements put in another world of the same server, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.client.WorldHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;

/**
 * Every two seconds: a placement without a fingerprint (or moved since it was taken, while shown) gets the bedrock
 * under it remembered once its chunks are loaded; a placement whose loaded bedrock differs is hidden until a
 * matching chunk is seen again.
 */
public final class WorldFingerprints {
    private static int ticks;

    private WorldFingerprints() {}

    public static void tick(Minecraft mc) {
        if (mc.world == null || ticks++ % 40 != 0) return;
        boolean changed = false;
        for (SchematicWorld placement : ClientProxy.loadedSchematics) {
            if (!ConfigurationHandler.worldFingerprinting) {
                placement.otherWorld = false;
                continue;
            }
            WorldFingerprint fingerprint = placement.fingerprint;
            boolean moved = fingerprint != null && (fingerprint.x != placement.position.x || fingerprint.y != placement.position.y
                || fingerprint.z != placement.position.z);
            if (fingerprint == null || moved && !placement.otherWorld) {
                WorldFingerprint taken = WorldFingerprint.take(mc.world, placement.enclosingBox(), placement.position.x, placement.position.y, placement.position.z);
                if (taken != null) {
                    placement.fingerprint = taken;
                    changed = true;
                }
                continue;
            }
            WorldFingerprint.State state = fingerprint.judge(mc.world);
            if (state == WorldFingerprint.State.OTHER_WORLD && !placement.otherWorld) {
                placement.otherWorld = true;
                if (mc.player != null) {
                    TextComponentTranslation text = new TextComponentTranslation("schematica.message.other_world", placement.name);
                    text.getStyle().setColor(TextFormatting.GRAY);
                    mc.player.sendMessage(text);
                }
            } else if (state == WorldFingerprint.State.MATCH) {
                placement.otherWorld = false;
            }
        }
        if (changed) WorldHandler.INSTANCE.saveSession();
    }
}
