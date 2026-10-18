package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.selection.AreaSelectionLibrary;
import com.github.lunatrius.schematica.client.selection.AreaSelections;
import com.github.lunatrius.schematica.client.util.BlockToItemStack;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.AirPlacement;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.client.InputHandler;
import com.github.lunatrius.schematica.internal.lunatriuscore.util.vector.Vector3i;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Constants;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.BlockGroups;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

public class SchematicPrinter {

    public static final SchematicPrinter INSTANCE = new SchematicPrinter();

    private final Minecraft minecraft = Minecraft.getMinecraft();

    private volatile boolean isEnabled = true;
    private volatile boolean isPrinting;
    private volatile String pendingMessage;

    private SchematicWorld schematic = null;
    private final Map<Long, Long> cooldowns = new HashMap<>();
    /**
     * Loop guard: the actions (placements, breaks, clicks) the printer took at a position, with the tick of the last one.
     * A position that needed more than MAX_ATTEMPTS actions within ATTEMPT_WINDOW ticks keeps changing back (a running
     * circuit, a piston pushing blocks around) and is left alone until the printer is refreshed.
     */
    private final Map<Long, long[]> attempts = new HashMap<>();
    static final int MAX_ATTEMPTS = 5;
    /** Scaffold blocks this printer placed under fence gates (Scaffold), mined again once the gate stands. */
    private final java.util.Set<BlockPos> scaffolds = new java.util.HashSet<>();
    private World scaffoldWorld;
    /** Ice this printer placed for water sources (printerIceWater), mined again to leave the water. */
    private final java.util.Set<BlockPos> ice = new java.util.HashSet<>();
    private World iceWorld;
    static final long ATTEMPT_WINDOW = 600;
    private long tick;
    private Pass pass;
    /** The block being mined, after litematica-printer's printBreakWrongBlock/ExtraBlock. */
    private BlockPos mining;
    private final BlockMiner miner = new BlockMiner();
    /** printerRedstoneOrder: observers and pistons placed in an order that does not set them off. */
    private final RedstoneOrder redstoneOrder = new RedstoneOrder();
    private final ContainerFiller filler = new ContainerFiller();
    /** printAutoTool: the hotbar slot of the tool held while mining and the slot to go back to afterwards. */
    private int toolSlot = -1, toolRestoreSlot;

    public boolean isEnabled() {
        return this.isEnabled;
    }

    /** Whether the server allows printing (its printerEnabled option); turning it off stops the printer. */
    public void setEnabled(boolean isEnabled) {
        if (!isEnabled && this.isPrinting) {
            this.isPrinting = false;
            message("schematica.message.printer.disabled");
        }
        this.isEnabled = isEnabled;
    }

    /** The workingSwitch hotkey: toggles printing with the MaLiLib toggle message, unless the server forbids it. */
    public void toggleWithMessage() {
        if (!this.isEnabled) {
            this.isPrinting = false;
            message("schematica.message.printer.disabled");
            return;
        }
        this.isPrinting = !this.isPrinting;
        if (this.isPrinting) refresh();
        InputHandler.printToggle(UiTranslations.format("schematica.printer.name"), this.isPrinting);
    }

    /** Stops printing after death, like litematica-printer's auto disable. */
    public void stopWithMessage(String key) {
        if (!this.isPrinting) return;
        this.isPrinting = false;
        message(key);
    }

    /** Capabilities arrive on the network thread, so messages are shown on the next client tick. */
    private void message(String key) { this.pendingMessage = key; }

    public void showPendingMessage() {
        String key = this.pendingMessage;
        if (key == null || this.minecraft.ingameGUI == null) return;
        this.pendingMessage = null;
        this.minecraft.ingameGUI.setOverlayMessage(UiTranslations.format(key), false);
    }

    public boolean isPrinting() {
        return this.isPrinting;
    }

    public void setPrinting(boolean isPrinting) {
        if (!isPrinting) stopMining();
        this.isPrinting = isPrinting;
    }

    public SchematicWorld getSchematic() {
        return this.schematic;
    }

    public void setSchematic(SchematicWorld schematic) {
        this.schematic = schematic;
        refresh();
    }

    public void refresh() {
        this.cooldowns.clear();
        this.attempts.clear();
        this.redstoneOrder.clear();
        this.filler.clear();
        ManualMining.forget();
        stopMining();
    }

    /** Stops a started mining once printing was turned off (the switch may change on the network thread). */
    public void tickIdle() {
        if (!this.isPrinting || !this.isEnabled) {
            if (this.mining != null && !DestroyAssist.active()) stopMining();
            PrinterMissingMaterials.clear();
        }
    }

    /** The client player, or null without a client (tests). */
    private net.minecraft.entity.player.EntityPlayer player() {
        return this.minecraft == null ? null : this.minecraft.player;
    }

    void stopMining() {
        this.miner.abort();
        this.mining = null;
        if (this.toolSlot >= 0 && this.minecraft.player != null) this.minecraft.player.inventory.currentItem = this.toolRestoreSlot;
        this.toolSlot = -1;
    }

    /** Keeps mining the started block; false when there is none (any more) and the pass may place. */
    boolean continueMining(World world, double eyeX, double eyeY, double eyeZ, double range) {
        if (this.mining == null) return false;
        BlockPos pos = this.mining;
        double dx = pos.getX() + 0.5 - eyeX, dy = pos.getY() + 0.5 - eyeY, dz = pos.getZ() + 0.5 - eyeZ;
        if (world.isAirBlock(pos) || dx * dx + dy * dy + dz * dz > range * range) {
            stopMining();
            return false;
        }
        if (this.toolSlot >= 0) this.minecraft.player.inventory.currentItem = this.toolSlot;
        if (this.miner.tick() != BlockMiner.State.MINING) {
            stopMining();
            return false;
        }
        return true;
    }

