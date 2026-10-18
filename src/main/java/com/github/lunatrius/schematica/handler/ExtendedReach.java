// Server side of the extended printer / Easy Place range, 2026.
package com.github.lunatrius.schematica.handler;

import java.lang.reflect.Field;

import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.github.lunatrius.schematica.network.message.MessageRangedDig;

/**
 * A vanilla server ignores a block click farther than the reach attribute + 3 from the player's feet. A Plus client
 * whose printer or Easy Place range goes beyond that sends the click a second time as a Plus message; this class
 * performs it when the vanilla packet will be ignored (exactly one of the two acts) and the block is within the
 * server's printerMaxRange of the player's eyes. Printer mining beyond the vanilla reach comes as MessageRangedDig.
 */
public final class ExtendedReach {
    private static Field targetPos;

    private ExtendedReach() {}

    /** The farthest distance from the eyes a Plus click may reach; 0 when the server keeps the vanilla reach. */
    public static double maxRange() {
        return ConfigurationHandler.printerMaxRange;
    }

    /** Distance from the eyes allowed for a Plus placement (air placement): the larger of reach + 1 and the max range. */
    public static double placementReach(EntityPlayer player) {
        return Math.max(player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue() + 1, maxRange() > 0 ? maxRange() + 1 : 0);
    }

    /** NetHandlerPlayServer.processTryUseItemOnBlock's distance check, which a vanilla click must pass. */
    public static boolean vanillaReaches(EntityPlayer player, BlockPos pos) {
        double reach = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue() + 3;
        return player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < reach * reach;
    }

    /** Eye distance squared to the block centre. */
    static double eyeDistanceSq(EntityPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.posX, dy = pos.getY() + 0.5 - (player.posY + player.getEyeHeight()), dz = pos.getZ() + 0.5 - player.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Uses the held item on a block face like the vanilla packet would, when only the extended range reaches it. */
    public static void click(EntityPlayerMP player, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ) {
        double range = maxRange();
        if (range <= 0 || player.isSpectator() || teleportPending(player.connection)) return;
        WorldServer world = player.getServerWorld();
        int limit = player.mcServer.getBuildLimit();
        if (pos.getY() >= limit || pos.getY() == limit - 1 && side == EnumFacing.UP) return;
        if (vanillaReaches(player, pos) || eyeDistanceSq(player, pos) > (range + 1) * (range + 1)) return;
        if (!world.isBlockLoaded(pos) || player.mcServer.isBlockProtected(world, pos, player) || !world.getWorldBorder().contains(pos)) return;
        player.markPlayerActive();
        player.interactionManager.processRightClickBlock(player, world, player.getHeldItem(EnumHand.MAIN_HAND), EnumHand.MAIN_HAND, pos, side, hitX, hitY, hitZ);
        player.connection.sendPacket(new SPacketBlockChange(world, pos));
        player.connection.sendPacket(new SPacketBlockChange(world, pos.offset(side)));
    }

    /**
     * NetHandlerPlayServer.processPlayerDigging (start / stop / abort) without its distance check, for a block within
     * the server's printerMaxRange. The client sends either the vanilla packet or this message, never both. The
     * interaction manager still checks the mining time, so blocks break no faster than by hand.
     */
    public static void dig(EntityPlayerMP player, int action, BlockPos pos, EnumFacing side) {
        double range = maxRange();
        if (range <= 0 || player.isSpectator()) return;
        WorldServer world = player.getServerWorld();
        if (pos.getY() >= player.mcServer.getBuildLimit() || !world.isBlockLoaded(pos)) return;
        if (eyeDistanceSq(player, pos) > (range + 1) * (range + 1)) return;
        player.markPlayerActive();
        switch (action) {
            case MessageRangedDig.START:
                if (!player.mcServer.isBlockProtected(world, pos, player) && world.getWorldBorder().contains(pos)) {
                    player.interactionManager.onBlockClicked(pos, side);
                } else {
                    player.connection.sendPacket(new SPacketBlockChange(world, pos));
                }
                return;
            case MessageRangedDig.STOP:
                player.interactionManager.blockRemoving(pos);
                break;
            default:
                player.interactionManager.cancelDestroyingBlock();
                break;
        }
        if (world.getBlockState(pos).getMaterial() != Material.AIR) player.connection.sendPacket(new SPacketBlockChange(world, pos));
    }

    /** NetHandlerPlayServer.targetPos: set while the server waits for a teleport confirmation; vanilla ignores clicks then. */
    private static boolean teleportPending(NetHandlerPlayServer connection) {
        try {
            if (targetPos == null) {
                for (String name : new String[] {"targetPos", "field_184362_y"}) {
                    try {
                        Field field = NetHandlerPlayServer.class.getDeclaredField(name);
                        field.setAccessible(true);
                        targetPos = field;
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (targetPos == null) return false;
            }
            return targetPos.get(connection) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
