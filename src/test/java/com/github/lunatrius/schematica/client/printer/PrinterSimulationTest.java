package com.github.lunatrius.schematica.client.printer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import org.junit.Assume;
import org.junit.Test;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.client.util.BlockToItemStack;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ServerProxy;
import com.github.lunatrius.schematica.util.BlockGroups;
import com.github.lunatrius.schematica.world.schematic.BlockStates;
import com.github.lunatrius.schematica.world.schematic.SchematicFormat;

/**
 * Builds every .litematic in the project's import folder in a simulated server world with the printer's decisions
 * (PrinterStates, StateClicks, PlacementSolver, breakWanted), one action per tick like placeBlocksPerTick 1, while
 * redstone, pistons, observers, tripwire and fluids update as in game. Reports positions the printer acts on again and
 * again (loops), states it cannot place, and what is not built at the end. Runs only with PRINTER_SIM=1.
 */
public class PrinterSimulationTest {
    private static final int LOOP_LIMIT = 8, MAX_TICKS = 60000, IDLE_TICKS = 400;
    private static final double REACH = 4.5;

    private static final class FakePlayer extends EntityPlayer {
        FakePlayer(SimWorld world) { super(world, new GameProfile(java.util.UUID.randomUUID(), "printer")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return true; }
    }

    private static EntityPlayer player(SimWorld world) {
        EntityPlayer player = new FakePlayer(world);
        player.capabilities.isCreativeMode = true;
        return player;
    }

    /** One scenario: an order of the positions, whether the printer breaks wrong blocks, a random seed. */
    private static final class Scenario {
        final String name;
        final boolean breaks;
        final long seed;
        Scenario(String name, boolean breaks, long seed) { this.name = name; this.breaks = breaks; this.seed = seed; }
    }

    private static final class Track {
        final List<String> actions = new ArrayList<>();
        boolean refused;
        int refusals;
        String refusedWhy;
        /** The first and the last redstone order wait (RedstoneOrder.hold), with the tick. */
        String firstWait, lastWait;
    }

    @Test public void buildImportedSchematics() throws Exception {
        Assume.assumeTrue("set PRINTER_SIM=1 to run", "1".equals(System.getenv("PRINTER_SIM")));
        File folder = new File("../reference/test-schematics");
        String only = System.getenv("PRINTER_SIM_FILE");
        File[] files = folder.listFiles((dir, name) -> (name.toLowerCase().endsWith(".litematic") || name.toLowerCase().endsWith(".schematic"))
            && (only == null || name.toLowerCase().contains(only.toLowerCase())));
        Assume.assumeTrue("no files in " + folder.getAbsolutePath(), files != null && files.length > 0);
        Bootstrap.register();
        SchematicaPlus.proxy = new ServerProxy() {
            @Override public File getDataDirectory() { return new File("build/tmp"); }
        };
        new File("build/printer-sim").mkdirs();
        StringBuilder summary = new StringBuilder();
        for (File file : files) {
            ISchematic schematic = SchematicFormat.readFromFile(file);
            if (schematic == null) { summary.append(file.getName()).append(": could not read\n"); continue; }
            SchematicWorld placement = new SchematicWorld(schematic, file.getName());
            List<Scenario> scenarios = new ArrayList<>();
            for (boolean breaks : new boolean[] {false, true}) {
                scenarios.add(new Scenario("layers" + (breaks ? "+break" : ""), breaks, 1));
                scenarios.add(new Scenario("topdown" + (breaks ? "+break" : ""), breaks, 2));
                for (long seed = 10; seed < 13; seed++) scenarios.add(new Scenario("random" + seed + (breaks ? "+break" : ""), breaks, seed));
            }
            for (Scenario scenario : scenarios) {
                String result = run(placement, scenario, file.getName());
                summary.append(file.getName()).append(" [").append(scenario.name).append("] ").append(result).append('\n');
            }
        }
        Files.write(new File("build/printer-sim/summary.txt").toPath(), summary.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(summary);
    }

    private static List<BlockPos> order(SchematicWorld placement, Scenario scenario) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 0; y < placement.getHeight(); y++) for (int z = 0; z < placement.getLength(); z++) for (int x = 0; x < placement.getWidth(); x++) {
            BlockPos local = new BlockPos(x, y, z);
            if (placement.getSchematic().containsBlock(local)) positions.add(local);
        }
        if (scenario.name.startsWith("topdown")) Collections.reverse(positions);
        else if (scenario.name.startsWith("random")) Collections.shuffle(positions, new Random(scenario.seed));
        return positions;
    }

