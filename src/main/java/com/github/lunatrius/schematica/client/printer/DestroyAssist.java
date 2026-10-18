// Hold-to-clear destroy assist after the behavior of Buildprint's easy destroy, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.input.Mouse;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;

/**
 * destroyAssist: pressing the attack button on a block a placement wants gone (an extra block where it has air, a
 * wrong block or a block in the wrong state) takes over from the vanilla attack: while the button stays down the
 * nearest such blocks in reach are mined one after another, with the best tool; other blocks are never touched.
 * Only for an attack bound to a mouse button, whose press can be kept from the vanilla attack.
 */
public final class DestroyAssist {
    public static final DestroyAssist INSTANCE = new DestroyAssist();
    private static boolean active;
    private static int button;

    private DestroyAssist() {}

    public static boolean active() { return active; }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ConfigurationHandler.destroyAssist || !event.isButtonstate() || event.getButton() < 0 || mc.currentScreen != null || mc.player == null) return;
        if (event.getButton() - 100 != mc.gameSettings.keyBindAttack.getKeyCode()) return;
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || !wanted(mc.world, hit.getBlockPos())) return;
        event.setCanceled(true);
        active = true;
        button = event.getButton();
    }

    private static boolean wanted(World world, BlockPos pos) {
        List<SchematicWorld> placements = new ArrayList<>();
        for (SchematicWorld placement : ClientProxy.visiblePlacements()) if (placement.isRenderingEnabled()) placements.add(placement);
        SchematicWorld placement = SchematicPrinter.placementAt(placements, pos.getX(), pos.getY(), pos.getZ());
        return placement != null && SchematicPrinter.INSTANCE.breakWanted(world, placement, pos, true, true, true);
    }

    /** Mines on while the button is held; the next block is the looked-at one, else the nearest one in reach. */
    public static void tick(Minecraft mc) {
        if (!active) return;
        EntityPlayerSP player = mc.player;
        World world = mc.world;
        SchematicPrinter printer = SchematicPrinter.INSTANCE;
        if (player == null || world == null || mc.currentScreen != null || !Mouse.isButtonDown(button) || !ConfigurationHandler.destroyAssist) {
            active = false;
            printer.stopMining();
            return;
        }
        double eyeX = player.posX, eyeY = player.posY + player.getEyeHeight(), eyeZ = player.posZ;
        double range = mc.playerController.getBlockReachDistance();
        if (printer.continueMining(world, eyeX, eyeY, eyeZ, range)) return;
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && wanted(world, hit.getBlockPos())) {
            printer.beginMining(world, hit.getBlockPos());
            return;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int r = (int) Math.ceil(range), bx = MathHelper.floor(eyeX), by = MathHelper.floor(eyeY), bz = MathHelper.floor(eyeZ);
        for (int x = bx - r; x <= bx + r; x++) for (int y = Math.max(0, by - r); y <= Math.min(255, by + r); y++) for (int z = bz - r; z <= bz + r; z++) {
            double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ, distance = dx * dx + dy * dy + dz * dz;
            BlockPos pos = new BlockPos(x, y, z);
            if (distance > range * range || distance >= bestDistance || world.isAirBlock(pos) || !wanted(world, pos)) continue;
            best = pos;
            bestDistance = distance;
        }
        if (best != null) printer.beginMining(world, best);
    }
}
