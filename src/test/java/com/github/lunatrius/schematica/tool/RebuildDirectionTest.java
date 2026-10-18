package com.github.lunatrius.schematica.tool;

import net.minecraft.util.EnumFacing;

import org.junit.Test;
import static org.junit.Assert.*;

public class RebuildDirectionTest {
    @Test public void centerQuarterTargetsTheHitFace() {
        for (EnumFacing side : EnumFacing.values()) {
            assertEquals(side, RebuildDirection.targeted(side, EnumFacing.NORTH, 0.5, 0.5, 0.5));
            assertEquals(side, RebuildDirection.targeted(side, EnumFacing.EAST, 0.7, 0.3, 0.7));
        }
    }

    @Test public void horizontalFacesUseTheirEdgesRelativeToTheFace() {
        assertEquals(EnumFacing.WEST, RebuildDirection.targeted(EnumFacing.NORTH, EnumFacing.SOUTH, 0.1, 0.5, 0));
        assertEquals(EnumFacing.EAST, RebuildDirection.targeted(EnumFacing.NORTH, EnumFacing.SOUTH, 0.9, 0.5, 0));
        assertEquals(EnumFacing.EAST, RebuildDirection.targeted(EnumFacing.SOUTH, EnumFacing.NORTH, 0.9, 0.5, 1));
        assertEquals(EnumFacing.NORTH, RebuildDirection.targeted(EnumFacing.EAST, EnumFacing.WEST, 1, 0.5, 0.05));
        assertEquals(EnumFacing.SOUTH, RebuildDirection.targeted(EnumFacing.WEST, EnumFacing.EAST, 0, 0.5, 0.95));
        assertEquals(EnumFacing.DOWN, RebuildDirection.targeted(EnumFacing.SOUTH, EnumFacing.NORTH, 0.5, 0.05, 1));
        assertEquals(EnumFacing.UP, RebuildDirection.targeted(EnumFacing.EAST, EnumFacing.WEST, 1, 0.95, 0.5));
    }

    @Test public void verticalFacesFollowThePlayerFacing() {
        assertEquals(EnumFacing.NORTH, RebuildDirection.targeted(EnumFacing.UP, EnumFacing.NORTH, 0.5, 1, 0.05));
        assertEquals(EnumFacing.SOUTH, RebuildDirection.targeted(EnumFacing.UP, EnumFacing.NORTH, 0.5, 1, 0.95));
        assertEquals(EnumFacing.EAST, RebuildDirection.targeted(EnumFacing.UP, EnumFacing.NORTH, 0.95, 1, 0.5));
        assertEquals(EnumFacing.WEST, RebuildDirection.targeted(EnumFacing.UP, EnumFacing.NORTH, 0.05, 1, 0.5));
        assertEquals(EnumFacing.EAST, RebuildDirection.targeted(EnumFacing.UP, EnumFacing.EAST, 0.95, 1, 0.5));
        assertEquals(EnumFacing.SOUTH, RebuildDirection.targeted(EnumFacing.DOWN, EnumFacing.NORTH, 0.5, 0, 0.95));
        assertEquals(EnumFacing.NORTH, RebuildDirection.targeted(EnumFacing.DOWN, EnumFacing.NORTH, 0.5, 0, 0.05));
    }

    @Test public void overlayFacePointsInvertTheHitPositionMapping() {
        EnumFacing[] facings = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
        for (EnumFacing side : EnumFacing.values()) for (EnumFacing facing : facings) {
            for (double h = 0; h <= 1; h += 0.125) for (double v = 0; v <= 1; v += 0.125) {
                double[] point = RebuildDirection.facePoint(side, facing, h, v, 0);
                double[] back = RebuildDirection.facePosition(side, facing, point[0], point[1], point[2]);
                assertEquals(h, back[0], 1.0E-9);
                assertEquals(v, back[1], 1.0E-9);
                double[] outside = RebuildDirection.facePoint(side, facing, h, v, 0.01);
                assertEquals(side.getFrontOffsetX() * 0.01, outside[0] - point[0], 1.0E-9);
                assertEquals(side.getFrontOffsetY() * 0.01, outside[1] - point[1], 1.0E-9);
                assertEquals(side.getFrontOffsetZ() * 0.01, outside[2] - point[2], 1.0E-9);
            }
        }
        assertEquals(RebuildDirection.Part.CENTER, RebuildDirection.part(0.6, 0.4));
        assertEquals(RebuildDirection.Part.LEFT, RebuildDirection.part(0.1, 0.4));
        assertEquals(RebuildDirection.Part.TOP, RebuildDirection.part(0.5, 0.9));
    }
}