    /**
     * printBreakExtraBlock / printBreakWrongBlock / printBreakWrongStateBlock: blocks where the schematic has air,
     * another block, or the same block in another state are mined first. Replaceable blocks, fluids, blocks a click
     * fixes and blocks a slab click completes are left to the placement.
     */
    private boolean startBreak(World world, SchematicWorld schematic, BlockPos pos) {
        return breakWanted(world, schematic, pos, ConfigurationHandler.printBreakExtraBlock, ConfigurationHandler.printBreakWrongBlock,
            ConfigurationHandler.printBreakWrongStateBlock) && beginMining(world, pos);
    }

    /** Whether the world block is an extra, wrong or wrong-state block of the placement that may be mined. */
    boolean breakWanted(World world, SchematicWorld schematic, BlockPos pos, boolean extra, boolean wrong, boolean wrongState) {
        if (!extra && !wrong && !wrongState) return false;
        IBlockState real = world.getBlockState(pos);
        if (real.getBlock().isAir(real, world, pos) || FluidPrinter.isFluid(real.getBlock()) || real.getBlockHardness(world, pos) < 0) return false;
        BlockPos local = new BlockPos(pos.getX() - schematic.position.x, pos.getY() - schematic.position.y, pos.getZ() - schematic.position.z);
        IBlockState state = schematic.getBlockState(local);
        if (PrinterStates.pistonBusy(state, real) || PrinterStates.fluidMade(world, pos, real) || FluidPrinter.isFluid(state.getBlock())) return false;
        // scaffolds are only mined by removeScaffold, after their gate is built
        if (world == this.scaffoldWorld && this.scaffolds.contains(pos)) return false;
        if (state.getBlock().isAir(state, schematic, local)) {
            return extra && !ConfigurationHandler.isExtraAirBlock(real.getBlock());
        }
        if (PrinterStates.built(world, pos, state, real)) return false;
        if (state.getBlock() != real.getBlock()) {
            return wrong && !BlockGroups.tolerated(state, real) && !BlockGroups.switchedPair(state.getBlock(), real.getBlock())
                && !ToolUse.fixable(world, state, real, pos)
                && !real.getBlock().isReplaceable(world, pos)
                && !completesSlab(state, real, BlockToItemStack.getItemStack(player(), state, schematic, local));
        }
        MultiBlockPlacement.Kind kind = MultiBlockPlacement.kind(state.getBlock());
        boolean same = kind != null ? MultiBlockPlacement.sameState(kind, state, real, AccuratePlacementClient.active(state.getBlock()))
            : PrinterStates.built(world, pos, state, real);
        return wrongState && !same && !FluidPrinter.isFluid(state.getBlock()) && !BlockGroups.tolerated(state, real)
            && StateClicks.clicks(real, state) < 0 && SnowLayers.missing(real, state) == 0;
    }

    /** Starts mining a block with the best tool; false when it could not start. */
    boolean beginMining(World world, BlockPos pos) {
        return beginMining(world, pos, false);
    }

