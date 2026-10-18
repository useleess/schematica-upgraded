// SPDX-License-Identifier: LGPL-3.0-only
// Litematica Easy Place (WorldUtils.doEasyPlaceAction, placement protocol "None"), adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.input.Hotkeys;
import com.github.lunatrius.schematica.client.util.BlockToItemStack;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.BlockInfoHudSettings;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.tool.SchematicTargets;
import com.github.lunatrius.schematica.tool.ToolManager;
import com.github.lunatrius.schematica.tool.ToolMode;
import com.github.lunatrius.schematica.util.BlockGroups;

/**
 * Places the targeted schematic block with the item it needs. On a Plus server with accurate placement the server sets
 * the schematic's state, as upstream with protocol v3; otherwise the click (face, height and the player's facing) is
 * chosen by simulating the item use, as upstream with protocol "None".
 */
public final class EasyPlace {
    public enum Result { PASS, SUCCESS, FAIL }

    private static final long CACHE_NANOS = 2_000_000_000L;
    private static final List<long[]> PLACED = new ArrayList<>();
    private static long lastPickTime = System.nanoTime();

    private EasyPlace() {}

    private static Minecraft mc() { return Minecraft.getMinecraft(); }

    /** The easyPlaceUseKey press (WorldUtils.handleEasyPlace); returns whether the use action is consumed. */
    public static boolean handle() {
        if (!ConfigurationHandler.easyPlaceMode || ToolManager.getCurrentMode() == ToolMode.REBUILD) return false;
        Result result = act();
        if (result == Result.FAIL) {
            warn("litematica.message.easy_place_fail");
            return true;
        }
        return result != Result.PASS;
    }

    /** Repeats Easy Place every client tick while the use key is held (easyPlaceOnUseTick). */
    public static void tick(Minecraft mc) {
        if (mc.player != null && mc.currentScreen == null && ConfigurationHandler.easyPlaceMode && ConfigurationHandler.easyPlaceHoldEnabled
            && ToolManager.getCurrentMode() != ToolMode.REBUILD && Hotkeys.held("easyPlaceUseKey")) act();
    }

    /** Prints a warning according to placementRestrictionWarn. */
    public static void warn(String key) {
        if ("message".equals(ConfigurationHandler.placementRestrictionWarn)) {
            if (mc().player != null) mc().player.sendMessage(new TextComponentTranslation(key));
        } else if ("actionbar".equals(ConfigurationHandler.placementRestrictionWarn) && mc().ingameGUI != null) {
            mc().ingameGUI.setOverlayMessage(UiTranslations.format(key), false);
        }
    }

