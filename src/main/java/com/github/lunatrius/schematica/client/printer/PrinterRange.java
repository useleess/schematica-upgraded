// Printer / Easy Place range, extended on Plus servers, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.network.PacketHandler;
import com.github.lunatrius.schematica.network.message.MessageRangedClick;
import com.github.lunatrius.schematica.network.message.MessageRangedDig;
import com.github.lunatrius.schematica.tool.SchematicTargets;

/**
 * printerWorkRange for the printer and Easy Place. Without Plus on the server it is capped at VANILLA_CAP, which a
 * vanilla server always accepts (reach + 3 from the feet); with Plus at the server's printerMaxRange. Printer
 * clicks and printer mining are extended; the player's own reach keeps the vanilla distance.
 */
public final class PrinterRange {
    /** Eye distance a vanilla server accepts for clicks whatever the player's height and reach attribute. */
    static final double VANILLA_CAP = 6;
    /** Eye distance a vanilla server accepts for dig packets (36 squared from the feet + 1.5), with a margin. */
    static final double VANILLA_DIG = 5.5;
    /** A click this much inside the vanilla limit (client position) is not repeated as a Plus message. */
    private static final double MARGIN = 1;

    private PrinterRange() {}

    private static Minecraft mc() { return Minecraft.getMinecraft(); }

    /** The farthest range allowed now: the server's extended range on a Plus server, else VANILLA_CAP. */
    static double cap() {
        return Math.max(VANILLA_CAP, SchematicaPlus.proxy.serverPrinterRange);
    }

    static double limited(double fallback) {
        return ConfigurationHandler.printerWorkRange > 0 ? Math.min(ConfigurationHandler.printerWorkRange, cap()) : fallback;
    }

    /** The printer's work range. */
    public static double printer() {
        return limited(mc().playerController.getBlockReachDistance());
    }

    /** Easy Place's target range. */
    public static double easyPlace() {
        return limited(SchematicTargets.validBlockRange());
    }

    /**
     * The range in which the printer may mine: the printer range. Without Plus on the server it is capped at
     * VANILLA_DIG, which a vanilla server accepts for dig packets.
     */
    static double mining(double range) {
        return SchematicaPlus.proxy.serverPrinterRange > 0 ? range : Math.min(range, VANILLA_DIG);
    }

    /**
     * Sends a dig action. Beyond the vanilla dig distance on a Plus server it goes as a Plus message instead of the
     * vanilla packet (which the server would ignore); exactly one of the two is sent.
     */
    public static void dig(EntityPlayerSP player, CPacketPlayerDigging.Action action, BlockPos pos, EnumFacing side) {
        if (SchematicaPlus.proxy.serverPrinterRange > 0 && beyondVanillaDig(player, pos)) {
            int kind = action == CPacketPlayerDigging.Action.START_DESTROY_BLOCK ? MessageRangedDig.START
                : action == CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK ? MessageRangedDig.STOP : MessageRangedDig.ABORT;
            PacketHandler.INSTANCE.sendToServer(new MessageRangedDig(kind, pos, side));
        } else {
            player.connection.sendPacket(new CPacketPlayerDigging(action, pos, side));
        }
    }

    /** NetHandlerPlayServer.processPlayerDigging's check ((reach + 1) from the feet + 1.5) may refuse the dig. */
    static boolean beyondVanillaDig(EntityPlayerSP player, BlockPos pos) {
        double reach = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue() + 1 - MARGIN / 2;
        double dx = player.posX - (pos.getX() + 0.5), dy = player.posY - (pos.getY() + 0.5) + 1.5, dz = player.posZ - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz >= reach * reach;
    }

    /**
     * Uses the held item on a block face like processRightClickBlock (the client predicts the result). When the
     * vanilla server may ignore the click for its distance, the click goes to a Plus server as a message too, sent
     * first; the server performs the message only when it ignores the vanilla packet, so the click acts once.
     */
    public static EnumActionResult click(EntityPlayerSP player, BlockPos pos, EnumFacing side, Vec3d hit) {
        if (SchematicaPlus.proxy.serverPrinterRange > 0 && beyondVanilla(player, pos)) {
            player.connection.sendPacket(new CPacketHeldItemChange(player.inventory.currentItem));
            PacketHandler.INSTANCE.sendToServer(new MessageRangedClick(pos, side,
                (float) (hit.x - pos.getX()), (float) (hit.y - pos.getY()), (float) (hit.z - pos.getZ())));
        }
        return mc().playerController.processRightClickBlock(player, mc().world, pos, side, hit, EnumHand.MAIN_HAND);
    }

    /** Whether the server's vanilla check may refuse the click (the server's position may differ a little). */
    static boolean beyondVanilla(EntityPlayerSP player, BlockPos pos) {
        double reach = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue() + 3 - MARGIN;
        return player.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) >= reach * reach;
    }
}