    /** noSilk: a Silk Touch tool is never used (ice mined for its water); false when only one is at hand. */
    private boolean beginMining(World world, BlockPos pos, boolean noSilk) {
        EntityPlayerSP player = this.minecraft.player;
        int previous = player.inventory.currentItem;
        boolean switched = selectTool(player, world, pos, noSilk);
        if (noSilk && IceWater.silk(player.getHeldItemMainhand())) return false;
        BlockMiner.State state = this.miner.start(pos, EnumFacing.UP);
        if (state == BlockMiner.State.IDLE) return false;
        player.swingArm(EnumHand.MAIN_HAND);
        PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.BREAK);
        if (state == BlockMiner.State.MINING) {
            this.mining = pos;
            if (switched) {
                this.toolSlot = player.inventory.currentItem;
                this.toolRestoreSlot = this.pass != null ? this.pass.slot : previous;
            }
        }
        return true;
    }

    /**
     * printAutoTool: holds the item of the hotbar, or of the inventory through the swap slots, that mines the block
     * fastest (harvest level and enchantments included); tools at or below printAutoToolDurability uses left are not
     * used. The held item stays when nothing is faster. True when another slot was selected.
     */
    private boolean selectTool(EntityPlayerSP player, World world, BlockPos pos, boolean noSilk) {
        InventoryPlayer inventory = player.inventory;
        int current = inventory.currentItem;
        // a held Silk Touch tool has to go even without printAutoTool
        boolean leave = noSilk && IceWater.silk(inventory.mainInventory.get(current));
        if (!leave && (!ConfigurationHandler.printAutoTool || this.minecraft.playerController.isInCreativeMode())) return false;
        IBlockState state = world.getBlockState(pos);
        float best = !leave && usableTool(inventory.mainInventory.get(current)) ? strength(player, world, state, pos, inventory.mainInventory.get(current)) : -1;
        int bestSlot = -1;
        int slots = ConfigurationHandler.swapSlotsQueue.isEmpty() ? Constants.Inventory.Size.HOTBAR : inventory.mainInventory.size();
        for (int slot = 0; slot < slots; slot++) {
            if (slot == current || !usableTool(inventory.mainInventory.get(slot))) continue;
            if (noSilk && IceWater.silk(inventory.mainInventory.get(slot))) continue;
            float strength = strength(player, world, state, pos, inventory.mainInventory.get(slot));
            if (strength > best) { best = strength; bestSlot = slot; }
        }
        if (bestSlot < 0) return false;
        if (bestSlot >= Constants.Inventory.Size.HOTBAR) {
            int target = getNextSlot();
            if (!swapSlots(bestSlot, target)) return false;
            bestSlot = target;
        }
        inventory.currentItem = bestSlot;
        return true;
    }

    private static boolean usableTool(ItemStack stack) {
        return stack.isEmpty() || !stack.isItemStackDamageable() || ConfigurationHandler.printAutoToolDurability <= 0
            || stack.getMaxDamage() - stack.getItemDamage() > ConfigurationHandler.printAutoToolDurability;
    }

    /** The block's mining progress per tick with the stack in hand (the held slot is swapped only on the client side). */
    private static float strength(EntityPlayerSP player, World world, IBlockState state, BlockPos pos, ItemStack stack) {
        InventoryPlayer inventory = player.inventory;
        ItemStack held = inventory.mainInventory.get(inventory.currentItem);
        inventory.mainInventory.set(inventory.currentItem, stack);
        try {
            return state.getPlayerRelativeBlockHardness(player, world, pos);
        } catch (RuntimeException error) {
            return -1;
        } finally {
            inventory.mainInventory.set(inventory.currentItem, held);
        }
    }

    /**
     * One printer pass around the player over every visible placement, after the behavior of litematica-printer: the
     * positions within the work range in the configured shape and axis order, at most placeBlocksPerTick placements,
     * each position then waiting `timeout` ticks.
     * @return the number of placement clicks made
     */
    public int print() {
        final EntityPlayerSP player = this.minecraft.player;
        final World world = this.minecraft.world;
        if (player == null || world == null) return 0;
        this.tick = world.getTotalWorldTime();
        if (this.filler.busy()) {
            this.filler.tick(this.minecraft, player);
            return 0;
        }
        if (!this.cooldowns.isEmpty()) this.cooldowns.values().removeIf(until -> until <= this.tick);
        boolean moving = Math.abs(player.posX - player.prevPosX) + Math.abs(player.posY - player.prevPosY) + Math.abs(player.posZ - player.prevPosZ) > 0.001;
        if (ConfigurationHandler.printerPauseWhileMoving && moving) return 0;
        if (ConfigurationHandler.printerLagCheck && LagMonitor.ticksSincePacket() > ConfigurationHandler.printerLagCheckMax) return 0;
        List<SchematicWorld> placements = new ArrayList<>();
        for (SchematicWorld placement : ClientProxy.visiblePlacements()) if (placement.isRenderingEnabled()) placements.add(placement);
        if (placements.isEmpty()) return 0;

        double eyeX = player.posX, eyeY = player.posY + player.getEyeHeight(), eyeZ = player.posZ;
        double feetY = player.getEntityBoundingBox().minY;
        double range = PrinterRange.printer(), mineRange = PrinterRange.mining(range);
        int bx = MathHelper.floor(eyeX), by = MathHelper.floor(eyeY), bz = MathHelper.floor(eyeZ);
        List<int[]> offsets = PrinterIteration.offsets((int) Math.ceil(range), ConfigurationHandler.printerIteratorShape,
            ConfigurationHandler.printerIteratorMode, ConfigurationHandler.printerXAxisReverse,
            ConfigurationHandler.printerYAxisReverse, ConfigurationHandler.printerZAxisReverse);
        long deadline = ConfigurationHandler.printerIterationTimeLimit > 0
            ? System.nanoTime() + ConfigurationHandler.printerIterationTimeLimit * 1_000_000L : Long.MAX_VALUE;
        AreaSelectionLibrary.Area area = "selection".equals(ConfigurationHandler.printSelectionType) ? AreaSelections.library().selected() : null;

        if (ManualMining.active()) {
            stopMining();
            return 0;
        }
        if (DestroyAssist.active() || continueMining(world, eyeX, eyeY, eyeZ, mineRange)) return 0;
        if (removeScaffold(world, eyeX, eyeY, eyeZ, mineRange)) return 0;
        if (removeIce(world, eyeX, eyeY, eyeZ, mineRange)) return 0;
        boolean layers = "layers".equals(ConfigurationHandler.printerBuildOrder);
        List<PrinterBuildOrder.Candidate> candidates = new ArrayList<>();
        BlockPos fill = null;
        ItemStack[] fillItems = null;
        boolean filling = ConfigurationHandler.printerFillContainers && this.minecraft.currentScreen == null;
        for (int[] offset : offsets) {
            if (System.nanoTime() > deadline) break;
            int x = bx + offset[0], y = by + offset[1], z = bz + offset[2];
            if (y < 0 || y > 255) continue;
            double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ;
            if (dx * dx + dy * dy + dz * dz > range * range) continue;
            if (!selected(x, y, z, feetY, area)) continue;
            long key = key(x, y, z);
            if (this.cooldowns.containsKey(key) || givenUp(key)) continue;
            SchematicWorld placement = placementAt(placements, x, y, z);
            if (placement == null) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (ManualMining.skipped(pos)) continue;
            IBlockState state = placement.getBlockState(new BlockPos(x - placement.position.x, y - placement.position.y, z - placement.position.z));
            IBlockState real = world.getBlockState(pos);
            if (PrinterStates.pistonBusy(state, real)) continue;
            if (PrinterStates.built(world, pos, state, real)) {
                if (filling && fill == null && pos.distanceSqToCenter(eyeX, eyeY, eyeZ) <= ContainerFiller.REACH * ContainerFiller.REACH) {
                    fillItems = this.filler.wanted(world, placement, new BlockPos(x - placement.position.x, y - placement.position.y, z - placement.position.z), pos);
                    if (fillItems != null) fill = pos;
                }
                continue;
            }
            candidates.add(new PrinterBuildOrder.Candidate(x, y, z, layers ? PrinterBuildOrder.tier(state) : 0, placement));
        }
        // a built filter container is filled before anything else is placed (printerFillContainers)
        if (fill != null && this.filler.start(this.minecraft, this, player, fill, fillItems)) return 0;
        if (layers) candidates.sort(PrinterBuildOrder.order(eyeX, eyeY, eyeZ, feetY, ConfigurationHandler.printerYAxisReverse));
        this.pass = new Pass(player);
        int placed = 0;
        // Fluids last: a source placed before the blocks around it are built floods the build (and washes away
        // tripwire, redstone and rails); it is placed only once nothing else in reach can be placed this pass.
        List<PrinterBuildOrder.Candidate> fluids = new ArrayList<>();
        for (java.util.Iterator<PrinterBuildOrder.Candidate> it = candidates.iterator(); it.hasNext();) {
            PrinterBuildOrder.Candidate candidate = it.next();
            SchematicWorld placement = (SchematicWorld) candidate.placement;
            IBlockState state = placement.getBlockState(new BlockPos(candidate.x - placement.position.x, candidate.y - placement.position.y,
                candidate.z - placement.position.z));
            if (FluidPrinter.isFluid(state.getBlock())) { fluids.add(candidate); it.remove(); }
        }
        boolean actedOnBlocks = false;
        int broken = 0;
        this.redstoneOrder.takeHeld();
        try {
            for (int round = 0; round < 2; round++) {
            if (round == 1) {
                // blocks held back for the redstone order are not flooded while they wait (RedstoneOrder.PATIENCE at most)
                if (actedOnBlocks || placed > 0 || this.redstoneOrder.takeHeld()) break;
                candidates = fluids;
            }
            for (PrinterBuildOrder.Candidate candidate : candidates) {
                if (System.nanoTime() > deadline) break;
                BlockPos pos = new BlockPos(candidate.x, candidate.y, candidate.z);
                long key = key(candidate.x, candidate.y, candidate.z);
                SchematicWorld placement = (SchematicWorld) candidate.placement;
                // blocks that break at once (creative, insta-mine) go on up to placeBlocksPerTick a pass; a block that
                // takes time is mined alone (the server tracks one dig per player)
                if (pos.distanceSqToCenter(eyeX, eyeY, eyeZ) <= mineRange * mineRange && startBreak(world, placement, pos)) {
                    this.cooldowns.put(key, this.tick + Math.max(1, ConfigurationHandler.timeout));
                    attempted(key);
                    actedOnBlocks = true;
                    if (this.mining != null || ++broken >= Math.max(1, ConfigurationHandler.placeBlocksPerTick)) break;
                    continue;
                }
                if (placeBlock(world, player, placement, pos)) {
                    PrinterHighlights.add(candidate.x, candidate.y, candidate.z, PrinterHighlights.Type.PLACE);
                    this.cooldowns.put(key, this.tick + Math.max(1, ConfigurationHandler.timeout));
                    if (ConfigurationHandler.printerRedstoneOrder) holdPulse(world, pos, placement);
                    attempted(key);
                    if (++placed >= Math.max(1, ConfigurationHandler.placeBlocksPerTick)) break;
                }
            }
            }
        } catch (RuntimeException e) {
            Reference.logger.error("Could not place block!", e);
        } finally {
            this.pass.restore();
            this.pass = null;
        }
        return placed;
    }

    /** Leaves the output side of observers that fire because of the block just placed alone until the pulse is over. */
    private void holdPulse(World world, BlockPos pos, SchematicWorld placement) {
        IBlockState placed = placement.getBlockState(new BlockPos(pos.getX() - placement.position.x, pos.getY() - placement.position.y, pos.getZ() - placement.position.z));
        for (BlockPos cell : RedstoneOrder.pulseCells(world, pos, placed)) {
            this.cooldowns.merge(key(cell.getX(), cell.getY(), cell.getZ()), this.tick + RedstoneOrder.PULSE_HOLD, Math::max);
        }
    }

    /** Counts an action at a position for the loop guard; older actions than the window are forgotten. */
    private void attempted(long key) {
        long[] entry = this.attempts.get(key);
        if (entry == null || this.tick - entry[1] > ATTEMPT_WINDOW) this.attempts.put(key, entry = new long[2]);
        entry[0]++;
        entry[1] = this.tick;
    }

    /** Whether the loop guard gave up on a position. */
    private boolean givenUp(long key) {
        long[] entry = this.attempts.get(key);
        if (entry == null) return false;
        if (this.tick - entry[1] > ATTEMPT_WINDOW) {
            this.attempts.remove(key);
            return false;
        }
        return entry[0] >= MAX_ATTEMPTS;
    }

    /** printSelectionType: render layers only, also inside the selected area, or below/above the player's feet. */
    private static boolean selected(int x, int y, int z, double feetY, AreaSelectionLibrary.Area area) {
        switch (ConfigurationHandler.printSelectionType) {
            case "selection":
                if (area == null) return false;
                for (AreaSelectionLibrary.Box box : area.boxes()) {
                    Vector3i a = box.first(), b = box.second();
                    if (x >= Math.min(a.x, b.x) && x <= Math.max(a.x, b.x) && y >= Math.min(a.y, b.y) && y <= Math.max(a.y, b.y)
                        && z >= Math.min(a.z, b.z) && z <= Math.max(a.z, b.z)) return true;
                }
                return false;
            case "below_player": return y <= MathHelper.floor(feetY);
            case "above_player": return y >= MathHelper.ceil(feetY);
            default: return true;
        }
    }

    static SchematicWorld placementAt(List<SchematicWorld> placements, int x, int y, int z) {
        for (SchematicWorld placement : placements) {
            if (placement.isBlockRendered(x - placement.position.x, y - placement.position.y, z - placement.position.z)) return placement;
        }
        return null;
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | y & 0xfffL; }

    /** The player state a pass changes (held slot, sneaking, reported rotation), restored when the pass ends. */
    private final class Pass {
        final EntityPlayerSP player;
        final int slot;
        final boolean sneaking;
        final float yaw, pitch;
        boolean sneakSent, lookSent;

        Pass(EntityPlayerSP player) {
            this.player = player; this.slot = player.inventory.currentItem; this.sneaking = player.isSneaking();
            this.yaw = player.rotationYaw; this.pitch = player.rotationPitch;
        }

        /** Sneaks for clicks on a neighbor so that containers and other interactive blocks are not opened. */
        void sneak() {
            if (sneakSent || player.isSneaking()) return;
            syncSneaking(player, true);
            sneakSent = true;
        }

        /** Clicks that change a placed block's state must not sneak. */
        void unsneak() {
            if (!player.isSneaking()) return;
            syncSneaking(player, false);
            sneakSent = true;
        }

        void look(PrinterLook look) {
            player.rotationYaw = look.yaw;
            player.rotationPitch = look.pitch;
            player.connection.sendPacket(new CPacketPlayer.Rotation(look.yaw, look.pitch, player.onGround));
            lookSent = true;
        }

        void restoreLook() {
            player.rotationYaw = yaw;
            player.rotationPitch = pitch;
        }

        void restore() {
            player.inventory.currentItem = mining != null && toolSlot >= 0 ? toolSlot : slot;
            restoreLook();
            if (lookSent) player.connection.sendPacket(new CPacketPlayer.Rotation(yaw, pitch, player.onGround));
            if (sneakSent) syncSneaking(player, sneaking);
        }
    }

    private boolean placeBlock(World world, EntityPlayerSP player, SchematicWorld schematic, BlockPos pos) {
        final BlockPos local = new BlockPos(pos.getX() - schematic.position.x, pos.getY() - schematic.position.y, pos.getZ() - schematic.position.z);
        final IBlockState state = schematic.getBlockState(local);
        final Block block = state.getBlock();
        final IBlockState real = world.getBlockState(pos);

        if (PrinterStates.pistonBusy(state, real) || PrinterStates.built(world, pos, state, real)) {
            return false;
        }

        int stateClicks = PrinterStates.poweredOpenable(world, pos, real) ? -1 : StateClicks.clicks(real, state);
        if (stateClicks > 0) return clickState(world, player, pos, stateClicks, StateClicks.look(state));
        if (stateClicks == 0) return false;
        int layers = SnowLayers.missing(real, state);
        if (layers > 0) return addSnow(player, schematic, local, pos, state, layers);

        if (FluidPrinter.isFluid(block)) {
            // only sources are placed; flowing fluid comes from them
            if (!FluidPrinter.source(schematic, local) || FluidPrinter.matches(schematic, local, world, pos)) return false;
            if (!PrinterStates.fluidSafe(world, pos, state.getMaterial())) return false;
            if (!world.isAirBlock(pos) && !FluidPrinter.isFluid(real.getBlock())) return false;
            if (FluidPrinter.isFluid(real.getBlock()) && !FluidPrinter.sameFluid(block, real.getBlock())) return false;
            boolean placed = FluidPrinter.place(this.minecraft, FluidPrinter.fluid(block), pos,
                container -> swapToItem(player.inventory, container, true, true));
            if (!placed && ConfigurationHandler.printerIceWater && FluidPrinter.fluid(block) == net.minecraftforge.fluids.FluidRegistry.WATER
                && placeIce(world, player, pos)) return true;
            if (!placed) missing(player, FluidPrinter.bucketOf(FluidPrinter.fluid(block)));
            return placed;
        }

        if (ConfigurationHandler.destroyBlocks && !world.isAirBlock(pos) && this.minecraft.playerController.isInCreativeMode()) {
            ManualMining.printerClick(() -> this.minecraft.playerController.clickBlock(pos, EnumFacing.UP));
            return !ConfigurationHandler.destroyInstantly;
        }

        if (block.isAir(state, schematic, local) || skipped(block) || PrinterStates.primes(world, pos, state)
            || PrinterStates.wireWaits(world, pos, schematic, local, state) || PrinterStates.sourceWaits(world, pos, schematic, local, state)) {
            return false;
        }
        if (ConfigurationHandler.printerRedstoneOrder && this.redstoneOrder.hold(world, pos, schematic, local, state, this.tick) != null) return false;
        ToolUse.Click toolClick = ToolUse.click(world, state, real, pos);
        if (toolClick != null) {
            if (ConfigurationHandler.printForcedSneak) this.pass.sneak();
            return ToolUse.use(this, world, player, state, toolClick);
        }
        MultiBlockPlacement.Kind multi = MultiBlockPlacement.kind(block);
        if (multi != null && MultiBlockPlacement.secondary(state)) return false;

        ItemStack itemStack = BlockToItemStack.getItemStack(player, state, schematic, local);
        if (itemStack.isEmpty()) {
            Reference.logger.debug("{} is missing a mapping!", BlockStates.name(block));
            return false;
        }
        if (isBlacklisted(block, itemStack)) {
            return false;
        }
        if (!hasItem(player.inventory, itemStack)) {
            missing(player, itemStack);
            PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.FAILED);
            return false;
        }

        if (multi != null) return placeMultiBlock(world, player, schematic, local, pos, state, itemStack, multi);

        boolean slab = completesSlab(state, real, itemStack);
        boolean doubleSlab = !slab && isDoubleSlab(block);
        if (!slab) {
            if (!real.getBlock().isReplaceable(world, pos)) return false;
            // Gravity blocks need ground below, attached blocks their support (Entropy5's block supports)
            if (ConfigurationHandler.printFallingBlockCheck && PrinterStates.fallsNow(world, pos, state)) return false;
            if (!doubleSlab && !block.canPlaceBlockAt(world, pos)) {
                if (!Scaffold.needed(world, schematic, local, pos, state)) return false;
                return AirPlacementClient.active() && AirPlacement.supports(state)
                    ? airPlace(player, pos, state, itemStack) : placeScaffold(world, player, pos.down());
            }
        }

        boolean accurate = AccuratePlacementClient.active(block);
        EnumFacing[] solidSides = PlacementSolver.solidSides(world, pos);
        Click click;
        PrinterLook look = null;
        Vec3d stand = null;
        if (slab) {
            click = slabClick(pos, real);
        } else {
            IBlockState wanted = doubleSlab ? singleSlab(itemStack) : state;
            PlacementSolver.Solution solved = wanted == null || !ConfigurationHandler.printerPlacementSolver ? null
                : PlacementSolver.solve(world, player, itemStack, wanted, pos, solidSides, ConfigurationHandler.placeInAir,
                    ConfigurationHandler.printerFakePosition);
            if (solved == PlacementSolver.REFUSE && !accurate) {
                PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.FAILED);
                return false;
            }
            if (solved != null && solved != PlacementSolver.REFUSE) {
                click = new Click(solved.pos, solved.side, solved.hit, solved.neighbor);
                look = solved.look;
                stand = solved.stand;
            } else {
                click = defaultClick(pos, solidSides);
            }
        }
        if (click == null || !swapToItem(player.inventory, itemStack)) {
            if (click != null) missing(player, itemStack);
            PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.FAILED);
            return false;
        }
        ItemStack held = player.getHeldItemMainhand();
        int extraClicks = doubleSlab ? 1 : 0;
        if (held.isEmpty() || !held.isItemEqual(itemStack)
            || !this.minecraft.playerController.isInCreativeMode() && held.getCount() <= extraClicks) {
            return false;
        }

        if (ConfigurationHandler.printForcedSneak || click.neighbor) this.pass.sneak();
        if (look != null) this.pass.look(look);
        if (accurate) AccuratePlacementClient.announce(pos, state);
        Vec3d standing = stand == null ? null : FakeStand.enter(player, stand);
        try {
            boolean success = placeBlock(player, click.pos, click.side, click.hit);
            for (int i = 0; success && i < extraClicks; i++) {
                // A double slab: the second half goes onto the single slab just placed
                IBlockState now = world.getBlockState(pos);
                if (!(now.getBlock() instanceof BlockSlab)) break;
                Click second = slabClick(pos, now);
                success = placeBlock(player, second.pos, second.side, second.hit);
            }
            return success;
        } finally {
            if (standing != null) FakeStand.leave(player, standing);
            if (look != null) this.pass.restoreLook();
        }
    }

    /** Adds the missing layers to placed snow, one click on its top each (as many as the held stack has). */
    private boolean addSnow(EntityPlayerSP player, SchematicWorld schematic, BlockPos local, BlockPos pos, IBlockState state, int layers) {
        ItemStack itemStack = BlockToItemStack.getItemStack(player, state, schematic, local);
        if (itemStack.isEmpty() || isBlacklisted(state.getBlock(), itemStack)) return false;
        if (!swapToItem(player.inventory, itemStack)) {
            missing(player, itemStack);
            PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.FAILED);
            return false;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(itemStack)) return false;
        if (!this.minecraft.playerController.isInCreativeMode()) layers = Math.min(layers, held.getCount());
        this.pass.sneak();
        boolean success = false;
        for (int i = 0; i < layers; i++) {
            IBlockState now = this.minecraft.world.getBlockState(pos);
            if (SnowLayers.missing(now, state) == 0) break;
            Click click = SnowLayers.click(pos, now);
            if (!placeBlock(player, click.pos, click.side, click.hit)) break;
            success = true;
        }
        return success;
    }

    /** An item the printer lacks: taken out of a carried shulker box on a Plus server, else listed as missing. */
    private static void missing(EntityPlayerSP player, ItemStack stack) {
        if (!ShulkerRestockClient.request(player, stack)) PrinterMissingMaterials.record(stack);
    }

    /** Asks the server to place the gate in the air (air placement); the block arrives with the server's update. */
    private boolean airPlace(EntityPlayerSP player, BlockPos pos, IBlockState state, ItemStack itemStack) {
        if (AirPlacementClient.waiting(pos)) return false;
        if (!swapToItem(player.inventory, itemStack)) {
            missing(player, itemStack);
            return false;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(itemStack)) return false;
        AirPlacementClient.send(player, pos, state);
        PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.PLACE);
        return true;
    }

    /** Places a scaffold block under a fence gate that needs one; the gate follows on a later pass. */
    private boolean placeScaffold(World world, EntityPlayerSP player, BlockPos below) {
        ItemStack stack = Scaffold.item(player.inventory, this.minecraft.playerController.isInCreativeMode());
        if (stack.isEmpty()) {
            PrinterMissingMaterials.record(new ItemStack(net.minecraft.init.Blocks.COBBLESTONE));
            return false;
        }
        Click click = defaultClick(below, PlacementSolver.solidSides(world, below));
        if (click == null || !swapToItem(player.inventory, stack)) return false;
        this.pass.sneak();
        if (!placeBlock(player, click.pos, click.side, click.hit)) return false;
        if (world != this.scaffoldWorld) {
            this.scaffolds.clear();
            this.scaffoldWorld = world;
        }
        this.scaffolds.add(below);
        PrinterHighlights.add(below.getX(), below.getY(), below.getZ(), PrinterHighlights.Type.PLACE);
        return true;
    }

    /** Mines one scaffold in reach whose gate stands; forgets scaffolds that are gone. True when mining started. */
    private boolean removeScaffold(World world, double eyeX, double eyeY, double eyeZ, double range) {
        if (world != this.scaffoldWorld || this.scaffolds.isEmpty()) return false;
        for (java.util.Iterator<BlockPos> it = this.scaffolds.iterator(); it.hasNext();) {
            BlockPos pos = it.next();
            if (!Scaffold.isMaterial(world.getBlockState(pos))) { it.remove(); continue; }
            if (!Scaffold.done(world, pos) || pos.distanceSqToCenter(eyeX, eyeY, eyeZ) > range * range) continue;
            if (beginMining(world, pos)) {
                if (!Scaffold.isMaterial(world.getBlockState(pos))) it.remove();
                return true;
            }
            return false;
        }
        return false;
    }

    /** Places ice where the schematic has a water source and no water bucket is carried; it is mined by removeIce. */
    private boolean placeIce(World world, EntityPlayerSP player, BlockPos pos) {
        if (!IceWater.works(world, pos, this.minecraft.playerController.isInCreativeMode())) return false;
        if (!world.getBlockState(pos).getBlock().isReplaceable(world, pos)) return false;
        ItemStack stack = IceWater.item(player.inventory);
        if (stack.isEmpty()) return false;
        Click click = defaultClick(pos, PlacementSolver.solidSides(world, pos));
        if (click == null || !swapToItem(player.inventory, stack)) return false;
        this.pass.sneak();
        if (!placeBlock(player, click.pos, click.side, click.hit)) return false;
        if (world != this.iceWorld) {
            this.ice.clear();
            this.iceWorld = world;
        }
        this.ice.add(pos);
        return true;
    }

    /**
     * Mines one placed ice block in reach (without Silk Touch), which leaves its water source; forgets ice that is
     * gone. Ice waits while the block below it would not keep the water. True when mining started.
     */
    private boolean removeIce(World world, double eyeX, double eyeY, double eyeZ, double range) {
        if (world != this.iceWorld || this.ice.isEmpty()) return false;
        for (java.util.Iterator<BlockPos> it = this.ice.iterator(); it.hasNext();) {
            BlockPos pos = it.next();
            if (!IceWater.isIce(world.getBlockState(pos))) { it.remove(); continue; }
            if (!IceWater.works(world, pos, this.minecraft.playerController.isInCreativeMode())
                || pos.distanceSqToCenter(eyeX, eyeY, eyeZ) > range * range) continue;
            if (beginMining(world, pos, true)) {
                // the client shows air until the server's water arrives: no new ice there meanwhile
                this.cooldowns.put(key(pos.getX(), pos.getY(), pos.getZ()), this.tick + Math.max(20, ConfigurationHandler.timeout));
                if (!IceWater.isIce(world.getBlockState(pos))) it.remove();
                return true;
            }
        }
        return false;
    }

    /** Clicks a placed block (without sneaking) to cycle its delay, mode or open state. */
    private boolean clickState(World world, EntityPlayerSP player, BlockPos pos, int clicks, PrinterLook look) {
        this.pass.unsneak();
        if (look != null) this.pass.look(look);
        try {
            Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            for (int i = 0; i < clicks; i++) {
                if (!placeBlock(player, pos, EnumFacing.UP, hit)) return i > 0;
            }
            return true;
        } finally {
            if (look != null) this.pass.restoreLook();
        }
    }

    /**
     * A door, bed or double plant: its first block is placed by clicking the top of the block below while facing the
     * schematic's direction; the second block comes with it. On a Plus server the door halves' hinge and open state
     * are sent as accurate placement intents.
     */
    private boolean placeMultiBlock(World world, EntityPlayerSP player, SchematicWorld schematic, BlockPos local, BlockPos pos,
                                    IBlockState state, ItemStack itemStack, MultiBlockPlacement.Kind kind) {
        BlockPos second = pos.offset(MultiBlockPlacement.secondDirection(kind, state));
        if (second.getY() > 255 || pos.getY() < 1) return false;
        if (kind == MultiBlockPlacement.Kind.BED) {
            if (!world.getBlockState(pos).getBlock().isReplaceable(world, pos) || !world.getBlockState(second).getBlock().isReplaceable(world, second)
                || !world.getBlockState(pos.down()).isTopSolid() || !world.getBlockState(second.down()).isTopSolid()) return false;
        } else if (!state.getBlock().canPlaceBlockAt(world, pos)) {
            return false;
        }
        IBlockState below = world.getBlockState(pos.down());
        if (below.getBlock().isAir(below, world, pos.down()) || below.getBlock().isReplaceable(world, pos.down())) return false;
        IBlockState secondState = schematic.getBlockState(local.offset(MultiBlockPlacement.secondDirection(kind, state)));
        EnumFacing facing = MultiBlockPlacement.wantedFacing(kind, state);
        PrinterLook look = facing == null ? null : MultiBlockPlacement.look(player.rotationYaw, facing);
        if (facing != null && look == null) return false;

        if (!swapToItem(player.inventory, itemStack)) {
            missing(player, itemStack);
            PrinterHighlights.add(pos.getX(), pos.getY(), pos.getZ(), PrinterHighlights.Type.FAILED);
            return false;
        }
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !held.isItemEqual(itemStack)) return false;
        this.pass.sneak();
        if (look != null) this.pass.look(look);
        if (kind == MultiBlockPlacement.Kind.DOOR && AccuratePlacementClient.active(state.getBlock())) {
            AccuratePlacementClient.announce(pos, state);
            if (secondState.getBlock() == state.getBlock() && MultiBlockPlacement.secondary(secondState)) AccuratePlacementClient.announce(second, secondState);
        }
        try {
            return placeBlock(player, pos.down(), EnumFacing.UP, new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        } finally {
            if (look != null) this.pass.restoreLook();
        }
    }

    private static boolean skipped(Block block) {
        if (ConfigurationHandler.printSkipList.length == 0) return false;
        String name = BlockStates.name(block);
        for (String entry : ConfigurationHandler.printSkipList) if (entry.trim().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** A placement click: on a neighbor's face, or at the target itself (the server places into a replaceable clicked block). */
    static final class Click {
        final BlockPos pos;
        final EnumFacing side;
        final Vec3d hit;
        final boolean neighbor;
        Click(BlockPos pos, EnumFacing side, Vec3d hit, boolean neighbor) {
            this.pos = pos; this.side = side; this.hit = hit; this.neighbor = neighbor;
        }
    }

    /** The click that turns a single slab into the double slab: on its top face for a bottom slab, its bottom face for a top slab. */
    static Click slabClick(BlockPos pos, IBlockState real) {
        boolean top = real.getProperties().containsKey(BlockSlab.HALF) && real.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP;
        return new Click(pos, top ? EnumFacing.DOWN : EnumFacing.UP, new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), false);
    }

    /** The first solid neighbor's face, else (placeInAir) the target itself. */
    private static Click defaultClick(BlockPos pos, EnumFacing[] solidSides) {
        if (solidSides.length > 0) {
            EnumFacing direction = solidSides[0];
            BlockPos clicked = pos.offset(direction);
            EnumFacing face = direction.getOpposite();
            return new Click(clicked, face, new Vec3d(clicked.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                clicked.getY() + 0.5 + face.getFrontOffsetY() * 0.5, clicked.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5), true);
        }
        if (!ConfigurationHandler.placeInAir) return null;
        return new Click(pos, EnumFacing.UP, new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), false);
    }

    static boolean isDoubleSlab(Block block) {
        return block instanceof BlockSlab && ((BlockSlab) block).isDouble();
    }

    /** The bottom single slab a slab item places, the first half of a double slab. */
    @SuppressWarnings("deprecation")
    static IBlockState singleSlab(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemBlock)) return null;
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        if (!(block instanceof BlockSlab) || ((BlockSlab) block).isDouble()) return null;
        return block.getStateFromMeta(stack.getItem().getMetadata(stack.getMetadata()));
    }

    /** A single slab in the world that the schematic's double slab of the same item completes. */
    static boolean completesSlab(IBlockState state, IBlockState real, ItemStack stack) {
        if (!isDoubleSlab(state.getBlock()) || !(real.getBlock() instanceof BlockSlab) || isDoubleSlab(real.getBlock()) || stack.isEmpty()) return false;
        return Item.getItemFromBlock(real.getBlock()) == stack.getItem() && real.getBlock().damageDropped(real) == stack.getMetadata();
    }

    boolean isBlacklisted(Block block, ItemStack itemStack) {
        if (block instanceof IFluidBlock || block instanceof BlockLiquid) {
            return true;
        }

        if (itemStack.getItem() instanceof ItemBucket) {
            return true;
        }

        return itemStack.getItem() == Items.SIGN;
    }

    /** Uses the held item on a block face like the player would; true when the server is asked to act. */
    boolean placeBlock(EntityPlayerSP player, BlockPos pos, EnumFacing side, Vec3d hitVec) {
        EnumActionResult result = PrinterRange.click(player, pos, side, hitVec);
        boolean success = result == EnumActionResult.SUCCESS;
        if (success) player.swingArm(EnumHand.MAIN_HAND);
        return success;
    }

    /** Sets the sneaking state on both sides: the client's movement input and the server's entity action. */
    void syncSneaking(EntityPlayerSP player, boolean isSneaking) {
        player.movementInput.sneak = isSneaking;
        player.setSneaking(isSneaking);
        player.connection.sendPacket(new CPacketEntityAction(player,
            isSneaking ? CPacketEntityAction.Action.START_SNEAKING : CPacketEntityAction.Action.STOP_SNEAKING));
    }

    private boolean hasItem(InventoryPlayer inventory, ItemStack itemStack) {
        return this.minecraft.playerController.isInCreativeMode() && !ConfigurationHandler.swapSlotsQueue.isEmpty()
            || getInventorySlotWithItem(inventory, itemStack, false) >= 0;
    }

    private boolean swapToItem(InventoryPlayer inventory, ItemStack itemStack) {
        return swapToItem(inventory, itemStack, true, false);
    }

    boolean swapToItem(InventoryPlayer inventory, ItemStack itemStack, boolean swapSlots, boolean matchNBT) {
        int slot = getInventorySlotWithItem(inventory, itemStack, matchNBT);

        if (this.minecraft.playerController.isInCreativeMode()
            && (slot < Constants.Inventory.InventoryOffset.HOTBAR
                || slot >= Constants.Inventory.InventoryOffset.HOTBAR + Constants.Inventory.Size.HOTBAR)
            && !ConfigurationHandler.swapSlotsQueue.isEmpty()) {
            inventory.currentItem = getNextSlot();
            inventory.setInventorySlotContents(inventory.currentItem, itemStack.copy());
            this.minecraft.playerController.sendSlotPacket(
                inventory.getStackInSlot(inventory.currentItem),
                Constants.Inventory.SlotOffset.HOTBAR + inventory.currentItem);
            return true;
        }

        if (slot >= Constants.Inventory.InventoryOffset.HOTBAR
            && slot < Constants.Inventory.InventoryOffset.HOTBAR + Constants.Inventory.Size.HOTBAR) {
            inventory.currentItem = slot;
            return true;
        } else if (swapSlots && slot >= Constants.Inventory.InventoryOffset.INVENTORY
            && slot < inventory.mainInventory.size()) {
            if (swapSlots(inventory, slot)) {
                return swapToItem(inventory, itemStack, false, matchNBT);
            }
        }
        return false;
    }

    /** Holds a stack the test accepts: from the hotbar, else moved there from the main inventory through the swap slots. */
    boolean swapToMatching(InventoryPlayer inventory, Predicate<ItemStack> wanted) {
        int hotbar = Constants.Inventory.InventoryOffset.HOTBAR, size = Constants.Inventory.Size.HOTBAR;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = hotbar; i < hotbar + size; i++) {
                if (wanted.test(inventory.mainInventory.get(i))) {
                    inventory.currentItem = i;
                    return true;
                }
            }
            if (pass > 0) return false;
            int found = -1;
            for (int i = Constants.Inventory.InventoryOffset.INVENTORY; i < inventory.mainInventory.size() && found < 0; i++) {
                if (wanted.test(inventory.mainInventory.get(i))) found = i;
            }
            if (found < 0 || !swapSlots(inventory, found)) return false;
        }
        return false;
    }

    private int getInventorySlotWithItem(final InventoryPlayer inventory, final ItemStack itemStack, boolean matchNBT) {
        for (int i = 0; i < inventory.mainInventory.size(); i++) {
            ItemStack stack = inventory.mainInventory.get(i);
            if (!stack.isEmpty() && stack.isItemEqual(itemStack) && (!matchNBT || ItemStack.areItemStackTagsEqual(stack, itemStack))) {
                return i;
            }
        }
        return -1;
    }

    private boolean swapSlots(InventoryPlayer inventory, int from) {
        if (!ConfigurationHandler.swapSlotsQueue.isEmpty()) {
            int slot = getNextSlot();

            return swapSlots(from, slot);
        }

        return false;
    }

    private int getNextSlot() {
        int slot = ConfigurationHandler.swapSlotsQueue.poll() % Constants.Inventory.Size.HOTBAR;
        ConfigurationHandler.swapSlotsQueue.offer(slot);
        return slot;
    }

    private boolean swapSlots(final int from, final int to) {
        return PickBlockSlots.swapToHotbar(this.minecraft, this.minecraft.player, from, to);
    }
}