    static Result act() {
        Minecraft mc = mc();
        EntityPlayerSP player = mc.player;
        World world = mc.world;
        if (player == null || world == null || ClientProxy.visiblePlacements().isEmpty()) return Result.PASS;
        double range = PrinterRange.easyPlace();
        SchematicTargets.Hit hit = ConfigurationHandler.easyPlaceFirst ? SchematicTargets.closest(range, BlockInfoHudSettings.targetFluids)
            : SchematicTargets.furthestBeforeVanilla(range);
        if (hit == null) return PlacementRestriction.inEffect(mc) ? Result.FAIL : Result.PASS;
        SchematicWorld schematic = hit.world;
        BlockPos local = hit.localPos(), pos = new BlockPos(hit.x, hit.y, hit.z);
        EnumFacing prerequisite = Prerequisites.support(world, schematic, local, pos);
        boolean redirected = prerequisite != null;
        if (redirected) {
            local = local.offset(prerequisite);
            pos = pos.offset(prerequisite);
        }
        IBlockState state = schematic.getBlockState(local);
        MultiBlockPlacement.Kind kind = MultiBlockPlacement.kind(state.getBlock());
        if (kind != null) return multiBlock(player, world, schematic, local, pos, state, kind);
        if (cached(pos) || System.nanoTime() - lastPickTime < 1_000_000L * ConfigurationHandler.easyPlaceSwapInterval) return Result.FAIL;
        IBlockState real = world.getBlockState(pos);
        if (PrinterStates.pistonBusy(state, real) || PrinterStates.built(world, pos, state, real)) return Result.FAIL;
        SchematicPrinter printer = SchematicPrinter.INSTANCE;
        ToolUse.Click tool = ToolUse.click(world, state, real, pos);
        if (tool != null) {
            cache(pos);
            return ToolUse.use(printer, world, player, state, tool) ? Result.SUCCESS : Result.FAIL;
        }
        if (FluidPrinter.isFluid(state.getBlock())) {
            // only sources are placed; flowing fluid comes from them
            if (!FluidPrinter.source(schematic, local) || FluidPrinter.matches(schematic, local, world, pos)) return Result.FAIL;
            if (!PrinterStates.fluidSafe(world, pos, state.getMaterial())) return Result.FAIL;
            if (!world.isAirBlock(pos) && !(FluidPrinter.isFluid(real.getBlock()) && FluidPrinter.sameFluid(state.getBlock(), real.getBlock()))) return Result.FAIL;
            cache(pos);
            BlockPos target = pos;
            FluidPrinter.place(mc, FluidPrinter.fluid(state.getBlock()), target,
                bucket -> pick(player, () -> printer.swapToItem(player.inventory, bucket, true, true)));
            return Result.SUCCESS;
        }
        if (PrinterStates.primes(world, pos, state)) return Result.FAIL;
        if (ConfigurationHandler.printFallingBlockCheck && PrinterStates.fallsNow(world, pos, state)) return Result.FAIL;
        ItemStack stack = BlockToItemStack.getItemStack(player, state, schematic, local);
        if (stack.isEmpty()) return Result.FAIL;
        if (SnowLayers.missing(real, state) > 0) return addSnowLayer(player, pos, real, stack);
        boolean slab = SchematicPrinter.completesSlab(state, real, stack);
        boolean doubleSlab = !slab && SchematicPrinter.isDoubleSlab(state.getBlock());
        if (!slab && !real.getBlock().isReplaceable(world, pos)) return Result.FAIL;
        if (!pickStack(player, stack)) return Result.FAIL;
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(stack)) return Result.FAIL;

