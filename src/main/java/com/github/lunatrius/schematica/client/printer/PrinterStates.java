// What the printer counts as built: the schematic block, whatever redstone has since done to it, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDragonEgg;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockObserver;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.BlockPistonMoving;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockTripWire;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.BlockTNT;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * Redstone changes blocks after they are placed: rails, plates, buttons and observers get powered, wire carries a power
 * level, pistons extend (and leave heads and moving blocks behind), torches, lamps, repeaters and comparators switch to
 * their lit twins, redstone opens doors, trapdoors and gates. None of that is a wrong block the printer should fix:
 * fixing it only starts a loop of breaking and placing while the circuit keeps changing it back.
 */
public final class PrinterStates {
    private PrinterStates() {}

    /** The properties redstone (or the block's own updates) sets after placement, on top of the survival-only ones. */
    static Set<String> runtimeProperties(Block block) {
        Set<String> names = new HashSet<>(BlockGroups.survivalProperties(block));
        if (block instanceof BlockButton || block instanceof BlockBasePressurePlate || block instanceof BlockRailBase
            || block instanceof BlockRedstoneComparator || block instanceof BlockObserver || block instanceof BlockDoor
            || block instanceof BlockFenceGate || block instanceof BlockTripWire || block instanceof BlockTripWireHook) {
            names.add("powered");
        }
        if (block instanceof BlockRedstoneWire || block instanceof BlockDaylightDetector || block instanceof BlockBasePressurePlate) names.add("power");
        if (block instanceof BlockRailBase) names.add("shape");
        if (block instanceof BlockPistonBase) names.add("extended");
        if (block instanceof BlockDispenser) names.add("triggered");
        if (block instanceof BlockHopper) names.add("enabled");
        if (block instanceof BlockTripWire || block instanceof BlockTripWireHook) names.addAll(Arrays.asList("attached", "disarmed"));
        return names;
    }

    /** A piston head or a block in motion: made by a piston, never placed or broken by the printer. */
    static boolean pistonPart(Block block) {
        return block instanceof BlockPistonExtension || block instanceof BlockPistonMoving;
    }

    /**
     * Whether the world block counts as the schematic block for the printer: the same block (or its lit twin) with the
     * same state apart from what redstone sets; a door, trapdoor or gate redstone holds counts whether open or not.
     */
    static boolean built(World world, BlockPos pos, IBlockState expected, IBlockState real) {
        if (BlockGroups.sameStored(expected, real) || BlockGroups.tolerated(expected, real)) return true;
        Block a = expected.getBlock(), b = real.getBlock();
        if (a != b && !BlockGroups.switchedPair(a, b)) return false;
        Set<String> ignored = runtimeProperties(a);
        if (poweredOpenable(world, pos, real)) ignored.add("open");
        return BlockGroups.sameProperties(expected, real, ignored);
    }

    /** Whether the printer should leave the position alone: piston heads, moving blocks, either way round. */
    static boolean pistonBusy(IBlockState expected, IBlockState real) {
        return pistonPart(expected.getBlock()) || pistonPart(real.getBlock());
    }

    /**
     * Cobblestone, stone or obsidian touching water or lava: what a cobblestone (or stone) generator makes. Mining it
     * only makes the fluids make it again.
     */
    static boolean fluidMade(World world, BlockPos pos, IBlockState real) {
        Block block = real.getBlock();
        if (block != Blocks.COBBLESTONE && block != Blocks.STONE && block != Blocks.OBSIDIAN) return false;
        for (EnumFacing side : EnumFacing.values()) if (world.getBlockState(pos.offset(side)).getMaterial().isLiquid()) return true;
        return false;
    }

