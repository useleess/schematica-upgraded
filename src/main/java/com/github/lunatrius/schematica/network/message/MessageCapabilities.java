package com.github.lunatrius.schematica.network.message;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.reference.Reference;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class MessageCapabilities implements IMessage, IMessageHandler<MessageCapabilities, IMessage> {

    public boolean isPrinterEnabled;
    public boolean isSaveEnabled;
    public boolean isLoadEnabled;
    public boolean supportsWorldMove = true;
    public boolean supportsRemoteEdit, supportsAccuratePlacement, supportsAirPlacement, supportsShulkerRestock;
    public float printerRange;

    public MessageCapabilities() {
        this(false, false, false);
    }

    public MessageCapabilities(boolean isPrinterEnabled, boolean isSaveEnabled, boolean isLoadEnabled) {
        this.isPrinterEnabled = isPrinterEnabled;
        this.isSaveEnabled = isSaveEnabled;
        this.isLoadEnabled = isLoadEnabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.isPrinterEnabled = buf.readBoolean();
        this.isSaveEnabled = buf.readBoolean();
        this.isLoadEnabled = buf.readBoolean();
        supportsWorldMove = buf.isReadable() && buf.readBoolean();
        supportsRemoteEdit = buf.isReadable() && buf.readBoolean();
        supportsAccuratePlacement = buf.isReadable() && buf.readBoolean();
        supportsAirPlacement = buf.isReadable() && buf.readBoolean();
        printerRange = buf.readableBytes() >= 4 ? buf.readFloat() : 0;
        supportsShulkerRestock = buf.isReadable() && buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.isPrinterEnabled);
        buf.writeBoolean(this.isSaveEnabled);
        buf.writeBoolean(this.isLoadEnabled);
        buf.writeBoolean(supportsWorldMove);
        buf.writeBoolean(supportsRemoteEdit);
        buf.writeBoolean(supportsAccuratePlacement);
        buf.writeBoolean(supportsAirPlacement);
        buf.writeFloat(printerRange);
        buf.writeBoolean(supportsShulkerRestock);
    }

    private static volatile MessageCapabilities pending;

    /** Runs on the network thread; the capabilities are applied on the client thread after the connection reset. */
    @Override
    public IMessage onMessage(MessageCapabilities message, MessageContext ctx) {
        pending = message;
        return null;
    }

    public static void clearPending() { pending = null; }

    /** Called each client tick after a pending settings reset. */
    public static void applyPending() {
        MessageCapabilities message = pending;
        if (message == null) return;
        pending = null;
        SchematicaPlus.proxy.printerEnabledByServer = message.isPrinterEnabled;
        com.github.lunatrius.schematica.client.printer.SchematicPrinter.INSTANCE.setEnabled(message.isPrinterEnabled);
        SchematicaPlus.proxy.isSaveEnabled = message.isSaveEnabled;
        SchematicaPlus.proxy.isLoadEnabled = message.isLoadEnabled;
        SchematicaPlus.proxy.supportsWorldMove = message.supportsWorldMove;
        SchematicaPlus.proxy.supportsRemoteEdit = message.supportsRemoteEdit;
        SchematicaPlus.proxy.supportsAccuratePlacement = message.supportsAccuratePlacement;
        SchematicaPlus.proxy.supportsAirPlacement = message.supportsAirPlacement;
        SchematicaPlus.proxy.serverPrinterRange = message.printerRange;
        SchematicaPlus.proxy.supportsShulkerRestock = message.supportsShulkerRestock;

        Reference.logger.info(
            "Server capabilities{printer={}, save={}, load={}, remoteEdit={}, accuratePlacement={}, airPlacement={}, printerRange={}, shulkerRestock={}}",
            message.isPrinterEnabled,
            message.isSaveEnabled,
            message.isLoadEnabled,
            message.supportsRemoteEdit,
            message.supportsAccuratePlacement,
            message.supportsAirPlacement,
            message.printerRange,
            message.supportsShulkerRestock);
    }
}
