package com.github.lunatrius.schematica.client.world;

import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.storage.Schematic;
import com.github.lunatrius.schematica.world.storage.SchematicCopies;

/** A block state and optional tile entity template positioned at 0,0,0. */
@SideOnly(Side.CLIENT)
public final class CellState {
    public static final CellState AIR = new CellState(Blocks.AIR.getDefaultState(), null);
    private static final BlockPos ORIGIN = BlockPos.ORIGIN;

    public final IBlockState state;
    public final TileEntity tile;

    public CellState(IBlockState state, TileEntity tile) {
        this.state = state == null ? Blocks.AIR.getDefaultState() : state;
        this.tile = tile;
    }

    /** The cell a block would make when placed: with the tile entity it creates, if it has one. */
    public static CellState of(IBlockState state) {
        if (state == null || state.getBlock() == Blocks.AIR) return AIR;
        boolean tile;
        try { tile = state.getBlock().hasTileEntity(state); }
        catch (RuntimeException | LinkageError error) { tile = false; }
        if (!tile) return new CellState(state, null);
        Scratch world = Scratch.create();
        world.setBlockState(ORIGIN, state, 3);
        return world.state();
    }

    public static CellState read(ISchematic schematic, int x, int y, int z) {
        TileEntity tile = schematic.getTileEntity(new BlockPos(x, y, z));
        return new CellState(schematic.getBlockState(x, y, z), tile == null ? null : SchematicCopies.tile(tile, x, y, z));
    }

    public Block block() { return state.getBlock(); }

    public boolean isAir() { return state.getBlock() == Blocks.AIR; }

    public boolean same(CellState other) { return other != null && state == other.state; }

    public boolean matches(ISchematic schematic, int x, int y, int z) {
        return schematic.getBlockState(x, y, z) == state;
    }

    public CellState transform(List<String> operations, Map<String, CellState> cache) {
        if (operations.isEmpty() || isAir()) return this;
        String key = tile == null ? Block.getStateId(state) + ":" + String.join("", operations) : null;
        CellState cached = key == null ? null : cache.get(key);
        if (cached != null) return cached;
        Schematic cell = new Schematic(SchematicWorld.DEFAULT_ICON, 1, 1, 1);
        cell.setBlockState(0, 0, 0, state);
        if (tile != null) cell.setTileEntity(ORIGIN, SchematicCopies.tile(tile, 0, 0, 0));
        SchematicWorld world = new SchematicWorld(cell);
        PlacementState.applyTransforms(world, operations);
        TileEntity transformed = world.getTileEntity(ORIGIN);
        CellState result = new CellState(world.getBlockState(ORIGIN), transformed == null ? null : SchematicCopies.tile(transformed, 0, 0, 0));
        if (key != null) cache.put(key, result);
        return result;
    }

    /** Writes this state into a schematic, keeping an existing tile entity only when the block itself is unchanged. */
    public void write(ISchematic target, int x, int y, int z) {
        IBlockState previous = target.getBlockState(x, y, z);
        if (!target.setBlockState(x, y, z, state)) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (tile != null) {
            target.setTileEntity(pos, SchematicCopies.tile(tile, -x, -y, -z));
        } else if (previous.getBlock() != state.getBlock() || isAir()) {
            target.removeTileEntity(pos);
        }
    }

    /** A detached one-block world in which placement code can run without touching a real world. */
    public static final class Scratch extends SchematicWorld {
        private World real;
        private int ox, oy, oz;

        private Scratch(Schematic schematic) { super(schematic); }

        public static Scratch create() { return new Scratch(new Schematic(DEFAULT_ICON, 1, 1, 1)); }

        /** A scratch cell at a world position whose neighbors read the blocks of that world (without tile entities). */
        public static Scratch around(World real, int x, int y, int z) {
            Scratch scratch = create();
            scratch.real = real;
            scratch.ox = x; scratch.oy = y; scratch.oz = z;
            return scratch;
        }

        private boolean outside(BlockPos pos) { return real != null && !pos.equals(ORIGIN); }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return outside(pos) ? real.getBlockState(pos.add(ox, oy, oz)) : super.getBlockState(pos);
        }

        @Override
        public boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
            boolean changed = super.setBlockState(pos, state, flags);
            if (changed && getTileEntity(pos) == null) {
                try {
                    if (state.getBlock().hasTileEntity(state)) {
                        TileEntity tile = state.getBlock().createTileEntity(this, state);
                        if (tile != null) setTileEntity(pos, tile);
                    }
                } catch (RuntimeException | LinkageError error) {
                    Reference.logger.warn("Could not create a preview tile entity for {}", state, error);
                }
            }
            return changed;
        }

        public CellState state() {
            TileEntity tile = getTileEntity(ORIGIN);
            TileEntity copy = null;
            if (tile != null) {
                try { copy = SchematicCopies.tile(tile, 0, 0, 0); }
                catch (IllegalArgumentException error) { Reference.logger.warn("Could not capture a preview tile entity", error); }
            }
            return new CellState(getBlockState(ORIGIN), copy);
        }
    }
}
