package com.github.lunatrius.schematica.network.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.handler.DownloadHandler;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.network.PacketHandler;
import com.github.lunatrius.schematica.reference.Constants;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

import io.netty.buffer.ByteBuf;

/**
 * One 16x16x16 piece of a download: a palette of block state strings (the registry ids of the two games need not
 * agree), the palette index of every block, then the block entities and entities of the piece as NBT.
 */
public class MessageDownloadChunk implements IMessage, IMessageHandler<MessageDownloadChunk, IMessage> {
    private static final int VOLUME = Constants.SchematicChunk.WIDTH * Constants.SchematicChunk.HEIGHT * Constants.SchematicChunk.LENGTH;

    public int baseX;
    public int baseY;
    public int baseZ;

    /** The block states of the piece, x major then y then z. */
    public IBlockState[] blocks;
    public List<TileEntity> tileEntities;
    public List<Entity> entities;

    public MessageDownloadChunk() {}

    public MessageDownloadChunk(ISchematic schematic, int baseX, int baseY, int baseZ) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;

        this.blocks = new IBlockState[VOLUME];
        this.tileEntities = new ArrayList<>();
        this.entities = new ArrayList<>();

        for (Entity entity : schematic.getEntities()) {
            int ex = Math.max(0, Math.min(schematic.getWidth() - 1, (int) Math.floor(entity.posX))) / 16 * 16;
            int ey = Math.max(0, Math.min(schematic.getHeight() - 1, (int) Math.floor(entity.posY))) / 16 * 16;
            int ez = Math.max(0, Math.min(schematic.getLength() - 1, (int) Math.floor(entity.posZ))) / 16 * 16;
            if (ex == baseX && ey == baseY && ez == baseZ) this.entities.add(entity);
        }

        int i = 0;
        for (int x = 0; x < Constants.SchematicChunk.WIDTH; x++) {
            for (int y = 0; y < Constants.SchematicChunk.HEIGHT; y++) {
                for (int z = 0; z < Constants.SchematicChunk.LENGTH; z++) {
                    this.blocks[i++] = schematic.getBlockState(baseX + x, baseY + y, baseZ + z);
                    final TileEntity tileEntity = schematic.getTileEntity(new BlockPos(baseX + x, baseY + y, baseZ + z));
                    if (tileEntity != null) {
                        this.tileEntities.add(tileEntity);
                    }
                }
            }
        }
    }

    private void copyToSchematic(final ISchematic schematic) {
        int i = 0;
        for (int x = 0; x < Constants.SchematicChunk.WIDTH; x++) {
            for (int y = 0; y < Constants.SchematicChunk.HEIGHT; y++) {
                for (int z = 0; z < Constants.SchematicChunk.LENGTH; z++) {
                    schematic.setBlockState(this.baseX + x, this.baseY + y, this.baseZ + z, this.blocks[i++]);
                }
            }
        }

        for (TileEntity tileEntity : this.tileEntities) {
            schematic.setTileEntity(tileEntity.getPos(), tileEntity);
        }
        for (Entity entity : this.entities) schematic.addEntity(entity);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.baseX = buf.readShort();
        this.baseY = buf.readShort();
        this.baseZ = buf.readShort();

        int paletteSize = buf.readUnsignedShort();
        if (paletteSize < 1 || paletteSize > VOLUME) throw new IllegalArgumentException("Invalid palette size");
        IBlockState[] palette = new IBlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            IBlockState state = BlockStates.fromStateString(ByteBufUtils.readUTF8String(buf));
            palette[i] = state == null ? Blocks.AIR.getDefaultState() : state;
        }

        this.blocks = new IBlockState[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            int index = buf.readUnsignedShort();
            if (index >= paletteSize) throw new IllegalArgumentException("Invalid palette index");
            this.blocks[i] = palette[index];
        }

        this.tileEntities = new ArrayList<>();
        this.entities = new ArrayList<>();

        final NBTTagCompound compound = ByteBufUtils.readTag(buf);
        this.tileEntities = NBTHelper.readTileEntitiesFromCompound(compound, this.tileEntities);

        final NBTTagCompound compound2 = ByteBufUtils.readTag(buf);
        this.entities = NBTHelper.readEntitiesFromCompound(compound2, null, this.entities);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(this.baseX);
        buf.writeShort(this.baseY);
        buf.writeShort(this.baseZ);

        Map<IBlockState, Integer> indices = new HashMap<>();
        List<IBlockState> palette = new ArrayList<>();
        int[] values = new int[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            Integer index = indices.get(this.blocks[i]);
            if (index == null) {
                index = palette.size();
                palette.add(this.blocks[i]);
                indices.put(this.blocks[i], index);
            }
            values[i] = index;
        }
        buf.writeShort(palette.size());
        for (IBlockState state : palette) ByteBufUtils.writeUTF8String(buf, BlockStates.toString(state));
        for (int value : values) buf.writeShort(value);

        ByteBufUtils.writeTag(buf, NBTHelper.writeTileEntitiesToCompound(this.tileEntities));
        ByteBufUtils.writeTag(buf, NBTHelper.writeEntitiesToCompound(this.entities));
    }

    @Override
    public IMessage onMessage(MessageDownloadChunk message, MessageContext ctx) {
        SchematicaPlus.proxy.runOnClientThread(() -> {
            if (!DownloadHandler.INSTANCE.validChunk(message.baseX, message.baseY, message.baseZ)) return;
            // A lost acknowledgement may resend this chunk. Acknowledge it again without duplicating entities.
            if (!DownloadHandler.INSTANCE.hasReceivedChunk(message.baseX, message.baseY, message.baseZ)) {
                message.copyToSchematic(DownloadHandler.INSTANCE.schematic);
                DownloadHandler.INSTANCE.receivedChunk(message.baseX, message.baseY, message.baseZ);
            }
            PacketHandler.INSTANCE.sendToServer(new MessageDownloadChunkAck(message.baseX, message.baseY, message.baseZ));
        });
        return null;
    }
}
