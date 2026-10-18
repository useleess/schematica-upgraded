// Printer restock request: items out of a shulker box in the inventory, 2026.
package com.github.lunatrius.schematica.network.message;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.handler.ShulkerRestock;

/** Client to server: move up to count of item:meta from slot innerSlot of the shulker box at inventory slot boxSlot. */
public final class MessageShulkerTake implements IMessage, IMessageHandler<MessageShulkerTake, IMessage> {
    public int boxSlot, innerSlot, itemId, meta, count;

    public MessageShulkerTake() {}

    public MessageShulkerTake(int boxSlot, int innerSlot, int itemId, int meta, int count) {
        this.boxSlot = boxSlot; this.innerSlot = innerSlot; this.itemId = itemId; this.meta = meta; this.count = count;
    }

    @Override public void fromBytes(ByteBuf buffer) {
        boxSlot = buffer.readUnsignedByte(); innerSlot = buffer.readUnsignedByte();
        itemId = buffer.readInt(); meta = buffer.readShort(); count = buffer.readUnsignedByte();
    }

    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeByte(boxSlot); buffer.writeByte(innerSlot);
        buffer.writeInt(itemId); buffer.writeShort(meta); buffer.writeByte(count);
    }

    @Override public IMessage onMessage(MessageShulkerTake message, MessageContext context) {
        EntityPlayerMP player = context.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> ShulkerRestock.take(player, message.boxSlot, message.innerSlot, message.itemId, message.meta, message.count));
        return null;
    }
}
