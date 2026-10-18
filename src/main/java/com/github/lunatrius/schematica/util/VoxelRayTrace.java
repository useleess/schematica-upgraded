package com.github.lunatrius.schematica.util;

import net.minecraft.util.math.Vec3d;

public final class VoxelRayTrace {
    public interface Cell<T> { T hit(int x, int y, int z); }

    private VoxelRayTrace() {}

    public static <T> T trace(Vec3d start, Vec3d end, Cell<T> cell) {
        double[] from = {start.x, start.y, start.z};
        double[] to = {end.x, end.y, end.z};
        int[] position = new int[3], step = new int[3];
        double[] next = new double[3], interval = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(from[axis]) || !Double.isFinite(to[axis])
                || Math.abs(from[axis]) > Integer.MAX_VALUE - 1 || Math.abs(to[axis]) > Integer.MAX_VALUE - 1) return null;
            position[axis] = (int) Math.floor(from[axis]);
            double delta = to[axis] - from[axis];
            step[axis] = delta > 0 ? 1 : delta < 0 ? -1 : 0;
            interval[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / delta);
            next[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY
                : (position[axis] + (step[axis] > 0 ? 1 : 0) - from[axis]) / delta;
        }
        for (int i = 0; i < 256; i++) {
            T hit = cell.hit(position[0], position[1], position[2]);
            if (hit != null) return hit;
            int axis = next[0] < next[1] && next[0] < next[2] ? 0 : next[1] < next[2] ? 1 : 2;
            if (next[axis] > 1) return null;
            position[axis] += step[axis];
            next[axis] += interval[axis];
        }
        return null;
    }
}
