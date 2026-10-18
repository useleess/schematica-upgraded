package com.github.lunatrius.schematica.client.printer;

import java.util.List;

import net.minecraft.util.EnumFacing;
import org.junit.Test;

import static org.junit.Assert.*;

public class PrinterIterationTest {
    @Test public void firstAxisChangesFastestAndReversalFlipsAnAxis() {
        List<int[]> xzy = PrinterIteration.build(1, "cube", "xzy", false, false, false);
        assertEquals(27, xzy.size());
        assertArrayEquals(new int[] {-1, -1, -1}, xzy.get(0));
        assertArrayEquals(new int[] {0, -1, -1}, xzy.get(1));
        assertArrayEquals(new int[] {-1, -1, 0}, xzy.get(3));
        // the whole bottom layer comes before the next one
        for (int i = 0; i < 9; i++) assertEquals(-1, xzy.get(i)[1]);
        List<int[]> topDown = PrinterIteration.build(1, "cube", "xzy", false, true, false);
        assertEquals(1, topDown.get(0)[1]);
        List<int[]> yxz = PrinterIteration.build(1, "cube", "yxz", false, false, false);
        assertArrayEquals(new int[] {-1, 0, -1}, yxz.get(1));
    }

    @Test public void shapesLimitTheArea() {
        assertEquals(7, PrinterIteration.build(1, "sphere", "xzy", false, false, false).size());
        assertEquals(7, PrinterIteration.build(1, "octahedron", "xzy", false, false, false).size());
        assertEquals(125, PrinterIteration.build(2, "cube", "xzy", false, false, false).size());
        assertEquals(33, PrinterIteration.build(2, "sphere", "xzy", false, false, false).size());
        assertEquals(25, PrinterIteration.build(2, "octahedron", "xzy", false, false, false).size());
        for (int[] o : PrinterIteration.build(5, "sphere", "zyx", true, true, true)) assertTrue(o[0] * o[0] + o[1] * o[1] + o[2] * o[2] <= 25);
    }

    @Test public void unknownValuesFallBackToTheDefaults() {
        assertEquals(PrinterIteration.build(2, "sphere", "xzy", false, false, false).size(),
            PrinterIteration.build(2, "blob", "abc", false, false, false).size());
        assertSame(PrinterIteration.offsets(3, "cube", "xyz", false, false, false), PrinterIteration.offsets(3, "cube", "xyz", false, false, false));
    }

    @Test public void lookCandidatesCoverEveryOrientationStartingWithTheCurrentYaw() {
        PrinterLook[] looks = PrinterLook.candidates(181);
        assertEquals(180f, looks[0].yaw, 0);
        java.util.Set<EnumFacing> directions = java.util.EnumSet.noneOf(EnumFacing.class);
        for (PrinterLook look : looks) directions.add(orientation(look.yaw, look.pitch));
        assertEquals(6, directions.size());
        assertEquals(EnumFacing.SOUTH, orientation(0, 0));
        assertEquals(EnumFacing.WEST, orientation(90, 0));
        assertEquals(EnumFacing.NORTH, orientation(-180, 0));
        assertEquals(EnumFacing.DOWN, orientation(0, 90));
    }

    /** The direction a rotation looks: straight up or down past 45 degrees, else the horizontal one. */
    private static EnumFacing orientation(float yaw, float pitch) {
        if (pitch > 45) return EnumFacing.DOWN;
        if (pitch < -45) return EnumFacing.UP;
        return EnumFacing.fromAngle(yaw);
    }
}
