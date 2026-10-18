// Observer and piston aware placement order, after sakura-ryoko's litematica-printer2 (printerSmartRedstoneAvoid), 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockObserver;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.block.BlockRedstoneTorch;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockTNT;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockWallSign;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Places observers and pistons in an order that does not set them off, after litematica-printer2:
 * <ul>
 * <li>an observer waits until the block it watches is built, so that it does not fire again when that block comes
 * (in 1.12 an observer fires once when placed, which cannot be avoided; a second pulse can);</li>
 * <li>unless that block cannot exist without the observer (it stands or hangs on it) or connects to it (walls, fences,
 * panes, wire or rails climbing onto it): then the observer goes first;</li>
 * <li>redstone that reacts to power (pistons, dispensers, droppers, TNT, wire, repeaters, comparators, torches) at an
 * observer's output, directly or through the solid block the observer powers, waits until that observer is placed, its
 * pulse is over and the block it watches is built;</li>
 * <li>a piston that is retracted in the schematic is not placed while it would extend (a pulse, a power source built
 * too early); a power source waits while a retracted piston it would quasi-connect to is missing or still has
 * unbuilt neighbours, since the update that comes with them would fire it.</li>
 * </ul>
 * Waits are tracked per position; a wait that loops back to an observer through other waits is broken by placing the
 * observer when its output side is harmless, and no position waits longer than PATIENCE ticks.
 */
public final class RedstoneOrder {
    /** Ticks a position waits at most before it is placed anyway. */
    static final long PATIENCE = 400;
    /** Ticks an observer's output side is left alone after the observer is placed or the block it watches changes. */
    static final int PULSE_HOLD = 6;
    /** A wait not looked at for this many ticks (the player walked away) starts its patience again. */
    private static final long FORGET = 1200;

    /** Per position: the position it waits for. */
    private final Map<Long, Long> waits = new HashMap<>();
    /** Per position: the tick its wait started and the tick it was last seen waiting. */
    private final Map<Long, long[]> since = new HashMap<>();

    /** Whether a position was held back since the last call (the printer then leaves fluids for later). */
    private boolean held;

    public boolean takeHeld() {
        boolean was = this.held;
        this.held = false;
        return was;
    }

    public void clear() {
        this.waits.clear();
        this.since.clear();
    }

    /**
     * The position the block planned at pos waits for, or null when it may be placed now.
     * @param schematic the placement, looked up at local for pos
     */
    public BlockPos hold(World world, BlockPos pos, IBlockAccess schematic, BlockPos local, IBlockState expected, long tick) {
        Plan plan = new Plan(world, pos, schematic, local);
        BlockPos cause = waitsFor(plan, expected);
        long key = pos.toLong();
        if (cause == null) {
            forget(key);
            return null;
        }
        long[] entry = this.since.get(key);
        if (entry == null || tick - entry[1] > FORGET) this.since.put(key, entry = new long[] {tick, tick});
        entry[1] = tick;
        if (tick - entry[0] > PATIENCE) {
            forget(key);
            return null;
        }
        this.waits.put(key, cause.toLong());
        if (expected.getBlock() instanceof BlockObserver && loops(key, cause.toLong()) && safeOutput(plan, expected)) {
            forget(key);
            return null;
        }
        this.held = true;
        return cause;
    }

    private void forget(long key) {
        this.waits.remove(key);
        this.since.remove(key);
    }

    /** Whether following the waits from cause comes back to key. */
    private boolean loops(long key, long cause) {
        Long at = cause;
        for (int i = 0; i < 64 && at != null; i++) {
            if (at == key) return true;
            at = this.waits.get(at);
        }
        return false;
    }

    /** The schematic around one position, in world coordinates. */
    static final class Plan {
        final World world;
        final BlockPos pos, local;
        final IBlockAccess schematic;

        Plan(World world, BlockPos pos, IBlockAccess schematic, BlockPos local) {
            this.world = world; this.pos = pos; this.schematic = schematic; this.local = local;
        }

        IBlockState at(BlockPos at) {
            return this.schematic.getBlockState(this.local.add(at.getX() - this.pos.getX(), at.getY() - this.pos.getY(), at.getZ() - this.pos.getZ()));
        }

        /** Whether the world has what the schematic plans at the position (air and fluids never hold anything up). */
        boolean done(BlockPos at) {
            IBlockState planned = at(at);
            if (planned.getMaterial() == Material.AIR || FluidPrinter.isFluid(planned.getBlock())) return true;
            IBlockState real = this.world.getBlockState(at);
            return PrinterStates.pistonBusy(planned, real) || PrinterStates.built(this.world, at, planned, real);
        }

