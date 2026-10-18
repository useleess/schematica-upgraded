// SPDX-License-Identifier: LGPL-3.0-only
// Accurate placement intent, the Plus counterpart of Litematica's easyPlaceProtocol v3, by HackerRouter, 2026.
package com.github.lunatrius.schematica.network.message;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.handler.AccuratePlacement;

/** Client to server, just before a placement click: the block state (name[properties]) the next placement at x, y, z should end with. */
public final class MessagePlacementIntent implements IMessage, IMessageHandler<MessagePlacementIntent, IMessage> {
    public static final int MAX_STATE_BYTES = 1024;
    public int x, y, z;
    public String state = "";

    public MessagePlacementIntent() {}

    public MessagePlacementIntent(int x, int y, int z, String state) {
        this.x = x; this.y = y; this.z = z; this.state = state;
    }

    @Override public void fromBytes(ByteBuf buffer) {
        x = buffer.readInt(); y = buffer.readInt(); z = buffer.readInt();
        int length = buffer.readUnsignedShort();
        if (length > MAX_STATE_BYTES || length != buffer.readableBytes()) throw new IllegalArgumentException("Invalid placement intent");
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

    @Override public IMessage onMessage(MessagePlacementIntent message, MessageContext context) {
        AccuratePlacement.INSTANCE.intent(context.getServerHandler().player, message);
        return null;
    }
}
