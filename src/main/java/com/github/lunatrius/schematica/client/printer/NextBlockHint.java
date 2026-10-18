// Next block to place, after the behavior of Buildprint's next-block highlight, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * highlightNextBlock: a pulsing box on the nearest schematic block that is still missing and can be placed now,
 * because a solid block is beside it. Searched every few ticks within a few blocks beyond reach.
 */
public final class NextBlockHint {
    private static final int SEARCH_EXTRA = 3, INTERVAL = 4;
    private static BlockPos next;
    private static int ticks;

    private NextBlockHint() {}

    public static void tick(Minecraft mc) {
        if (!ConfigurationHandler.highlightNextBlock || mc.player == null || mc.world == null) {
            next = null;
            return;
        }
        if (ticks++ % INTERVAL != 0) return;
        next = find(mc.player, mc.world, mc.playerController.getBlockReachDistance() + SEARCH_EXTRA);
    }

    static BlockPos find(EntityPlayerSP player, World world, double range) {
        List<SchematicWorld> placements = new ArrayList<>();
        for (SchematicWorld placement : ClientProxy.visiblePlacements()) if (placement.isRenderingEnabled()) placements.add(placement);
        if (placements.isEmpty()) return null;
        double eyeX = player.posX, eyeY = player.posY + player.getEyeHeight(), eyeZ = player.posZ;
        int r = (int) Math.ceil(range), bx = MathHelper.floor(eyeX), by = MathHelper.floor(eyeY), bz = MathHelper.floor(eyeZ);
        double feetY = player.getEntityBoundingBox().minY;
        BlockPos best = null;
        double bestDistance = range * range;
        for (int x = bx - r; x <= bx + r; x++) for (int y = Math.max(0, by - r); y <= Math.min(255, by + r); y++) for (int z = bz - r; z <= bz + r; z++) {
            double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ, distance = dx * dx + dy * dy + dz * dz;
            if (distance >= bestDistance) continue;
            SchematicWorld placement = SchematicPrinter.placementAt(placements, x, y, z);
            if (placement == null) continue;
            BlockPos local = new BlockPos(x - placement.position.x, y - placement.position.y, z - placement.position.z);
            IBlockState state = placement.getBlockState(local);
            if (state.getBlock().isAir(state, placement, local) || FluidPrinter.isFluid(state.getBlock())) continue;
            BlockPos pos = new BlockPos(x, y, z);
            IBlockState real = world.getBlockState(pos);
            if (PrinterStates.pistonBusy(state, real) || PrinterStates.built(world, pos, state, real) || !real.getBlock().isReplaceable(world, pos)) continue;
            if (MultiBlockPlacement.kind(state.getBlock()) != null && MultiBlockPlacement.secondary(state)) continue;
            // not where the player stands
            if (Math.abs(x + 0.5 - player.posX) < 0.8 && Math.abs(z + 0.5 - player.posZ) < 0.8 && y >= Math.floor(feetY) && y <= Math.floor(feetY + 1.7)) continue;
            if (PlacementSolver.solidSides(world, pos).length == 0) continue;
            best = pos;
            bestDistance = distance;
        }
        return best;
    }

    /** A pulsing outline through walls; world coordinates minus the camera. */
    public static void render(double cx, double cy, double cz) {
        BlockPos target = next;
        if (target == null) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 150.0);
        PrinterHighlights.begin(3f, true);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        PrinterHighlights.box(buffer, target.getX(), target.getY(), target.getZ(), 0.02 + 0.04 * pulse, cx, cy, cz, 0.2f, 1f, 1f, 0.4f + 0.5f * pulse);
        Tessellator.getInstance().draw();
        PrinterHighlights.end(true);
    }
}
