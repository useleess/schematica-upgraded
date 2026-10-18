package com.github.lunatrius.schematica.tool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Writes blocks straight into chunk storage, skipping placement callbacks and neighbour updates (pasteWithoutUpdates). */
final class SilentBlockPlacement {
    private static final Method RELIGHT = relight();
    private static final Field PRECIPITATION = precipitation();
    private final Map<Chunk, BitSet> changed = new LinkedHashMap<>();

    /** Chunk.relightBlock by its development or its obfuscated (SRG) name. */
    private static Method relight() {
        for (String name : new String[] {"relightBlock", "func_76615_h"}) {
            try {
                Method method = Chunk.class.getDeclaredMethod(name, int.class, int.class, int.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new IllegalStateException("Chunk.relightBlock not found");
    }

    /** Chunk.precipitationHeightMap by its development or its obfuscated (SRG) name. */
    static Field precipitation() {
        for (String name : new String[] {"precipitationHeightMap", "field_76638_b"}) {
            try {
                Field field = Chunk.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new IllegalStateException("Chunk.precipitationHeightMap not found");
    }

    void setBlock(WorldServer world, BlockPos pos, IBlockState state) {
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        TileEntity previous = chunk.getTileEntityMap().remove(pos);
        if (previous != null) {
            world.loadedTileEntityList.remove(previous);
            world.tickableTileEntities.remove(previous);
            boolean restoring = world.restoringBlockSnapshots;
            try {
                world.restoringBlockSnapshots = true;
                previous.invalidate();
            } finally {
                world.restoringBlockSnapshots = restoring;
            }
        }
        writeStorage(chunk, pos.getX() & 15, pos.getY(), pos.getZ() & 15, state, world.provider.hasSkyLight());
        changed.computeIfAbsent(chunk, ignored -> new BitSet()).set((pos.getY() << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
    }

    static void writeStorage(Chunk chunk, int x, int y, int z, IBlockState state, boolean skylight) {
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        if (storage[y >> 4] == Chunk.NULL_BLOCK_STORAGE) {
            ExtendedBlockStorage section = new ExtendedBlockStorage(y & ~15, skylight);
            if (skylight) {
                for (int sx = 0; sx < 16; sx++) {
                    for (int sz = 0; sz < 16; sz++) {
                        for (int sy = 0; sy < 16; sy++) {
                            section.setSkyLight(sx, sy, sz, (y & ~15) + sy >= chunk.getHeightValue(sx, sz) ? 15 : 0);
                        }
                    }
                }
            }
            storage[y >> 4] = section;
        }
        storage[y >> 4].set(x, y & 15, z, state);
        try {
            ((int[]) PRECIPITATION.get(chunk))[(z << 4) | x] = -999;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not reset the precipitation height", e);
        }
        chunk.markDirty();
    }

    void setTile(WorldServer world, TileEntity tile) {
        world.setTileEntity(tile.getPos(), tile);
    }

    void flush(WorldServer world) {
        for (Map.Entry<Chunk, BitSet> entry : changed.entrySet()) {
            Chunk chunk = entry.getKey();
            BitSet cells = entry.getValue();
            int[] tops = new int[256];
            for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
                tops[cell & 255] = (cell >> 8) + 1;
            }
            for (int column = 0; column < tops.length; column++) {
                if (tops[column] == 0) continue;
                try { RELIGHT.invoke(chunk, column & 15, tops[column], column >> 4); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not update pasted block lighting", e); }
            }
            for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
                BlockPos pos = new BlockPos((chunk.x << 4) + (cell & 15), cell >> 8, (chunk.z << 4) + ((cell >> 4) & 15));
                world.checkLight(pos);
                IBlockState state = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, state, state, 3);
            }
        }
        changed.clear();
    }
}
