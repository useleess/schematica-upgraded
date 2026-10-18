// Mining a block for the printer and the destroy assist without PlayerControllerMP, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * PlayerControllerMP's survival mining (clickBlock, then onPlayerDamageBlock every tick) with its own progress.
 * Minecraft.sendClickBlockToController resets the controller's mining every tick the attack key is up, so a block
 * mined for the printer while the player does not hold the key would never break; the dig packets and the breaking
 * progress are therefore kept here, where that reset does not reach them. PlayerControllerMP's five-tick hit delay
 * between blocks is left out: the server does not enforce it, and it made the printer mine far slower than it places.
 */
public final class BlockMiner {
    public enum State { IDLE, MINING, BROKEN }

    private final Minecraft mc = Minecraft.getMinecraft();
    private BlockPos pos = BlockPos.ORIGIN;
    private EnumFacing side = EnumFacing.UP;
    private int slot = -1, ticks;
    private float progress;
    private boolean active;

    public boolean active() { return active; }
    public boolean at(BlockPos other) { return active && pos.equals(other); }

    /** Starts mining a block (a creative player breaks it right away); BROKEN when it is gone already. */
    public State start(BlockPos target, EnumFacing face) {
        EntityPlayerSP player = mc.player;
        World world = mc.world;
        if (active) abort();
        IBlockState state = world.getBlockState(target);
        if (state.getMaterial() == Material.AIR) return State.BROKEN;
        slot = -1;
        syncSlot(player);
        pos = target; side = face; progress = 0; ticks = 0;
        PrinterRange.dig(player, CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, side);
        if (mc.playerController.isInCreativeMode()) {
            mc.playerController.onPlayerDestroyBlock(pos);
            return State.BROKEN;
        }
        state.getBlock().onBlockClicked(world, pos, player);
        if (state.getPlayerRelativeBlockHardness(player, world, pos) >= 1.0F) {
            mc.playerController.onPlayerDestroyBlock(pos);
            return State.BROKEN;
        }
        active = true;
        return State.MINING;
    }

    /** Mines on for a tick; BROKEN once the block broke, IDLE when there is nothing to mine. */
    public State tick() {
        if (!active) return State.IDLE;
        EntityPlayerSP player = mc.player;
        World world = mc.world;
        IBlockState state = world.getBlockState(pos);
        if (state.getMaterial() == Material.AIR) {
            clear();
            return State.BROKEN;
        }
        syncSlot(player);
        progress += state.getPlayerRelativeBlockHardness(player, world, pos);
        if (ticks++ % 4 == 0) {
            SoundType sound = state.getBlock().getSoundType(state, world, pos, player);
            mc.getSoundHandler().playSound(new PositionedSoundRecord(sound.getHitSound(), SoundCategory.NEUTRAL,
                (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F, pos));
        }
        player.swingArm(EnumHand.MAIN_HAND);
        if (progress >= 1.0F) {
            PrinterRange.dig(player, CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, side);
            mc.playerController.onPlayerDestroyBlock(pos);
            clear();
            return State.BROKEN;
        }
        world.sendBlockBreakProgress(player.getEntityId(), pos, (int) (progress * 10.0F) - 1);
        return State.MINING;
    }

    /** Stops mining and tells the server, like PlayerControllerMP.resetBlockRemoving. */
    public void abort() {
        if (!active) return;
        if (mc.player != null) PrinterRange.dig(mc.player, CPacketPlayerDigging.Action.ABORT_DESTROY_BLOCK, pos, EnumFacing.DOWN);
        clear();
    }

    private void clear() {
        if (active && mc.world != null && mc.player != null) mc.world.sendBlockBreakProgress(mc.player.getEntityId(), pos, -1);
        active = false;
        progress = 0;
    }

    /** The server must know the held tool before the dig packets (PlayerControllerMP.syncCurrentPlayItem). */
    private void syncSlot(EntityPlayerSP player) {
        int current = player.inventory.currentItem;
        if (current != slot) {
            player.connection.sendPacket(new CPacketHeldItemChange(current));
            slot = current;
        }
    }
}