    /** Scaffolds placed under fence gates in the current run (SchematicPrinter.scaffolds). */
    private static final java.util.Set<BlockPos> SCAFFOLDS = new java.util.HashSet<>();
    /** SchematicPrinter.redstoneOrder for the current run (off with PRINTER_SIM_ORDER=0) and the current tick. */
    private static final RedstoneOrder ORDER = new RedstoneOrder();
    private static final boolean ORDERED = !"0".equals(System.getenv("PRINTER_SIM_ORDER"));
    private static long now;

    private static String run(SchematicWorld placement, Scenario scenario, String fileName) throws Exception {
        SimWorld world = new SimWorld();
        SCAFFOLDS.clear();
        ORDER.clear();
        EntityPlayer player = player(world);
        Random random = new Random(scenario.seed * 31 + 7);
        List<BlockPos> order = order(placement, scenario);
        Map<BlockPos, Track> tracks = new LinkedHashMap<>();
        Map<BlockPos, Long> cooldown = new HashMap<>();
        int timeout = Math.max(1, ConfigurationHandler.timeout);
        Map<BlockPos, Integer> stalls = new HashMap<>();
        int idle = 0, actions = 0;
        long tick;
        for (tick = 0; tick < MAX_TICKS && idle < IDLE_TICKS; tick++) {
            world.step();
            now = tick;
            BlockPos anchor = null;
            for (BlockPos local : order) {
                if (!pending(world, placement, local, tracks, scenario) || stalls.getOrDefault(local, 0) > tick) continue;
                anchor = local;
                break;
            }
            if (anchor == null) { idle = world.idle() ? idle + 1 : 0; continue; }
            // the player stands within reach of the next unfinished position, looking at it
            double angle = random.nextDouble() * Math.PI * 2, distance = 1.5 + random.nextDouble() * 2;
            player.posX = anchor.getX() + 0.5 + Math.cos(angle) * distance;
            player.posZ = anchor.getZ() + 0.5 + Math.sin(angle) * distance;
            player.posY = anchor.getY() - 1 + random.nextInt(3);
            double eyeX = player.posX, eyeY = player.posY + 1.62, eyeZ = player.posZ;
            double dx = anchor.getX() + 0.5 - eyeX, dy = anchor.getY() + 0.5 - eyeY, dz = anchor.getZ() + 0.5 - eyeZ;
            player.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            player.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            boolean acted = false;
            // SchematicPrinter.removeScaffold: a scaffold whose gate stands is mined first
            for (java.util.Iterator<BlockPos> it = SCAFFOLDS.iterator(); it.hasNext() && !acted;) {
                BlockPos scaffold = it.next();
                if (!Scaffold.isMaterial(world.getBlockState(scaffold))) { it.remove(); continue; }
                if (!Scaffold.done(world, scaffold) || scaffold.distanceSqToCenter(eyeX, eyeY, eyeZ) > REACH * REACH) continue;
                world.setBlockState(scaffold, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
                it.remove();
                tracks.computeIfAbsent(scaffold, k -> new Track()).actions.add("t" + tick + " mine scaffold");
                actions++;
                acted = true;
            }
            ORDER.takeHeld();
            for (int round = 0; round < 2 && !acted && !(round == 1 && ORDER.takeHeld()); round++)
            for (BlockPos local : order) {
                if (local.distanceSqToCenter(eyeX, eyeY, eyeZ) > REACH * REACH) continue;
                // fluids last, as SchematicPrinter.print does
                if (FluidPrinter.isFluid(placement.getBlockState(local).getBlock()) != (round == 1)) continue;
                Long until = cooldown.get(local);
                if (until != null && until > tick) continue;
                Track track = tracks.computeIfAbsent(local, k -> new Track());
                if (track.actions.size() >= LOOP_LIMIT) continue;
                String action = act(world, placement, local, player, scenario, track);
                if (action == null) continue;
                track.actions.add("t" + tick + " " + action);
                cooldown.put(local, tick + timeout);
                // SchematicPrinter.holdPulse
                if (ORDERED && action.startsWith("place ")) {
                    for (BlockPos cell : RedstoneOrder.pulseCells(world, local, placement.getBlockState(local))) {
                        cooldown.merge(cell, tick + RedstoneOrder.PULSE_HOLD, Math::max);
                    }
                }
                actions++;
                acted = true;
                break;
            }
            // a position nothing could be done for is tried again later, as a player walking past would
            if (!acted) stalls.put(anchor, (int) tick + 300);
            idle = 0;
        }
        return report(world, placement, scenario, fileName, tracks, tick, actions);
    }

    /** Whether the printer still has something to do at the position (not built, not a piston part, not given up). */
    private static boolean pending(SimWorld world, SchematicWorld placement, BlockPos local, Map<BlockPos, Track> tracks, Scenario scenario) {
        Track track = tracks.get(local);
        if (track != null && (track.actions.size() >= LOOP_LIMIT || track.refused)) return false;
        IBlockState expected = placement.getBlockState(local), real = world.getBlockState(local);
        if (PrinterStates.pistonBusy(expected, real) || PrinterStates.built(world, local, expected, real)) return false;
        if (expected.getBlock().isAir(expected, placement, local)) return scenario.breaks && !real.getBlock().isAir(real, world, local);
        if (FluidPrinter.isFluid(expected.getBlock())) return FluidPrinter.source(placement, local) && !FluidPrinter.matches(placement, local, world, local);
        return !MultiBlockPlacement.secondary(expected) || false;
    }

    /** SchematicPrinter.print for one position: break first, then placeBlock. Returns the action taken, or null. */
    private static String act(SimWorld world, SchematicWorld placement, BlockPos pos, EntityPlayer player, Scenario scenario, Track track) {
        IBlockState expected = placement.getBlockState(pos), real = world.getBlockState(pos);
        if (!SCAFFOLDS.contains(pos) && SchematicPrinter.INSTANCE.breakWanted(world, placement, pos, scenario.breaks, scenario.breaks, scenario.breaks)) {
            world.setBlockState(pos, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
            return "break " + BlockStates.toString(real) + " (want " + BlockStates.toString(expected) + ")";
        }
        if (PrinterStates.pistonBusy(expected, real) || PrinterStates.built(world, pos, expected, real)) return null;
        Block block = expected.getBlock();
        int clicks = PrinterStates.poweredOpenable(world, pos, real) ? -1 : StateClicks.clicks(real, expected);
        if (clicks > 0) {
            PrinterLook look = StateClicks.look(expected);
            if (look != null) { player.rotationYaw = look.yaw; player.rotationPitch = look.pitch; }
            for (int i = 0; i < clicks; i++) {
                IBlockState now = world.getBlockState(pos);
                now.getBlock().onBlockActivated(world, pos, now, player, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5f, 0.5f, 0.5f);
            }
            return "click x" + clicks + " " + BlockStates.toString(real) + " -> " + BlockStates.toString(world.getBlockState(pos));
        }
        if (clicks == 0) return null;
        if (SnowLayers.missing(real, expected) > 0) {
            return "snow layer" + use(world, player, new ItemStack(net.minecraft.init.Blocks.SNOW_LAYER), pos, EnumFacing.UP, 0.5f, 0.5f, 0.5f);
        }
        if (FluidPrinter.isFluid(block)) {
            if (!FluidPrinter.source(placement, pos) || FluidPrinter.matches(placement, pos, world, pos)) return null;
            if (!PrinterStates.fluidSafe(world, pos, expected.getMaterial())) { track.refusedWhy = "fluid waits: the other fluid touches it"; return null; }
            if (!world.isAirBlock(pos) && !FluidPrinter.isFluid(real.getBlock())) return null;
            Block flowing = expected.getMaterial() == net.minecraft.block.material.Material.LAVA ? net.minecraft.init.Blocks.FLOWING_LAVA
                : net.minecraft.init.Blocks.FLOWING_WATER;
            world.setBlockState(pos, flowing.getDefaultState(), 11);
            return "bucket " + flowing.getRegistryName();
        }
        if (block.isAir(expected, placement, pos)) return null;
        if (PrinterStates.primes(world, pos, expected)) { track.refusedWhy = "TNT would be primed"; return null; }
        if (PrinterStates.wireWaits(world, pos, placement, pos, expected)) { track.refusedWhy = "wire waits: it would power other blocks"; return null; }
        if (PrinterStates.sourceWaits(world, pos, placement, pos, expected)) {
            BlockPos wire = PrinterStates.unfinishedWire(world, pos, placement, pos, expected);
            track.refusedWhy = "power source " + BlockStates.toString(expected) + " waits: wire " + wire + " is " + BlockStates.toString(world.getBlockState(wire).getActualState(world, wire))
                + " not " + BlockStates.toString(placement.getBlockState(wire).getActualState(placement, wire));
            return null;
        }
        if (ORDERED) {
            BlockPos cause = ORDER.hold(world, pos, placement, pos, expected, now);
            if (cause != null) {
                String why = "t" + now + " waits for " + cause + " (" + BlockStates.toString(placement.getBlockState(cause)) + ", world "
                    + BlockStates.toString(world.getBlockState(cause)) + ")";
                if (track.firstWait == null) track.firstWait = why;
                track.lastWait = why;
                track.refusedWhy = "redstone order: waits for " + cause + " (" + BlockStates.toString(placement.getBlockState(cause)) + ", world "
                    + BlockStates.toString(world.getBlockState(cause)) + ")";
                return null;
            }
        }
        MultiBlockPlacement.Kind multi = MultiBlockPlacement.kind(block);
        if (multi != null && MultiBlockPlacement.secondary(expected)) return null;
        ItemStack stack = BlockToItemStack.getItemStack(player, expected, placement, pos);
        if (stack.isEmpty() || SchematicPrinter.INSTANCE.isBlacklisted(block, stack)) {
            track.refused = true;
            track.refusedWhy = "no item";
            return null;
        }
        if (multi != null) return placeMulti(world, pos, expected, stack, player, multi);
        boolean slab = SchematicPrinter.completesSlab(expected, real, stack);
        boolean doubleSlab = !slab && SchematicPrinter.isDoubleSlab(block);
        if (!slab) {
            if (!real.getBlock().isReplaceable(world, pos)) return null;
            if (ConfigurationHandler.printFallingBlockCheck && PrinterStates.fallsNow(world, pos, expected)) return null;
            if (!doubleSlab && !block.canPlaceBlockAt(world, pos)) {
                if (Scaffold.needed(world, placement, pos, pos, expected)) {
                    BlockPos below = pos.down();
                    EnumFacing[] around = PlacementSolver.solidSides(world, below);
                    String result = around.length > 0
                        ? use(world, player, new ItemStack(net.minecraft.init.Blocks.COBBLESTONE), below.offset(around[0]), around[0].getOpposite(), 0.5f, 0.5f, 0.5f)
                        : use(world, player, new ItemStack(net.minecraft.init.Blocks.COBBLESTONE), below, EnumFacing.UP, 0.5f, 0.5f, 0.5f);
                    if (Scaffold.isMaterial(world.getBlockState(below))) SCAFFOLDS.add(below);
                    return "scaffold under gate" + result;
                }
                track.refusedWhy = "cannot stand here yet (below: " + BlockStates.toString(world.getBlockState(pos.down())) + ")";
                return null;
            }
        }
        EnumFacing[] solid = PlacementSolver.solidSides(world, pos);
        BlockPos clicked;
        EnumFacing side;
        float hx, hy, hz;
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        if (slab) {
            SchematicPrinter.Click click = SchematicPrinter.slabClick(pos, real);
            clicked = click.pos; side = click.side; hx = 0.5f; hy = 0.5f; hz = 0.5f;
        } else {
            IBlockState wanted = doubleSlab ? SchematicPrinter.singleSlab(stack) : expected;
            PlacementSolver.Solution solved = PlacementSolver.solve(world, player, stack, wanted, pos, solid, ConfigurationHandler.placeInAir);
            if (solved == PlacementSolver.REFUSE) {
                track.refusedWhy = "solver refused (no click gives " + BlockStates.toString(wanted) + ")";
                if (++track.refusals > 60) track.refused = true;
                return null;
            }
            if (solved != null) {
                clicked = solved.pos; side = solved.side;
                hx = (float) (solved.hit.x - clicked.getX()); hy = (float) (solved.hit.y - clicked.getY()); hz = (float) (solved.hit.z - clicked.getZ());
                if (solved.look != null) { player.rotationYaw = solved.look.yaw; player.rotationPitch = solved.look.pitch; }
            } else if (solid.length > 0) {
                clicked = pos.offset(solid[0]); side = solid[0].getOpposite();
                hx = 0.5f + side.getFrontOffsetX() * 0.5f; hy = 0.5f + side.getFrontOffsetY() * 0.5f; hz = 0.5f + side.getFrontOffsetZ() * 0.5f;
            } else if (ConfigurationHandler.placeInAir) {
                clicked = pos; side = EnumFacing.UP; hx = 0.5f; hy = 0.5f; hz = 0.5f;
            } else return null;
        }
        String result = use(world, player, stack, clicked, side, hx, hy, hz);
        if (doubleSlab && world.getBlockState(pos).getBlock() != block) {
            SchematicPrinter.Click second = SchematicPrinter.slabClick(pos, world.getBlockState(pos));
            use(world, player, stack, second.pos, second.side, 0.5f, 0.5f, 0.5f);
        }
        player.rotationYaw = yaw; player.rotationPitch = pitch;
        return "place " + BlockStates.toString(expected) + " -> " + BlockStates.toString(world.getBlockState(pos)) + result;
    }

    private static String placeMulti(SimWorld world, BlockPos pos, IBlockState expected, ItemStack stack, EntityPlayer player, MultiBlockPlacement.Kind kind) {
        if (!expected.getBlock().canPlaceBlockAt(world, pos)) return null;
        EnumFacing facing = MultiBlockPlacement.wantedFacing(kind, expected);
        float yaw = player.rotationYaw;
        if (facing != null) {
            PrinterLook look = MultiBlockPlacement.look(player.rotationYaw, facing);
            if (look == null) return null;
            player.rotationYaw = look.yaw;
        }
        String result = use(world, player, stack, pos.down(), EnumFacing.UP, 0.5f, 1f, 0.5f);
        player.rotationYaw = yaw;
        return "place " + BlockStates.toString(expected) + " -> " + BlockStates.toString(world.getBlockState(pos)) + result;
    }

    /** The server side of a right click with the item while sneaking: Item.onItemUse. */
    private static String use(SimWorld world, EntityPlayer player, ItemStack stack, BlockPos clicked, EnumFacing side, float hx, float hy, float hz) {
        int slot = player.inventory.currentItem;
        player.inventory.mainInventory.set(slot, stack.copy());
        try {
            return " [" + stack.getItem().onItemUse(player, world, clicked, EnumHand.MAIN_HAND, side, hx, hy, hz) + "]";
        } catch (RuntimeException error) {
            StringBuilder where = new StringBuilder(error.toString());
            StackTraceElement[] trace = error.getStackTrace();
            for (int i = 0; i < Math.min(4, trace.length); i++) where.append(" / ").append(trace[i]);
            return " [error " + where + "]";
        } finally {
            player.inventory.mainInventory.set(slot, ItemStack.EMPTY);
        }
    }

    private static String report(SimWorld world, SchematicWorld placement, Scenario scenario, String fileName, Map<BlockPos, Track> tracks,
                                 long ticks, int actions) throws IOException {
        StringBuilder text = new StringBuilder();
        int loops = 0, refused = 0, wrong = 0;
        StringBuilder loopText = new StringBuilder(), refusedText = new StringBuilder(), wrongText = new StringBuilder();
        for (Map.Entry<BlockPos, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            if (track.actions.size() > SchematicPrinter.MAX_ATTEMPTS) {
                loops++;
                loopText.append("  ").append(entry.getKey()).append(" want ").append(BlockStates.toString(placement.getBlockState(entry.getKey()))).append('\n');
                for (String action : track.actions) loopText.append("      ").append(action).append('\n');
            }
        }
        for (int y = 0; y < placement.getHeight(); y++) for (int z = 0; z < placement.getLength(); z++) for (int x = 0; x < placement.getWidth(); x++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!placement.getSchematic().containsBlock(pos)) continue;
            IBlockState expected = placement.getBlockState(pos), real = world.getBlockState(pos);
            if (PrinterStates.pistonBusy(expected, real) || PrinterStates.built(world, pos, expected, real)) continue;
            if (FluidPrinter.isFluid(expected.getBlock()) && (!FluidPrinter.source(placement, pos) || FluidPrinter.matches(placement, pos, world, pos))) continue;
            if (FluidPrinter.isFluid(expected.getBlock()) && FluidPrinter.isFluid(real.getBlock())) continue;
            if (MultiBlockPlacement.kind(expected.getBlock()) != null && MultiBlockPlacement.secondary(expected) && real.getBlock() == expected.getBlock()) continue;
            if (expected.getBlock().isAir(expected, placement, pos) && !scenario.breaks) continue;
            Track track = tracks.get(pos);
            if (track != null && track.refusedWhy != null) {
                refused++;
                refusedText.append("  ").append(pos).append(' ').append(track.refusedWhy).append(" | world ").append(BlockStates.toString(real)).append('\n');
                if (track.firstWait != null) refusedText.append("      first ").append(track.firstWait).append("\n      last ").append(track.lastWait).append('\n');
            } else {
                wrong++;
                wrongText.append("  ").append(pos).append(" want ").append(BlockStates.toString(expected)).append(" have ").append(BlockStates.toString(real))
                    .append(track == null ? " (never tried)" : " after " + track.actions.size() + " actions").append('\n');
            }
        }
        text.append(fileName).append(" scenario ").append(scenario.name).append('\n')
            .append("ticks ").append(ticks).append(", actions ").append(actions).append(", entities spawned ").append(world.spawned).append('\n')
            .append("\nLOOPS (more than ").append(SchematicPrinter.MAX_ATTEMPTS).append(" actions at one position): ").append(loops).append('\n').append(loopText)
            .append("\nCANNOT PLACE: ").append(refused).append('\n').append(refusedText)
            .append("\nNOT BUILT: ").append(wrong).append('\n').append(wrongText)
            .append("\nSPAWNS (actions within 5 blocks in the 80 ticks before):\n");
        for (Object[] spawn : world.spawns) {
            long time = (Long) spawn[0];
            BlockPos at = (BlockPos) spawn[1];
            text.append("  t").append(time).append(' ').append(at).append('\n');
            for (Map.Entry<BlockPos, Track> entry : tracks.entrySet()) {
                if (entry.getKey().distanceSq(at) > 25) continue;
                for (String action : entry.getValue().actions) {
                    long done = Long.parseLong(action.substring(1, action.indexOf(' ')));
                    if (done <= time && done >= time - 80) text.append("      ").append(entry.getKey()).append(' ').append(action).append('\n');
                }
            }
        }
        text.append("\nWORLD LOG:\n");
        for (String line : world.log) text.append("  ").append(line).append('\n');
        String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + scenario.name;
        Files.write(new File("build/printer-sim/" + safe + ".txt").toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return "ticks " + ticks + ", actions " + actions + ", loops " + loops + ", cannot place " + refused + ", not built " + wrong + ", spawned " + world.spawned
            + ", observer pulses " + world.observerPulses + ", piston extends " + world.pistonExtends;
    }
}
