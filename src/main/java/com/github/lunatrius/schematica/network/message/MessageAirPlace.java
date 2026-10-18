// Printer air placement request, 2026.
package com.github.lunatrius.schematica.network.message;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.handler.AirPlacement;

/** Client to server: place the held block at x, y, z in the air as the given state (name[properties]). */
public final class MessageAirPlace implements IMessage, IMessageHandler<MessageAirPlace, IMessage> {
    public int x, y, z;
    public String state = "";

    public MessageAirPlace() {}

    public MessageAirPlace(BlockPos pos, String state) {
        this.x = pos.getX(); this.y = pos.getY(); this.z = pos.getZ(); this.state = state;
    }

    @Override public void fromBytes(ByteBuf buffer) {
        x = buffer.readInt(); y = buffer.readInt(); z = buffer.readInt();
        int length = buffer.readUnsignedShort();
        if (length > MessagePlacementIntent.MAX_STATE_BYTES || length != buffer.readableBytes()) throw new IllegalArgumentException("Invalid air placement");
        byte[] name = new byte[length];
        buffer.readBytes(name);
        state = new String(name, StandardCharsets.UTF_8);
    }

    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeInt(x); buffer.writeInt(y); buffer.writeInt(z);
        byte[] name = state.getBytes(StandardCharsets.UTF_8);
        buffer.writeShort(name.length);
        buffer.writeBytes(name);
    }

    @Override public IMessage onMessage(MessageAirPlace message, MessageContext context) {
        EntityPlayerMP player = context.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> AirPlacement.place(player, new BlockPos(message.x, message.y, message.z), message.state));
        return null;
    }
}
