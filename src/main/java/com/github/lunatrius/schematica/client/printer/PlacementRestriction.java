// SPDX-License-Identifier: LGPL-3.0-only
// Litematica Placement Restriction (WorldUtils.placementRestrictionInEffect), adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.util.BlockToItemStack;
import com.github.lunatrius.schematica.client.world.CellState;
import com.github.lunatrius.schematica.client.world.RenderLayerSettings;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.client.world.SubRegionPlacements;
import com.github.lunatrius.schematica.proxy.ClientProxy;

public final class PlacementRestriction {
    private PlacementRestriction() {}

    /** WorldUtils.handlePlacementRestriction: returns true, with the configured warning, when the use action must be cancelled. */
    public static boolean handle(Minecraft mc) {
        boolean cancel = inEffect(mc);
        if (cancel) EasyPlace.warn("litematica.message.placement_restriction_fail");
        return cancel;
    }

    /**
     * Whether using the held item on the targeted block would place something the schematic does not want there:
     * outside the layer range, where the schematic has air near its regions, into an occupied position, with the wrong
     * item, or with an orientation that differs from the schematic's.
     */
    static boolean inEffect(Minecraft mc) {
        ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getHeldItemMainhand();
        RayTraceResult trace = mc.objectMouseOver;
        World world = mc.world;
        if (held.isEmpty() || world == null || trace == null || trace.typeOfHit != RayTraceResult.Type.BLOCK) return false;
        BlockPos clicked = trace.getBlockPos(), pos = clicked;
        IBlockState clickedState = world.getBlockState(clicked);
        if (clickedState.getBlock() != Blocks.SNOW_LAYER && !clickedState.getBlock().isReplaceable(world, clicked)) pos = clicked.offset(trace.sideHit);
        SchematicWorld schematic = schematicAt(pos);
        if (schematic != null && !RenderLayerSettings.RANGE.contains(pos.getX(), pos.getY(), pos.getZ())) return true;
        if (schematic == null) return nearRegions(pos, 2);
        if (!world.getBlockState(pos).getBlock().isReplaceable(world, pos)) return true;
        BlockPos local = new BlockPos(pos.getX() - schematic.position.x, pos.getY() - schematic.position.y, pos.getZ() - schematic.position.z);
        IBlockState state = schematic.getSchematic().getBlockState(local);
        ItemStack required = BlockToItemStack.getItemStack(mc.player, state, schematic, local);
        if (!required.isEmpty() && !held.isItemEqual(required)) return true;
        float hx = (float) (trace.hitVec.x - clicked.getX()), hy = (float) (trace.hitVec.y - clicked.getY()), hz = (float) (trace.hitVec.z - clicked.getZ());
        IBlockState placed;
        try {
            placed = PlacementSolver.place(CellState.Scratch.around(world, pos.getX(), pos.getY(), pos.getZ()), mc.player, held, pos,
                clicked.subtract(pos), trace.sideHit, hx, hy, hz);
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
        return placed != null && placed.getBlock() == state.getBlock() && !StateClicks.placedMatches(placed, state);
    }

    /** The enabled placement whose schematic has a non-air block at the world position, ignoring the layer range. */
    private static SchematicWorld schematicAt(BlockPos pos) {
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isEnabled()) continue;
            BlockPos local = new BlockPos(pos.getX() - world.position.x, pos.getY() - world.position.y, pos.getZ() - world.position.z);
            if (!world.getSchematic().containsBlock(local)) continue;
            if (world.getSchematic().getBlockState(local).getBlock() != Blocks.AIR) return world;
        }
        return null;
    }

    /** WorldUtils.isPositionWithinRangeOfSchematicRegions. */
    static boolean nearRegions(BlockPos pos, int range) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isEnabled() || world.subregions() == null) continue;
            for (SubRegionPlacements.Region region : world.subregions().regions()) {
                if (!region.enabled) continue;
                SchematicRegion box = world.subregionBounds(region.name());
                if (x >= box.minX - range && x <= box.maxX + range && y >= box.minY - range && y <= box.maxY + range
                    && z >= box.minZ - range && z <= box.maxZ + range) return true;
            }
        }
        return false;
    }
}
