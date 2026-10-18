// SPDX-License-Identifier: LGPL-3.0-only
// Litematica schematic world ray traces, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.tool;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.util.VoxelRayTrace;

/** Traces against the rendered blocks of every enabled placement, the counterpart of Litematica's schematic world. */
public final class SchematicTargets {
    private SchematicTargets() {}

    /** A schematic block hit, in world coordinates. */
    public static final class Hit {
        public final SchematicWorld world;
        public final int x, y, z;
        /** The face hit (EnumFacing index). */
        public final int side;
        public final double hitX, hitY, hitZ, distance;

        Hit(SchematicWorld world, RayTraceResult hit, double distance) {
            this.world = world;
            BlockPos pos = hit.getBlockPos();
            x = pos.getX() + world.position.x; y = pos.getY() + world.position.y; z = pos.getZ() + world.position.z;
            side = hit.sideHit.getIndex();
            hitX = hit.hitVec.x + world.position.x; hitY = hit.hitVec.y + world.position.y; hitZ = hit.hitVec.z + world.position.z;
            this.distance = distance;
        }

        Hit(SchematicWorld world, int x, int y, int z, EnumFacing side) {
            this.world = world;
            this.x = x; this.y = y; this.z = z; this.side = side.getIndex();
            hitX = x + 0.5; hitY = y + 0.5; hitZ = z + 0.5;
            distance = 0;
        }

        public EnumFacing face() { return EnumFacing.getFront(side); }
        int[] adjacent() { EnumFacing d = face(); return new int[] {x + d.getFrontOffsetX(), y + d.getFrontOffsetY(), z + d.getFrontOffsetZ()}; }
        public int localX() { return x - world.position.x; }
        public int localY() { return y - world.position.y; }
        public int localZ() { return z - world.position.z; }
        public BlockPos localPos() { return new BlockPos(localX(), localY(), localZ()); }
    }

    private static Entity camera() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.getRenderViewEntity() == null ? mc.player : mc.getRenderViewEntity();
    }

    /**
     * The distance to the entity the crosshair targets (vanilla's objectMouseOver, which includes entities), or -1.
     * A schematic block behind a targeted mob is not picked, so middle click gives the mob's spawn egg.
     */
    private static double targetedEntityDistance(Vec3d eye) {
        RayTraceResult target = Minecraft.getMinecraft().objectMouseOver;
        if (target == null || target.typeOfHit != RayTraceResult.Type.ENTITY || target.entityHit == null) return -1;
        return target.hitVec != null ? eye.distanceTo(target.hitVec)
            : eye.distanceTo(new Vec3d(target.entityHit.posX, target.entityHit.posY, target.entityHit.posZ));
    }

    private static Vec3d along(Vec3d eye, Vec3d look, double range) {
        return eye.addVector(look.x * range, look.y * range, look.z * range);
    }

    /** The nearest schematic block when it is not farther than the nearest real block (getGenericTrace). */
    public static Hit closest(double range, boolean fluids) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = camera();
        if (camera == null || mc.world == null || !VisualSettings.schematicVisible()) return null;
        Vec3d eye = camera.getPositionEyes(1), look = camera.getLook(1);
        double limit = range;
        RayTraceResult real = mc.world.rayTraceBlocks(eye, along(eye, look, range), fluids);
        if (real != null && real.typeOfHit == RayTraceResult.Type.BLOCK) limit = eye.distanceTo(real.hitVec);
        double entity = targetedEntityDistance(eye);
        if (entity >= 0) limit = Math.min(limit, entity);
        Hit best = null;
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isRenderingEnabled()) continue;
            Vec3d start = eye.addVector(-world.position.x, -world.position.y, -world.position.z);
            RayTraceResult hit = world.rayTraceRendered(start, along(start, look, range), fluids);
            if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) continue;
            double distance = start.distanceTo(hit.hitVec);
            if (distance <= limit + 1.0E-7 && (best == null || distance < best.distance)) best = new Hit(world, hit, distance);
        }
        return best;
    }

    /**
     * The farthest schematic block in front of the targeted real block, or the schematic block in the empty
     * space against the targeted face (getFurthestSchematicWorldBlockBeforeVanilla with a required real block).
     */
    public static Hit furthestBeforeVanilla(double range) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = camera();
        if (camera == null || mc.world == null || !VisualSettings.schematicVisible()) return null;
        Vec3d eye = camera.getPositionEyes(1), look = camera.getLook(1);
        RayTraceResult real = mc.world.rayTraceBlocks(eye, along(eye, look, range), false);
        if (real == null || real.typeOfHit != RayTraceResult.Type.BLOCK) return null;
        BlockPos realPos = real.getBlockPos();
        double vanilla = eye.distanceTo(real.hitVec);
        double entity = targetedEntityDistance(eye);
        if (entity >= 0 && entity < vanilla) return null;
        Hit[] best = {null};
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isRenderingEnabled()) continue;
            Vec3d start = eye.addVector(-world.position.x, -world.position.y, -world.position.z);
            Vec3d end = along(start, look, range);
            VoxelRayTrace.trace(start, end, (x, y, z) -> {
                if (!world.isBlockRendered(x, y, z)) return null;
                BlockPos pos = new BlockPos(x, y, z);
                IBlockState state = world.getBlockState(pos);
                if (state.getBlock().isAir(state, world, pos) || !state.getBlock().canCollideCheck(state, false)) return null;
                RayTraceResult hit = state.collisionRayTrace(world, pos, start, end);
                if (hit == null || hit.hitVec == null) return null;
                double distance = start.distanceTo(hit.hitVec);
                int wx = x + world.position.x, wy = y + world.position.y, wz = z + world.position.z;
                if (distance < vanilla && (best[0] == null || distance > best[0].distance)
                    && (wx != realPos.getX() || wy != realPos.getY() || wz != realPos.getZ())) best[0] = new Hit(world, hit, distance);
                return distance > vanilla ? Boolean.TRUE : null;
            });
        }
        if (best[0] != null) return best[0];
        BlockPos next = realPos.offset(real.sideHit);
        int x = next.getX(), y = next.getY(), z = next.getZ();
        if (!RenderLayerSettings.RANGE.contains(x, y, z) || !mc.world.isAirBlock(next)) return null;
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            BlockPos local = new BlockPos(x - world.position.x, y - world.position.y, z - world.position.z);
            if (world.isRenderingEnabled() && world.isBlockRendered(local.getX(), local.getY(), local.getZ()) && !world.isAirBlock(local)) {
                return new Hit(world, x, y, z, real.sideHit);
            }
        }
        return null;
    }

    /** The real world block trace from the camera (RayTraceUtils.getRayTraceFromEntity). */
    public static RayTraceResult vanilla(double range, boolean fluids) {
        Entity camera = camera();
        if (camera == null || Minecraft.getMinecraft().world == null) return null;
        Vec3d eye = camera.getPositionEyes(1), look = camera.getLook(1);
        return Minecraft.getMinecraft().world.rayTraceBlocks(eye, along(eye, look, range), fluids);
    }

    /** Litematica's WorldUtils.getValidBlockRange: reach + 1 unless easyPlaceVanillaReach is set. */
    public static double validBlockRange() {
        return Minecraft.getMinecraft().playerController.getBlockReachDistance() + (ConfigurationHandler.easyPlaceVanillaReach ? 0 : 1);
    }

    public static EnumFacing facing() {
        return EnumFacing.getHorizontal(MathHelper.floor(Minecraft.getMinecraft().player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3);
    }
}
