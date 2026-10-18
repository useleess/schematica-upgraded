package com.github.lunatrius.schematica.util;

/** Allocation and coordinate limits shared by disk, network and editing entry points. */
public final class SchematicLimits {
    public static final long MAX_BLOCKS = 16L * 1024 * 1024;
    public static final long MAX_NBT_BYTES = 128L * 1024 * 1024;
    public static final int MAX_NBT_DEPTH = 64;
    /** Largest bounding box of a schematic; its blocks are stored sparsely, so only the filled parts take memory. */
    public static final long MAX_VOLUME = Integer.MAX_VALUE;

    private SchematicLimits() {}

    /** How much a schematic file may take while it is read: half the game's memory, at least MAX_NBT_BYTES. */
    public static long readBudget() {
        return Math.max(MAX_NBT_BYTES, Runtime.getRuntime().maxMemory() / 2);
    }

    /** An error to show when a schematic did not fit in the game's memory. */
    public static java.io.IOException outOfMemory(Throwable cause) {
        java.io.IOException error = new java.io.IOException("Not enough memory for this schematic (Minecraft has "
            + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB); give the game more RAM");
        error.initCause(cause);
        return error;
    }

    public static int volume(long width, long height, long length) {
        if (width <= 0 || height <= 0 || length <= 0
            || width > Short.MAX_VALUE || height > Short.MAX_VALUE || length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Schematic dimensions must be between 1 and 32767");
        }
        long volume = width * height * length;
        if (volume > MAX_BLOCKS || width * height > 1024L * 1024) {
            throw new IllegalArgumentException("Schematic exceeds the allocation limit (16777216 blocks)");
        }
        return (int) volume;
    }

    /** The volume of a schematic's bounding box (sparse storage), up to MAX_VOLUME. */
    public static int boxVolume(long width, long height, long length) {
        if (width <= 0 || height <= 0 || length <= 0
            || width > Short.MAX_VALUE || height > Short.MAX_VALUE || length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Schematic dimensions must be between 1 and 32767");
        }
        long volume = width * height * length;
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Schematic bounding box exceeds the limit (" + MAX_VOLUME + " blocks), size " + width + "x" + height + "x" + length);
        }
        return (int) volume;
    }

    public static int dimension(int min, int max) {
        long size = (long) max - min + 1;
        if (size <= 0 || size > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid schematic extent");
        }
        return (int) size;
    }

    public static void worldBounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        insideWorld(minX, minY, minZ, maxX, maxY, maxZ);
        volume(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    /** Like worldBounds, but for sparsely stored boxes (up to MAX_VOLUME). */
    public static void worldBox(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        insideWorld(minX, minY, minZ, maxX, maxY, maxZ);
        boxVolume(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    private static void insideWorld(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        if (minX < -30000000 || minZ < -30000000 || maxX >= 30000000 || maxZ >= 30000000
            || minY < 0 || maxY >= 256 || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Selection is outside world bounds (Y must be 0..255)");
        }
    }
}
