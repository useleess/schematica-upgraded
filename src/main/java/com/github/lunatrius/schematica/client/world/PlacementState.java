package com.github.lunatrius.schematica.client.world;

import java.util.List;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.api.SchematicOrigin;

@SideOnly(Side.CLIENT)
public final class PlacementState {

    private PlacementState() {}

    /** Applies saved transform operations (X, Y, Z rotate; x, y, z mirror) to a freshly loaded placement. */
    public static void applyTransforms(SchematicWorld world, List<String> operations) {
        for (String op : operations) {
            if (op == null || op.length() != 1 || "XYZxyz".indexOf(op.charAt(0)) < 0) {
                throw new IllegalArgumentException("Invalid saved transformation");
            }
            int count = world.transformOperations.size();
            world.transform(op.charAt(0));
            if (world.transformOperations.size() != count + 1) throw new IllegalArgumentException("Source does not support this transform");
        }
    }

    /** Copies the settings and transformations of a placement onto its replacement (after a reload of its source). */
    public static void copy(SchematicWorld previous, SchematicWorld next) {
        applyTransforms(next, previous.transformOperations);
        if (previous.subregions() != null) next.restoreSubregions(previous.subregions().toJson());
        SchematicOrigin minimum = next.getSchematic().getOrigin().minimumAt(previous.originPosition());
        next.position.set(minimum.x, minimum.y, minimum.z);
        next.name = previous.name;
        next.isRendering = previous.isRendering;
        next.isRenderingEntities = previous.isRenderingEntities;
        next.isPastingBlockNBT = previous.isPastingBlockNBT;
        next.isRenderingLayer = previous.isRenderingLayer;
        next.renderingLayer = Math.max(0, Math.min(previous.renderingLayer, next.getHeight() - 1));
        next.setPlacementSettings(previous.placementSettings());
        next.grid.copyFrom(previous.grid);
        next.boxColor = previous.boxColor;
        next.materialListData = previous.materialList != null ? previous.materialList.toJson() : previous.materialListData;
        next.materialList = previous.materialList;
        if (next.materialList != null) next.materialList.rebind(next);
        next.fingerprint = previous.fingerprint;
    }
}
