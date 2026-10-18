package com.github.lunatrius.schematica.network.message;

import java.io.File;

import net.minecraft.util.text.TextComponentTranslation;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.world.schematic.SchematicFormat;

import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class MessageDownloadEnd implements IMessage, IMessageHandler<MessageDownloadEnd, IMessage> {

    public String name;

    public MessageDownloadEnd() {}

    public MessageDownloadEnd(String name) {
        this.name = name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.name = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.name);
    }

    @Override
    public IMessage onMessage(MessageDownloadEnd message, MessageContext ctx) {
        SchematicaPlus.proxy.runOnClientThread(() -> {
            File directory = SchematicaPlus.proxy.getPlayerSchematicDirectory(null, true);
            File saved = null;
            try {
                ISchematic downloaded = DownloadHandler.INSTANCE.completedSchematic();
                if (downloaded != null) saved = SchematicFormat.saveToFile(directory, message.name, downloaded, null, true, true);
            } catch (java.io.IOException | RuntimeException e) {
                Reference.logger.warn("Could not restore the downloaded regions", e);
            }

            SchematicaPlus.proxy.sendClientMessage(
                new TextComponentTranslation(saved != null ? Names.Command.Download.Message.DOWNLOAD_SUCCEEDED
                    : Names.Command.Download.Message.DOWNLOAD_FAILED, saved != null ? saved.getName() : message.name));

            DownloadHandler.INSTANCE.beginDownload(null);
        });
        return null;
    }
}
