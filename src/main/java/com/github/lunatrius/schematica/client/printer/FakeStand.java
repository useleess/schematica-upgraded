// Fake standing positions for facings a rotation cannot give, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * In 1.12 pistons, observers, dispensers and droppers face up or down only by the placer's position
 * (EnumFacing.getDirectionFromEntityLiving: less than 2 blocks away on X and Z, eyes more than 2 above the block or below
 * it), never by the pitch. When no rotation gives the schematic facing, the printer reports a nearby position for the
 * click and its real one right after. A position is only used when the server's movement check moves the player there
 * and back without touching a block (the target counts as solid for the way back), else the server would set the player
 * back.
 */
public final class FakeStand {
    /** The server refuses moves of 10 blocks and more per packet. */
    static final double MAX_DISTANCE = 8;
    private static final double HALF_WIDTH = 0.3, HEIGHT = 1.8;

    private FakeStand() {}

    /** Reports feet as the player's position; the client player stands there too, so its own prediction agrees. Returns the real one. */
    static Vec3d enter(EntityPlayerSP player, Vec3d feet) {
        Vec3d real = new Vec3d(player.posX, player.posY, player.posZ);
        player.connection.sendPacket(new CPacketPlayer.Position(feet.x, feet.y, feet.z, player.onGround));
        player.posX = feet.x; player.posY = feet.y; player.posZ = feet.z;
        return real;
    }

    /** Puts the player back and reports the real position right after the click. */
    static void leave(EntityPlayerSP player, Vec3d real) {
        player.posX = real.x; player.posY = real.y; player.posZ = real.z;
        player.connection.sendPacket(new CPacketPlayer.Position(real.x, real.y, real.z, player.onGround));
    }

    /** Which way getDirectionFromEntityLiving points for a player standing at feet. */
    enum Zone { ABOVE, BELOW, SIDE }

    static Zone zone(BlockPos target, double x, double y, double z, double eyeHeight) {
        if (Math.abs(x - (target.getX() + 0.5)) < 2 && Math.abs(z - (target.getZ() + 0.5)) < 2) {
            double eyes = y + eyeHeight;
            if (eyes - target.getY() > 2) return Zone.ABOVE;
            if (target.getY() - eyes > 0) return Zone.BELOW;
        }
        return Zone.SIDE;
    }

    /**
     * For each zone the player is not in, the nearest feet position in it that the server accepts; heights keep a margin
     * for sneaking and standing eyes (1.54 to 1.62).
     */
    public static List<Vec3d> candidates(World world, EntityPlayer player, BlockPos target) {
        Vec3d real = new Vec3d(player.posX, player.posY, player.posZ);
        Zone current = zone(target, real.x, real.y, real.z, player.getEyeHeight());
        List<Vec3d> found = new ArrayList<>(3);
        for (Zone wanted : Zone.values()) {
            if (wanted == current) continue;
            Vec3d best = null;
            double bestDistance = MAX_DISTANCE * MAX_DISTANCE;
            for (Vec3d feet : spots(target, wanted)) {
                double distance = feet.squareDistanceTo(real);
                if (distance >= bestDistance || !reachable(world, target, real, feet)) continue;
                best = feet;
                bestDistance = distance;
            }
            if (best != null) found.add(best);
        }
        return found;
    }

    /** Feet positions on block centers in the zone. */
    static List<Vec3d> spots(BlockPos target, Zone zone) {
        List<Vec3d> spots = new ArrayList<>();
        int range = zone == Zone.SIDE ? 2 : 1;
        int[] heights = zone == Zone.ABOVE ? new int[] {1, 2} : zone == Zone.BELOW ? new int[] {-2, -3} : new int[] {-1, 0, 1};
        for (int dy : heights) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    // the side zone: 2 blocks away on X or Z, or right below with the eyes inside the block's height
                    if (zone == Zone.SIDE && Math.max(Math.abs(dx), Math.abs(dz)) < 2 && (dy != -1 || dx == 0 && dz == 0)) continue;
                    spots.add(new Vec3d(target.getX() + dx + 0.5, target.getY() + dy, target.getZ() + dz + 0.5));
                }
            }
        }
        return spots;
    }

    static AxisAlignedBB body(Vec3d feet) {
        return new AxisAlignedBB(feet.x - HALF_WIDTH, feet.y, feet.z - HALF_WIDTH, feet.x + HALF_WIDTH, feet.y + HEIGHT, feet.z + HALF_WIDTH);
    }

    /** Whether the server moves the player from real to feet and back (Entity.move: Y, then X, then Z) without a collision. */
    static boolean reachable(World world, BlockPos target, Vec3d real, Vec3d feet) {
        if (real.squareDistanceTo(feet) >= MAX_DISTANCE * MAX_DISTANCE) return false;
        AxisAlignedBB block = new AxisAlignedBB(target);
        return clear(world, block, real, feet) && clear(world, block, feet, real);
    }

    private static boolean clear(World world, AxisAlignedBB block, Vec3d from, Vec3d to) {
        AxisAlignedBB box = body(from);
        double[] steps = {to.y - from.y, to.x - from.x, to.z - from.z};
        for (int axis = 0; axis < 3; axis++) {
            double step = steps[axis];
            AxisAlignedBB moved = axis == 0 ? box.offset(0, step, 0) : axis == 1 ? box.offset(step, 0, 0) : box.offset(0, 0, step);
            // shrunk a little: standing on a block or touching a wall is not a collision
            AxisAlignedBB swept = box.union(moved).shrink(0.001);
            if (swept.intersects(block) || collides(world, swept)) return false;
            box = moved;
        }
        return true;
    }

    /** World.collidesWithAnyBlock without its loaded-chunk test at y 64; unloaded blocks read as air. */
    private static boolean collides(World world, AxisAlignedBB box) {
        List<AxisAlignedBB> boxes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = MathHelper.floor(box.minX); x < MathHelper.ceil(box.maxX); x++) {
            for (int z = MathHelper.floor(box.minZ); z < MathHelper.ceil(box.maxZ); z++) {
                // fences and walls reach half a block higher than their cell
                for (int y = MathHelper.floor(box.minY) - 1; y < MathHelper.ceil(box.maxY); y++) {
                    pos.setPos(x, y, z);
                    IBlockState state = world.getBlockState(pos);
                    state.addCollisionBoxToList(world, pos, box, boxes, null, false);
                    if (!boxes.isEmpty()) return true;
                }
            }
        }
        return false;
    }
}
