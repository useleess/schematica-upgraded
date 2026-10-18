// Printer mining beyond the vanilla reach, 2026.
package com.github.lunatrius.schematica.network.message;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.handler.ExtendedReach;

/** Client to server: a dig action (like CPacketPlayerDigging start / stop / abort) within the extended range. */
public final class MessageRangedDig implements IMessage, IMessageHandler<MessageRangedDig, IMessage> {
    public static final int START = 0, STOP = 1, ABORT = 2;

    public int action, x, y, z, side;

    public MessageRangedDig() {}

    public MessageRangedDig(int action, BlockPos pos, EnumFacing side) {
        this.action = action;
        this.x = pos.getX(); this.y = pos.getY(); this.z = pos.getZ(); this.side = side.getIndex();
    }

    @Override public void fromBytes(ByteBuf buffer) {
        action = buffer.readUnsignedByte();
        x = buffer.readInt(); y = buffer.readInt(); z = buffer.readInt();
        side = buffer.readUnsignedByte();
        if (action > ABORT || side >= EnumFacing.VALUES.length || buffer.isReadable()) throw new IllegalArgumentException("Invalid ranged dig");
    }

    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeInt(x); buffer.writeInt(y); buffer.writeInt(z);
        buffer.writeByte(side);
    }

    @Override public IMessage onMessage(MessageRangedDig message, MessageContext context) {
        EntityPlayerMP player = context.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> ExtendedReach.dig(player, message.action, new BlockPos(message.x, message.y, message.z),
            EnumFacing.getFront(message.side)));
        return null;
    }
}