    /**
     * Whether a fluid source may be placed: lava touching water turns into obsidian (and water touching lava turns the
     * lava into obsidian), which a working build never has; the other fluid is there by accident, so the source waits.
     */
    static boolean fluidSafe(World world, BlockPos pos, Material fluid) {
        Material other = fluid == Material.LAVA ? Material.WATER : Material.LAVA;
        for (EnumFacing side : EnumFacing.values()) {
            if (side == EnumFacing.DOWN) continue; // BlockLiquid.checkForMixing ignores the block below
            if (world.getBlockState(pos.offset(side)).getMaterial() == other) return false;
        }
        return true;
    }

    /**
     * Sand, gravel, concrete powder, anvils and the dragon egg fall when air, fire or a fluid is below them: such a block
     * waits until its support is built.
     */
    static boolean fallsNow(World world, BlockPos pos, IBlockState expected) {
        Block block = expected.getBlock();
        return (block instanceof BlockFalling || block instanceof BlockDragonEgg) && pos.getY() > 0
            && BlockFalling.canFallThrough(world.getBlockState(pos.down()));
    }

    /** TNT next to a redstone signal is primed the moment it is placed. */
    static boolean primes(World world, BlockPos pos, IBlockState expected) {
        return expected.getBlock() instanceof BlockTNT && world.isBlockPowered(pos);
    }

    /**
     * Redstone wire that would carry power as soon as it is placed, and would point into other blocks than the finished
     * wire (a dot powers all four sides until the wire, repeater or block it connects to is built), waits: it could
     * prime TNT, fire pistons or power blocks the finished circuit never powers.
     */
    static boolean wireWaits(World world, BlockPos pos, IBlockAccess schematic, BlockPos local, IBlockState expected) {
        if (!(expected.getBlock() instanceof BlockRedstoneWire) || !wirePowered(world, pos)) return false;
        IBlockState placed = Blocks.REDSTONE_WIRE.getDefaultState().getActualState(world, pos);
        IBlockState finished = expected.getActualState(schematic, local);
        return !pointed(finished).containsAll(pointed(placed));
    }

    /**
     * A block that gives off power (a redstone block, torch, repeater, observer, lever...) waits while redstone wire it
     * would power, directly or along the wire, is not in its finished shape yet: power reaching a half-built wire (a
     * dot) spreads to blocks the finished circuit never powers.
     */
    static boolean sourceWaits(World world, BlockPos pos, IBlockAccess schematic, BlockPos local, IBlockState expected) {
        return unfinishedWire(world, pos, schematic, local, expected) != null;
    }

