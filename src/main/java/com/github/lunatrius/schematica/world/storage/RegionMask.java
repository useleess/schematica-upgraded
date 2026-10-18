package com.github.lunatrius.schematica.world.storage;

import com.github.lunatrius.schematica.api.SchematicRegion;

import java.util.BitSet;
import java.util.List;
import com.github.lunatrius.schematica.util.SchematicLimits;

public final class RegionMask {
    private RegionMask() {}

    public static BitSet create(List<SchematicRegion> regions, int width, int height, int length) {
        int volume = SchematicLimits.boxVolume(width, height, length);
        if (regions.size() > 256) throw new IllegalArgumentException("Too many subregions");
        BitSet mask = new BitSet(volume);
        if (regions.isEmpty()) mask.set(0, volume);
        for (SchematicRegion region : regions) {
            if (region.minX < 0 || region.minY < 0 || region.minZ < 0 || region.maxX >= width
                || region.maxY >= height || region.maxZ >= length) throw new IllegalArgumentException("Subregion outside schematic");
            for (int y = region.minY; y <= region.maxY; y++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {
                    int row = width * (z + length * y);
                    mask.set(row + region.minX, row + region.maxX + 1);
                }
            }
        }
        return mask;
    }

    /** Like create, but stored sparsely (memory grows with the regions, not the bounding box); null when there are no regions. */
    public static SparseBits sparse(List<SchematicRegion> regions, int width, int height, int length) {
        SchematicLimits.boxVolume(width, height, length);
        if (regions.size() > 256) throw new IllegalArgumentException("Too many subregions");
        if (regions.isEmpty()) return null;
        SparseBits mask = new SparseBits();
        for (SchematicRegion region : regions) {
            if (region.minX < 0 || region.minY < 0 || region.minZ < 0 || region.maxX >= width
                || region.maxY >= height || region.maxZ >= length) throw new IllegalArgumentException("Subregion outside schematic");
            for (int y = region.minY; y <= region.maxY; y++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {
                    int row = width * (z + length * y);
                    mask.set(row + region.minX, row + region.maxX + 1);
                }
            }
        }
        return mask;
    }
}
