package com.github.lunatrius.schematica.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;

import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.network.transfer.SchematicTransfer;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class MessageDownloadBeginAck implements IMessage, IMessageHandler<MessageDownloadBeginAck, IMessage> {

    public boolean supportsGeometry = true;
    public boolean supportsRegions = true;

    @Override
    public void fromBytes(ByteBuf buf) {
        this.supportsGeometry = buf.isReadable() && buf.readBoolean();
        this.supportsRegions = buf.isReadable() && buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.supportsGeometry);
        buf.writeBoolean(this.supportsRegions);
    }

    @Override
    public IMessage onMessage(MessageDownloadBeginAck message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> {
            SchematicTransfer transfer = DownloadHandler.INSTANCE.transferMap.get(player);
            if (transfer != null && transfer.state == SchematicTransfer.State.BEGIN) {
                if (!transfer.acceptGeometrySupport(message.supportsGeometry) || !transfer.acceptRegionSupport(message.supportsRegions)) {
                    DownloadHandler.INSTANCE.transferMap.remove(player);
                    String key = "schematica.message.download.update_client";
                    player.sendMessage(new TextComponentString(I18n.canTranslate(key) ? I18n.translateToLocal(key)
                        : "Schematica Plus: update the client to download schematics with subregions or custom origins."));
                    return;
                }
                transfer.setState(SchematicTransfer.State.CHUNK_WAIT);
            }
        });
        return null;
    }
}
