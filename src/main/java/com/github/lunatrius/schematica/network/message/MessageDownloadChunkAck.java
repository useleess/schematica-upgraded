package com.github.lunatrius.schematica.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.network.transfer.SchematicTransfer;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class MessageDownloadChunkAck implements IMessage, IMessageHandler<MessageDownloadChunkAck, IMessage> {

    private int baseX;
    private int baseY;
    private int baseZ;

    public MessageDownloadChunkAck() {}

    public MessageDownloadChunkAck(final int baseX, final int baseY, final int baseZ) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.baseX = buf.readShort();
        this.baseY = buf.readShort();
        this.baseZ = buf.readShort();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(this.baseX);
        buf.writeShort(this.baseY);
        buf.writeShort(this.baseZ);
    }

    @Override
    public IMessage onMessage(MessageDownloadChunkAck message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> {
            SchematicTransfer transfer = DownloadHandler.INSTANCE.transferMap.get(player);
            if (transfer != null) {
                transfer.confirmChunk(message.baseX, message.baseY, message.baseZ);
            }
        });
        return null;
    }
}
