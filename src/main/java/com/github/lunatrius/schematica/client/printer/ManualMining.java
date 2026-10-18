// Breaking blocks by hand while the printer runs, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.proxy.ClientProxy;

/**
 * The server keeps one digging block per player, so the printer's own mining (and the slot swaps of its placements)
 * would cancel a block the player mines by hand: while the player mines, the printer pauses. A block the player
 * breaks by hand where the placement wanted exactly that block is not placed back until the printer is refreshed
 * (turned on again, placement changed); a wrong or extra block broken by hand is replaced as usual.
 */
public final class ManualMining {
    public static final ManualMining INSTANCE = new ManualMining();
    /** Ticks a clicked block is watched after the player stopped looking at it while mining. */
    private static final int WATCH_TICKS = 20;

    private static final Map<BlockPos, Watch> watched = new HashMap<>();
    private static final Set<BlockPos> broken = new HashSet<>();
    private static boolean mining, printerClick;
    private static World world;

    private static final class Watch {
        final IBlockState state;
        final boolean built;
        int idle;

        Watch(IBlockState state, boolean built) { this.state = state; this.built = built; }
    }

    private ManualMining() {}

    /** Whether the player is mining a block by hand (the printer pauses meanwhile). */
    public static boolean active() { return mining; }

    /** Whether the player broke the placement's block at this position by hand. */
    static boolean skipped(BlockPos pos) { return !broken.isEmpty() && broken.contains(pos); }

    static void forget() { broken.clear(); }

    /** Runs a printer click through PlayerControllerMP.clickBlock without taking it for the player's own. */
    static void printerClick(Runnable click) {
        printerClick = true;
        try { click.run(); }
        finally { printerClick = false; }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (printerClick || !event.getWorld().isRemote) return;
        World world = event.getWorld();
        BlockPos pos = event.getPos();
        List<SchematicWorld> placements = ClientProxy.visiblePlacements();
        SchematicWorld placement = null;
        for (SchematicWorld candidate : placements) {
            if (candidate.isRenderingEnabled() && candidate.isBlockRendered(pos.getX() - candidate.position.x,
                pos.getY() - candidate.position.y, pos.getZ() - candidate.position.z)) { placement = candidate; break; }
        }
        if (placement == null) return;
        IBlockState real = world.getBlockState(pos);
        BlockPos local = new BlockPos(pos.getX() - placement.position.x, pos.getY() - placement.position.y, pos.getZ() - placement.position.z);
        IBlockState state = placement.getBlockState(local);
        watched.put(pos.toImmutable(), new Watch(real, !state.getBlock().isAir(state, placement, local) && PrinterStates.built(world, pos, state, real)));
    }

    /** Called every client tick before the printer: notices hand mining and the blocks it broke. */
    public static void tick(Minecraft mc) {
        if (mc.world != world) {
            watched.clear();
            broken.clear();
            world = mc.world;
        }
        if (world == null || mc.player == null || mc.playerController == null) {
            mining = false;
            return;
        }
        RayTraceResult hit = mc.objectMouseOver;
        BlockPos target = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK ? hit.getBlockPos() : null;
        mining = !DestroyAssist.active() && mc.currentScreen == null
            && (mc.playerController.getIsHittingBlock() || mc.gameSettings.keyBindAttack.isKeyDown() && target != null);
        for (Iterator<Map.Entry<BlockPos, Watch>> it = watched.entrySet().iterator(); it.hasNext();) {
            Map.Entry<BlockPos, Watch> entry = it.next();
            BlockPos pos = entry.getKey();
            Watch watch = entry.getValue();
            IBlockState now = world.getBlockState(pos);
            if (now != watch.state) {
                if (watch.built && now.getBlock().isAir(now, world, pos)) broken.add(pos);
                it.remove();
            } else if (mining && pos.equals(target)) {
                watch.idle = 0;
            } else if (++watch.idle > WATCH_TICKS) {
                it.remove();
            }
        }
    }
}