    /** The unfinished wire a power source placed at pos would power, or null. */
    static BlockPos unfinishedWire(World world, BlockPos pos, IBlockAccess schematic, BlockPos local, IBlockState expected) {
        if (!hot(world, pos, expected)) return null;
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos at : BlockPos.getAllInBox(pos.add(-2, -2, -2), pos.add(2, 2, 2))) {
            if (world.getBlockState(at).getBlock() instanceof BlockRedstoneWire && seen.add(at.toImmutable())) queue.add(at.toImmutable());
        }
        IBlockAccess after = with(world, pos, expected, schematic, local);
        while (!queue.isEmpty() && seen.size() < 256) {
            BlockPos wire = queue.poll();
            IBlockState finished = schematic.getBlockState(local.add(wire.subtract(pos)));
            if (finished.getBlock() instanceof BlockRedstoneWire && !pointed(finished.getActualState(schematic, local.add(wire.subtract(pos))))
                .containsAll(pointed(world.getBlockState(wire).getActualState(after, wire)))) {
                return wire;
            }
            for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
                BlockPos next = wire.offset(side);
                for (BlockPos at : new BlockPos[] {next, next.up(), next.down()}) {
                    if (world.getBlockState(at).getBlock() instanceof BlockRedstoneWire && seen.add(at)) queue.add(at);
                }
            }
        }
        return null;
    }

    /**
     * Whether a block gives off power the moment it is placed: redstone blocks, torches, daylight sensors, repeaters and
     * comparators with a powered input. Blocks that are off when placed (rails, observers, levers, buttons, plates) are
     * placed first; they connect wire without powering it.
     */
    static boolean hot(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_BLOCK || block instanceof net.minecraft.block.BlockRedstoneTorch || block instanceof BlockDaylightDetector) return true;
        if (block instanceof net.minecraft.block.BlockRedstoneDiode) {
            EnumFacing input = state.getValue(net.minecraft.block.BlockHorizontal.FACING);
            return world.getRedstonePower(pos.offset(input), input) > 0;
        }
        return false;
    }

    /**
     * The world once state is placed at pos (for the shape wire would have then), with the other power sources of the
     * schematic that give off power when placed counted as placed too, so that two of them never wait for each other.
     */
    private static IBlockAccess with(World world, BlockPos pos, IBlockState state, IBlockAccess schematic, BlockPos local) {
        return new IBlockAccess() {
            private IBlockState at(BlockPos at) {
                if (at.equals(pos)) return state;
                IBlockState real = world.getBlockState(at);
                if (real.getMaterial() != Material.AIR) return real;
                IBlockState planned = schematic.getBlockState(local.add(at.subtract(pos)));
                return hot(world, at, planned) ? planned : real;
            }
            @Override public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos at) { return world.getTileEntity(at); }
            @Override public int getCombinedLight(BlockPos at, int light) { return world.getCombinedLight(at, light); }
            @Override public IBlockState getBlockState(BlockPos at) { return at(at); }
            @Override public boolean isAirBlock(BlockPos at) { return at(at).getMaterial() == Material.AIR; }
            @Override public net.minecraft.world.biome.Biome getBiome(BlockPos at) { return world.getBiome(at); }
            @Override public int getStrongPower(BlockPos at, EnumFacing side) { return world.getStrongPower(at, side); }
            @Override public net.minecraft.world.WorldType getWorldType() { return world.getWorldType(); }
            @Override public boolean isSideSolid(BlockPos at, EnumFacing side, boolean fallback) {
                return at(at).isSideSolid(this, at, side);
            }
        };
    }

    /** Whether wire at pos would be powered: a powered block or source next to it, or powered wire it connects to. */
    static boolean wirePowered(World world, BlockPos pos) {
        if (world.isBlockIndirectlyGettingPowered(pos) > 0) return true;
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            BlockPos next = pos.offset(side);
            for (BlockPos at : new BlockPos[] {next, next.up(), next.down()}) {
                IBlockState state = world.getBlockState(at);
                if (state.getBlock() instanceof BlockRedstoneWire && state.getValue(BlockRedstoneWire.POWER) > 1) return true;
            }
        }
        return false;
    }

    /**
     * The sides a wire powers the neighbouring block on (BlockRedstoneWire.getWeakPower): every side for a dot, else
     * the ends of its straight lines, never past a corner.
     */
    static EnumSet<EnumFacing> pointed(IBlockState wire) {
        EnumSet<EnumFacing> connected = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) if (connects(wire, side)) connected.add(side);
        EnumSet<EnumFacing> pointed = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            if (connected.isEmpty() || connected.contains(side) && !connected.contains(side.rotateY()) && !connected.contains(side.rotateYCCW())) {
                pointed.add(side.getOpposite());
            }
        }
        return pointed;
    }

    /** Whether the wire's attach property towards side (north, south, east, west) is side or up. */
    private static boolean connects(IBlockState wire, EnumFacing side) {
        for (net.minecraft.block.properties.IProperty<?> property : wire.getPropertyKeys()) {
            if (property.getName().equals(side.getName())) return !"none".equals(String.valueOf(wire.getValue(property)));
        }
        return false;
    }

    /** Doors, trapdoors and gates held open or shut by redstone are not clicked. */
    static boolean poweredOpenable(World world, BlockPos pos, IBlockState real) {
        Block block = real.getBlock();
        if (!(block instanceof BlockDoor || block instanceof BlockTrapDoor || block instanceof BlockFenceGate)) return false;
        if (world.isBlockPowered(pos)) return true;
        return block instanceof BlockDoor && (world.isBlockPowered(pos.up()) || world.isBlockPowered(pos.down()));
    }
}