        /** The schematic as a block access in world coordinates, for power checks on the finished build. */
        IBlockAccess finished() {
            Plan plan = this;
            return new IBlockAccess() {
                @Override public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos at) { return null; }
                @Override public int getCombinedLight(BlockPos at, int light) { return plan.world.getCombinedLight(at, light); }
                @Override public IBlockState getBlockState(BlockPos at) { return plan.at(at); }
                @Override public boolean isAirBlock(BlockPos at) { return plan.at(at).getMaterial() == Material.AIR; }
                @Override public net.minecraft.world.biome.Biome getBiome(BlockPos at) { return plan.world.getBiome(at); }
                @Override public int getStrongPower(BlockPos at, EnumFacing side) { return plan.at(at).getStrongPower(this, at, side); }
                @Override public net.minecraft.world.WorldType getWorldType() { return plan.world.getWorldType(); }
                @Override public boolean isSideSolid(BlockPos at, EnumFacing side, boolean fallback) { return plan.at(at).isSideSolid(this, at, side); }
            };
        }
    }

    static BlockPos waitsFor(Plan plan, IBlockState expected) {
        Block block = expected.getBlock();
        if (block instanceof BlockObserver) {
            BlockPos cause = observerWaits(plan, expected);
            if (cause != null) return cause;
        }
        if (sensitive(block)) {
            BlockPos cause = outputWaits(plan, block);
            if (cause != null) return cause;
        }
        if (block instanceof BlockPistonBase && !expected.getValue(BlockPistonBase.EXTENDED)) {
            BlockPos cause = pistonWaits(plan, expected);
            if (cause != null) return cause;
        }
        if (PrinterStates.hot(plan.world, plan.pos, expected)) return quasiWaits(plan);
        return null;
    }

    /** An observer waits for the block it watches, unless that block needs the observer to be there first. */
    static BlockPos observerWaits(Plan plan, IBlockState expected) {
        EnumFacing facing = expected.getValue(BlockObserver.FACING);
        BlockPos watched = plan.pos.offset(facing);
        if (plan.done(watched) || needsObserver(plan.at(watched), facing)) return null;
        return watched;
    }

    /**
     * Whether a block watched by an observer looking towards facing cannot be built before the observer: it stands on
     * it, hangs from it, is attached to its face, or connects to it (and changes shape, firing it, when it comes).
     */
    static boolean needsObserver(IBlockState watched, EnumFacing facing) {
        Block block = watched.getBlock();
        if (attachedFacing(watched) == facing) return true;
        if (facing == EnumFacing.UP) {
            return block instanceof BlockRedstoneDiode || block instanceof BlockRailBase || block instanceof BlockRedstoneWire
                || block instanceof BlockDoor || block instanceof BlockBasePressurePlate || block instanceof BlockCarpet
                || block instanceof BlockFalling || block instanceof BlockSnow;
        }
        if (facing == EnumFacing.DOWN) return false;
        if (block instanceof BlockWall || block instanceof BlockFence || block instanceof BlockPane) return true;
        if (block instanceof BlockRedstoneWire) return "up".equals(propertyValue(watched, facing.getOpposite().getName()));
        if (block instanceof BlockRailBase) {
            String shape = String.valueOf(watched.getValue(((BlockRailBase) block).getShapeProperty()));
            return shape.equals("ascending_" + facing.getOpposite().getName());
        }
        return false;
    }

    /** The direction a block attached to a neighbour points away from it (its support is the opposite side), or null. */
    static EnumFacing attachedFacing(IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof BlockLever) return state.getValue(BlockLever.FACING).getFacing();
        if (block instanceof BlockButton || block instanceof BlockTorch || block instanceof BlockTripWireHook
            || block instanceof BlockLadder || block instanceof BlockWallSign) {
            for (IProperty<?> property : state.getPropertyKeys()) {
                if (property.getName().equals("facing") && state.getValue(property) instanceof EnumFacing) return (EnumFacing) state.getValue(property);
            }
        }
        return null;
    }

    private static String propertyValue(IBlockState state, String name) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (property.getName().equals(name)) return String.valueOf(state.getValue(property));
        }
        return null;
    }

    /** Blocks an observer pulse sets off: they move, fire, explode or pass the pulse on. */
    static boolean sensitive(Block block) {
        return block instanceof BlockPistonBase || block instanceof BlockDispenser || block instanceof BlockTNT
            || block instanceof BlockRedstoneWire || block instanceof BlockRedstoneDiode || block instanceof BlockRedstoneTorch;
    }

    /**
     * A block an observer's output reaches, directly or through the solid block in front of it, waits until the
     * observer stands, its pulse is over and the block it watches is built (no further pulse is coming).
     */
    static BlockPos outputWaits(Plan plan, Block block) {
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos observer = plan.pos.offset(side);
            BlockPos cause = unsettled(plan, observer, side);
            if (cause != null) return cause;
        }
        if (block instanceof BlockRedstoneDiode) return null; // a diode only takes its input, not the blocks around it
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos through = plan.pos.offset(side);
            if (!plan.at(through).isNormalCube()) continue;
            for (EnumFacing next : EnumFacing.values()) {
                if (next == side.getOpposite()) continue;
                BlockPos cause = unsettled(plan, through.offset(next), next);
                if (cause != null) return cause;
            }
        }
        return null;
    }

    /** For a planned observer at the position facing facing: what it still waits on before it stops pulsing, or null. */
    private static BlockPos unsettled(Plan plan, BlockPos observer, EnumFacing facing) {
        IBlockState planned = plan.at(observer);
        if (!(planned.getBlock() instanceof BlockObserver) || planned.getValue(BlockObserver.FACING) != facing) return null;
        IBlockState real = plan.world.getBlockState(observer);
        if (!(real.getBlock() instanceof BlockObserver) || real.getValue(BlockObserver.POWERED)) return observer;
        BlockPos watched = observer.offset(real.getValue(BlockObserver.FACING));
        return plan.done(watched) ? null : watched;
    }

    /**
     * A piston the schematic has retracted waits while it would extend when placed, unless the finished build powers
     * it too (a BUD piston: nothing better comes) and the power is not a passing observer pulse.
     */
    static BlockPos pistonWaits(Plan plan, IBlockState expected) {
        EnumFacing facing = expected.getValue(BlockPistonBase.FACING);
        BlockPos power = poweredBy(plan.world, plan.pos, facing);
        if (power == null) return null;
        IBlockState source = plan.world.getBlockState(power);
        boolean pulse = source.getBlock() instanceof BlockObserver && source.getValue(BlockObserver.POWERED);
        if (!pulse && poweredBy(plan.finished(), plan.pos, facing) != null) return null;
        return power;
    }

    /**
     * A block that gives off power when placed waits while a piston the schematic has retracted, which the source would
     * power only through quasi-connectivity (it reaches the block above the piston), is missing or has neighbours
     * still to come: the piston must be there first, and the last neighbour update must come before the power.
     */
    static BlockPos quasiWaits(Plan plan) {
        for (EnumFacing side : EnumFacing.values()) {
            if (side == EnumFacing.UP) continue;
            BlockPos piston = plan.pos.offset(side).down();
            IBlockState planned = plan.at(piston);
            if (!(planned.getBlock() instanceof BlockPistonBase) || planned.getValue(BlockPistonBase.EXTENDED)) continue;
            if (!plan.done(piston)) return piston;
            for (EnumFacing around : EnumFacing.values()) {
                BlockPos neighbour = piston.offset(around);
                if (!neighbour.equals(plan.pos) && !plan.done(neighbour)) return neighbour;
            }
        }
        return null;
    }

    /**
     * BlockPistonBase.shouldBeExtended: the position giving the piston at pos power, its own block above included
     * (quasi-connectivity), or null.
     */
    static BlockPos poweredBy(IBlockAccess access, BlockPos pos, EnumFacing facing) {
        for (EnumFacing side : EnumFacing.values()) {
            if (side != facing && power(access, pos.offset(side), side) > 0) return pos.offset(side);
        }
        if (power(access, pos, EnumFacing.DOWN) > 0) return pos.down();
        BlockPos up = pos.up();
        for (EnumFacing side : EnumFacing.values()) {
            if (side != EnumFacing.DOWN && power(access, up.offset(side), side) > 0) return up.offset(side);
        }
        return null;
    }

    /** World.getRedstonePower on any block access: a conductive block passes on the strong power it receives. */
    static int power(IBlockAccess access, BlockPos pos, EnumFacing side) {
        IBlockState state = access.getBlockState(pos);
        if (!state.getBlock().shouldCheckWeakPower(state, access, pos, side)) return state.getWeakPower(access, pos, side);
        int strongest = 0;
        for (EnumFacing from : EnumFacing.values()) {
            strongest = Math.max(strongest, access.getStrongPower(pos.offset(from), from));
            if (strongest >= 15) break;
        }
        return strongest;
    }

    /**
     * The positions to leave alone for PULSE_HOLD ticks after state was placed at pos: the output side of the observer
     * placed, or of the observers watching pos, which fire now.
     */
    static List<BlockPos> pulseCells(World world, BlockPos pos, IBlockState placed) {
        List<BlockPos> cells = new ArrayList<>();
        if (placed.getBlock() instanceof BlockObserver) addOutput(cells, pos, placed.getValue(BlockObserver.FACING));
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos observer = pos.offset(side);
            IBlockState state = world.getBlockState(observer);
            if (state.getBlock() instanceof BlockObserver && state.getValue(BlockObserver.FACING) == side.getOpposite()) {
                addOutput(cells, observer, side.getOpposite());
            }
        }
        return cells;
    }

    private static void addOutput(List<BlockPos> cells, BlockPos observer, EnumFacing facing) {
        BlockPos output = observer.offset(facing.getOpposite());
        cells.add(output);
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos next = output.offset(side);
            if (!next.equals(observer)) cells.add(next);
        }
    }

    /**
     * Whether an observer caught in a loop of waits may go first: its output is air or a block a pulse does not set
     * off, and if that block is solid, none of the blocks it would power is set off either.
     */
    static boolean safeOutput(Plan plan, IBlockState observer) {
        EnumFacing facing = observer.getValue(BlockObserver.FACING);
        BlockPos output = plan.pos.offset(facing.getOpposite());
        IBlockState real = plan.world.getBlockState(output);
        if (sensitive(real.getBlock())) return false;
        if (!real.isNormalCube()) return true;
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos next = output.offset(side);
            if (!next.equals(plan.pos) && sensitive(plan.world.getBlockState(next).getBlock())) return false;
        }
        return true;
    }
}
