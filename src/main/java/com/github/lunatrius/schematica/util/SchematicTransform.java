package com.github.lunatrius.schematica.util;

import net.minecraft.util.EnumFacing;

/** Uppercase operations rotate; lowercase operations mirror. Dimensions use continuous coordinates. */
public final class SchematicTransform {
    private SchematicTransform() {}

    /** The side a direction becomes under an operation. */
    public static EnumFacing direction(char operation, EnumFacing side) {
        double[] vector = point(operation, side.getFrontOffsetX(), side.getFrontOffsetY(), side.getFrontOffsetZ(), 0, 0, 0);
        for (EnumFacing candidate : EnumFacing.values()) {
            if (candidate.getFrontOffsetX() == vector[0] && candidate.getFrontOffsetY() == vector[1] && candidate.getFrontOffsetZ() == vector[2]) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid transformed direction");
    }

    public static double[] point(char operation, double x, double y, double z, double w, double h, double l) {
        switch (operation) {
            case 'X': return new double[] {x, z, h - y};
            case 'Y': return new double[] {l - z, y, x};
            case 'Z': return new double[] {y, w - x, z};
            case 'x': return new double[] {w - x, y, z};
            case 'y': return new double[] {x, h - y, z};
            case 'z': return new double[] {x, y, l - z};
            default: throw new IllegalArgumentException("Unknown schematic transform: " + operation);
        }
    }
}