        boolean accurate = AccuratePlacementClient.active(state.getBlock());
        boolean adjacentOnly = ConfigurationHandler.easyPlacePostRewrite && ConfigurationHandler.easyPlaceClickAdjacent;
        SchematicPrinter.Click click = null;
        PrinterLook placementLook = null;
        Vec3d stand = null;
        if (slab) {
            click = SchematicPrinter.slabClick(pos, real);
        } else {
            IBlockState wanted = doubleSlab ? SchematicPrinter.singleSlab(stack) : state;
            PlacementSolver.Solution solved = wanted == null || !ConfigurationHandler.printerPlacementSolver ? null
                : PlacementSolver.solve(world, player, held, wanted, pos, PlacementSolver.solidSides(world, pos), !adjacentOnly,
                    ConfigurationHandler.printerFakePosition);
            if (solved == PlacementSolver.REFUSE && !accurate) return Result.FAIL;
            if (solved != null && solved != PlacementSolver.REFUSE) {
                click = new SchematicPrinter.Click(solved.pos, solved.side, solved.hit, solved.neighbor);
                placementLook = solved.look;
                stand = solved.stand;
            } else if (adjacentOnly) {
                click = adjacent(world, pos);
                if (click == null) return Result.FAIL;
            } else {
                EnumFacing side = hit.face();
                Vec3d hitVec = redirected ? new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) : new Vec3d(hit.hitX, hit.hitY, hit.hitZ);
                click = new SchematicPrinter.Click(pos, side, hitVec, false);
            }
        }

        cache(pos);
        if (accurate) AccuratePlacementClient.announce(pos, state);
        boolean sneaking = player.isSneaking();
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        printer.syncSneaking(player, true);
        if (placementLook != null) look(player, placementLook.yaw, placementLook.pitch);
        Vec3d standing = stand == null ? null : FakeStand.enter(player, stand);
        boolean success;
        try {
            success = click(player, click);
            // A double slab gets its second half right away
            IBlockState now = world.getBlockState(pos);
            if (success && doubleSlab && !held.isEmpty() && now.getBlock() != state.getBlock()) {
                success = click(player, SchematicPrinter.slabClick(pos, now));
            }
        } finally {
            if (standing != null) FakeStand.leave(player, standing);
            if (placementLook != null) look(player, yaw, pitch);
            printer.syncSneaking(player, sneaking);
        }
        return success ? Result.SUCCESS : Result.FAIL;
    }

    /** Placed snow with fewer layers than the schematic's gets one more per use. */
    private static Result addSnowLayer(EntityPlayerSP player, BlockPos pos, IBlockState real, ItemStack stack) {
        if (!pickStack(player, stack)) return Result.FAIL;
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(stack)) return Result.FAIL;
        // not cached: the next layer may follow on the next use
        SchematicPrinter printer = SchematicPrinter.INSTANCE;
        boolean sneaking = player.isSneaking();
        printer.syncSneaking(player, true);
        try {
            return click(player, SnowLayers.click(pos, real)) ? Result.SUCCESS : Result.FAIL;
        } finally {
            printer.syncSneaking(player, sneaking);
        }
    }

    /** Turns the player and reports the rotation, so that the server places the block facing that way. */
    private static void look(EntityPlayerSP player, float yaw, float pitch) {
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        player.connection.sendPacket(new CPacketPlayer.Rotation(yaw, pitch, player.onGround));
    }

    /** getAdjacentClickPosition: the real block behind the target, else any solid neighbour, clicked in the middle of its face. */
    static SchematicPrinter.Click adjacent(World world, BlockPos pos) {
        RayTraceResult vanilla = SchematicTargets.vanilla(PrinterRange.easyPlace(), false);
        if (vanilla != null && vanilla.typeOfHit == RayTraceResult.Type.BLOCK && vanilla.hitVec != null
            && !world.getBlockState(vanilla.getBlockPos()).getBlock().isReplaceable(world, vanilla.getBlockPos())
            && vanilla.getBlockPos().offset(vanilla.sideHit).equals(pos)) {
            return new SchematicPrinter.Click(vanilla.getBlockPos(), vanilla.sideHit, vanilla.hitVec, true);
        }
        for (EnumFacing side : PlacementSolver.solidSides(world, pos)) {
            BlockPos clicked = pos.offset(side);
            EnumFacing face = side.getOpposite();
            return new SchematicPrinter.Click(clicked, face, new Vec3d(clicked.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                clicked.getY() + 0.5 + face.getFrontOffsetY() * 0.5, clicked.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5), true);
        }
        return null;
    }

    /**
     * A door, bed or double plant: the item is used on the top of the block below its first block, also when the
     * upper half or the bed head is targeted; the player turns temporarily to the schematic's facing.
     */
    private static Result multiBlock(EntityPlayerSP player, World world, SchematicWorld schematic, BlockPos local, BlockPos pos,
                                     IBlockState state, MultiBlockPlacement.Kind kind) {
        if (MultiBlockPlacement.secondary(state)) {
            EnumFacing back = kind == MultiBlockPlacement.Kind.BED ? state.getValue(net.minecraft.block.BlockBed.FACING).getOpposite() : EnumFacing.DOWN;
            local = local.offset(back);
            pos = pos.offset(back);
            IBlockState first = schematic.getBlockState(local);
            if (first.getBlock() != state.getBlock() || MultiBlockPlacement.secondary(first)) return Result.FAIL;
            state = first;
        }
        if (cached(pos) || System.nanoTime() - lastPickTime < 1_000_000L * ConfigurationHandler.easyPlaceSwapInterval) return Result.FAIL;
        BlockPos second = pos.offset(MultiBlockPlacement.secondDirection(kind, state));
        IBlockState real = world.getBlockState(pos), below = world.getBlockState(pos.down());
        if (real.getBlock() == state.getBlock() || !real.getBlock().isReplaceable(world, pos) || !world.getBlockState(second).getBlock().isReplaceable(world, second)
            || below.getBlock().isAir(below, world, pos.down()) || below.getBlock().isReplaceable(world, pos.down())) return Result.FAIL;
        ItemStack stack = BlockToItemStack.getItemStack(player, state, schematic, local);
        if (stack.isEmpty()) return Result.SUCCESS;
        if (!pickStack(player, stack)) return Result.FAIL;
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(stack)) return Result.FAIL;
        EnumFacing facing = MultiBlockPlacement.wantedFacing(kind, state);
        PrinterLook placementLook = facing == null ? null : MultiBlockPlacement.look(player.rotationYaw, facing);
        if (facing != null && placementLook == null) return Result.FAIL;
        cache(pos);
        if (kind == MultiBlockPlacement.Kind.DOOR && AccuratePlacementClient.active(state.getBlock())) {
            AccuratePlacementClient.announce(pos, state);
            IBlockState secondState = schematic.getBlockState(local.up());
            if (secondState.getBlock() == state.getBlock() && MultiBlockPlacement.secondary(secondState)) AccuratePlacementClient.announce(second, secondState);
        }
        SchematicPrinter printer = SchematicPrinter.INSTANCE;
        boolean sneaking = player.isSneaking();
        float yaw = player.rotationYaw, pitch = player.rotationPitch;
        printer.syncSneaking(player, true);
        if (placementLook != null) look(player, placementLook.yaw, placementLook.pitch);
        boolean placed;
        try {
            placed = click(player, new SchematicPrinter.Click(pos.down(), EnumFacing.UP, new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), true));
        } finally {
            if (placementLook != null) look(player, yaw, pitch);
            printer.syncSneaking(player, sneaking);
        }
        return placed ? Result.SUCCESS : Result.FAIL;
    }

    interface Swap { boolean run(); }

    /** Selects the needed item; a changed hotbar slot or stack starts the easyPlaceSwapInterval wait. */
    private static boolean pick(EntityPlayerSP player, Swap swap) {
        int slot = player.inventory.currentItem;
        ItemStack before = player.getHeldItemMainhand();
        if (!swap.run()) return false;
        if (slot != player.inventory.currentItem || before != player.getHeldItemMainhand()) lastPickTime = System.nanoTime();
        return true;
    }

    /** InventoryUtils.schematicWorldPickBlock: creative players get the item in the selected slot when it is not in the hotbar. */
    private static boolean pickStack(EntityPlayerSP player, ItemStack stack) {
        if (pick(player, () -> PickBlockSlots.pickToHand(mc(), stack, false))) return true;
        // not carried: a carried shulker box may hold it (Plus server); it is there for the next use
        ShulkerRestockClient.request(player, stack);
        return false;
    }

    private static boolean click(EntityPlayerSP player, SchematicPrinter.Click click) {
        boolean success = PrinterRange.click(player, click.pos, click.side, click.hit) == net.minecraft.util.EnumActionResult.SUCCESS;
        if (success && ConfigurationHandler.easyPlaceSwingHand) player.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
        return success;
    }

    private static boolean cached(BlockPos pos) {
        long now = System.nanoTime();
        PLACED.removeIf(entry -> now - entry[3] > CACHE_NANOS);
        for (long[] entry : PLACED) if (entry[0] == pos.getX() && entry[1] == pos.getY() && entry[2] == pos.getZ()) return true;
        return false;
    }

    private static void cache(BlockPos pos) { PLACED.add(new long[] {pos.getX(), pos.getY(), pos.getZ(), System.nanoTime()}); }
}
