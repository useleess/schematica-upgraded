// Printer / Easy Place click beyond the vanilla reach, 2026.
package com.github.lunatrius.schematica.network.message;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.handler.ExtendedReach;

/** Client to server: use the main hand item on a block face (like CPacketPlayerTryUseItemOnBlock) within the extended range. */
public final class MessageRangedClick implements IMessage, IMessageHandler<MessageRangedClick, IMessage> {
    public int x, y, z, side;
    public float hitX, hitY, hitZ;

    public MessageRangedClick() {}

    public MessageRangedClick(BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ) {
        this.x = pos.getX(); this.y = pos.getY(); this.z = pos.getZ(); this.side = side.getIndex();
        this.hitX = hitX; this.hitY = hitY; this.hitZ = hitZ;
    }

    @Override public void fromBytes(ByteBuf buffer) {
        x = buffer.readInt(); y = buffer.readInt(); z = buffer.readInt();
        side = buffer.readUnsignedByte();
        hitX = buffer.readFloat(); hitY = buffer.readFloat(); hitZ = buffer.readFloat();
        if (side >= EnumFacing.VALUES.length || buffer.isReadable()) throw new IllegalArgumentException("Invalid ranged click");
    }

    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeInt(x); buffer.writeInt(y); buffer.writeInt(z);
        buffer.writeByte(side);
        buffer.writeFloat(hitX); buffer.writeFloat(hitY); buffer.writeFloat(hitZ);
    }

    @Override public IMessage onMessage(MessageRangedClick message, MessageContext context) {
        EntityPlayerMP player = context.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> ExtendedReach.click(player, new BlockPos(message.x, message.y, message.z),
            EnumFacing.getFront(message.side), message.hitX, message.hitY, message.hitZ));
        return null;
    }
}
