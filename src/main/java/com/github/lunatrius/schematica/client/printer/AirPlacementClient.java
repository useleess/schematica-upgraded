// Client side of printer air placement, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.network.PacketHandler;
import com.github.lunatrius.schematica.network.message.MessageAirPlace;
import com.github.lunatrius.schematica.util.BlockGroups;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

/** Asks a Plus server to place a fence gate in the air, so the printer needs no scaffold block below it. */
public final class AirPlacementClient {
    /** A request waits this long for the server's block update before it may be sent again. */
    static final long RETRY_MILLIS = 1000;
    private static final Map<BlockPos, Long> pending = new HashMap<>();

    private AirPlacementClient() {}

    public static boolean active() {
        return SchematicaPlus.proxy.supportsAirPlacement;
    }

    /** Whether a request for pos is still waiting for the server. */
    static boolean waiting(BlockPos pos) {
        long now = System.currentTimeMillis();
        for (Iterator<Long> it = pending.values().iterator(); it.hasNext();) if (now - it.next() > RETRY_MILLIS) it.remove();
        return pending.containsKey(pos);
    }

    /** Sends the held slot (the server must know it first) and the request; the held item must be the wanted block. */
    static void send(EntityPlayerSP player, BlockPos pos, IBlockState state) {
        player.connection.sendPacket(new CPacketHeldItemChange(player.inventory.currentItem));
        PacketHandler.INSTANCE.sendToServer(new MessageAirPlace(pos, BlockStates.toString(BlockGroups.stored(state))));
        pending.put(pos.toImmutable(), System.currentTimeMillis());
    }
}
