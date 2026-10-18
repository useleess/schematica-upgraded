// Server paste placement callbacks and tile restoration checks, by HackerRouter, 2026.
package com.github.lunatrius.schematica.tool;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockLever;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.util.MessageException;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorldEditPlacementTest {
    private static IBlockState AIR, WALL;

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        AIR = Blocks.AIR.getDefaultState();
        WALL = Blocks.STONE.getDefaultState();
    }

    private static IBlockState chest(EnumFacing facing) {
        return Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, facing);
    }

    @Test public void chestFacingCallbackDoesNotDiscardSavedTileDataOrFinalUpdates() throws Exception {
        FakeWorld world = world();
        WorldEditJob job = paste(EnumFacing.NORTH);
        assertTrue(!job.step(world));
        assertEquals(chest(EnumFacing.SOUTH), world.stateAfterAdded);
        assertEquals("Saved chest", ((TileEntityChest) world.cell(4).tile).getName());
        assertEquals(chest(EnumFacing.NORTH), world.cell(4).state);
        assertEquals(1, job.blockCount);
        assertEquals(1, world.restorations);
        assertEquals(2, world.restoreFlags);
        assertEquals(new BlockPos(4, 70, 6), world.cell(4).tile.getPos());
        assertEquals(0, world.neighborUpdates);
        finish(job, world);
        assertEquals(1, world.neighborUpdates);
        assertEquals(1, world.blockUpdates);
    }

    @Test public void acceptedStateDoesNotRequireAnotherWrite() throws Exception {
        FakeWorld world = world();
        world.skipAdded = true;
        WorldEditJob job = paste(EnumFacing.NORTH);
        job.step(world);
        assertEquals(chest(EnumFacing.NORTH), world.cell(4).state);
        assertEquals("Saved chest", ((TileEntityChest) world.cell(4).tile).getName());
        assertEquals(0, world.restorations);
        assertEquals(1, job.blockCount);
    }

    @Test public void blockReplacedByAddedCallbackIsNotGivenChestStateOrTileData() throws Exception {
        FakeWorld world = world();
        world.replaceOnAdded = true;
        WorldEditJob job = paste(EnumFacing.NORTH);
        assertThrows(MessageException.class, () -> job.step(world));
        assertSame(WALL, world.cell(4).state);
        assertEquals(0, world.restorations);
        assertEquals(0, world.tileWrites);
        assertEquals(0, job.blockCount);
    }

    @Test public void blockReplacedDuringRestorationIsNotGivenChestTileData() throws Exception {
        FakeWorld world = world();
        world.replaceOnRestore = true;
        WorldEditJob job = paste(EnumFacing.NORTH);
        assertThrows(MessageException.class, () -> job.step(world));
        assertEquals(1, world.restorations);
        assertSame(WALL, world.cell(4).state);
        assertEquals(0, world.tileWrites);
        assertEquals(0, job.blockCount);
    }

    @Test public void rejectedRestorationDoesNotClaimOrApplyThePaste() throws Exception {
        FakeWorld world = world();
        world.rejectRestore = true;
        WorldEditJob job = paste(EnumFacing.NORTH);
        assertThrows(MessageException.class, () -> job.step(world));
        assertEquals(1, world.restorations);
        assertEquals(chest(EnumFacing.SOUTH), world.cell(4).state);
        assertEquals(0, world.tileWrites);
        assertEquals(0, job.blockCount);
    }

    @Test public void rejectedBlockWriteCannotFinishAsASuccessfulPaste() throws Exception {
        FakeWorld world = world();
        world.rejectPlacement = true;
        WorldEditJob job = paste(EnumFacing.NORTH);
        MessageException error = assertThrows(MessageException.class, () -> finish(job, world));
        assertEquals("schematica.message.edit.block_failed", error.key());
        assertSame(AIR, world.cell(4).state);
        assertEquals(0, job.blockCount);
        assertEquals(0, world.tileWrites);
    }

    @Test public void identicalOrdinaryBlocksDoNotWriteOrReportChanges() throws Exception {
        FakeWorld world = world();
        world.cell(4).state = WALL;
        WorldEditJob job = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.FILL, 4, 70, 6, 1, 1, 1, WALL, null);
        finish(job, world);
        assertEquals(0, job.blockCount);
        assertEquals(0, world.blockUpdates);
        assertEquals("schematica.message.edit.no_changes",
            ((net.minecraft.util.text.TextComponentTranslation) job.finishedMessage(true)).getKey());
    }

    @Test public void matchingChestStateStillRestoresSavedTileData() throws Exception {
        FakeWorld world = world();
        world.cell(4).state = chest(EnumFacing.NORTH);
        WorldEditJob job = paste(EnumFacing.NORTH);
        finish(job, world);
        assertEquals(1, job.blockCount);
        assertEquals("Saved chest", ((TileEntityChest) world.cell(4).tile).getName());
    }

    @Test public void partialPasteStopsAtTheRejectedCellWithoutCountingIt() throws Exception {
        FakeWorld world = world();
        WorldEditJob job = paste(EnumFacing.NORTH, 3, false);
        job.step(world);
        world.rejectPlacement = true;
        assertThrows(MessageException.class, () -> finish(job, world));
        assertEquals(1, job.blockCount);
        assertEquals(1, world.tileWrites);
        assertSame(AIR, world.cell(5).state);
        assertSame(AIR, world.cell(6).state);
    }

    @Test public void commandPasteSkipsMatchingCellsButStillSendsDifferentStates() throws Exception {
        FakeWorld world = world();
        world.cell(4).state = chest(EnumFacing.WEST);
        WorldEditJob same = paste(EnumFacing.WEST);
        assertNull(same.command(0, world));
        world.cell(4).state = chest(EnumFacing.SOUTH);
        assertTrue(same.command(0, world).endsWith(" minecraft:chest 4 replace"));
    }

    @Test public void bothChestHalvesAreRestoredBeforeAnyFinalNeighborNotification() throws Exception {
        FakeWorld world = doubleChestWorld();
        WorldEditJob job = paste(EnumFacing.NORTH, 2, false);
        job.step(world);
        TileEntity first = world.cell(4).tile;
        job.step(world);
        TileEntity second = world.cell(5).tile;
        // The second half's placement callback turned the first half around.
        assertEquals(chest(EnumFacing.SOUTH), world.cell(4).state);
        assertEquals(chest(EnumFacing.NORTH), world.cell(5).state);
        world.onNotify = () -> assertPair(world, EnumFacing.NORTH);
        finish(job, world);
        assertPair(world, EnumFacing.NORTH);
        assertSame(first, world.cell(4).tile);
        assertSame(second, world.cell(5).tile);
        assertEquals("Saved chest 1", ((TileEntityChest) first).getName());
        assertEquals("Saved chest 2", ((TileEntityChest) second).getName());
        assertEquals(2, world.tileWrites);
        assertEquals(2, world.neighborUpdates);
        assertEquals(2, job.blockCount);
    }

    @Test public void cancellationAndRegionMasksOnlyFinalizeAlreadyPlacedCells() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            FakeWorld world = doubleChestWorld();
            WorldEditJob job = paste(EnumFacing.NORTH, 3, false);
            if (!cancel) job.setRegions(Collections.singletonList(new SchematicRegion("Pair", 0, 0, 0, 1, 0, 0)));
            job.step(world);
            job.step(world);
            job.cancelled = cancel;
            world.onNotify = () -> assertPair(world, EnumFacing.NORTH);
            finish(job, world);
            assertPair(world, EnumFacing.NORTH);
            assertSame(AIR, world.cell(6).state);
            assertEquals(2, world.tileWrites);
            assertEquals(2, world.neighborUpdates);
            assertEquals(2, job.blockCount);
        }
    }

    @Test public void finalRestorationLeavesReplacedBlocksAloneAndContinuesUpdates() throws Exception {
        for (boolean replaceDuringRestore : new boolean[] {false, true}) {
            FakeWorld world = doubleChestWorld();
            WorldEditJob job = paste(EnumFacing.NORTH, 2, false);
            job.step(world);
            job.step(world);
            int before = world.restorations;
            if (replaceDuringRestore) world.replaceOnRestore = true;
            else world.cell(4).state = WALL;
            finish(job, world);
            assertSame(WALL, world.cell(4).state);
            assertEquals(before + (replaceDuringRestore ? 1 : 0), world.restorations);
            assertEquals(2, world.tileWrites);
            assertEquals(2, world.neighborUpdates);
            assertEquals(2, job.blockCount);
        }
    }

    @Test public void rejectedFinalRestorationDoesNotReportSuccess() throws Exception {
        FakeWorld world = doubleChestWorld();
        WorldEditJob job = paste(EnumFacing.NORTH, 2, false);
        job.step(world);
        job.step(world);
        world.rejectRestore = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> finish(job, world));
        assertTrue(failure.getMessage().contains("x=4, y=70, z=6"));
        assertEquals(chest(EnumFacing.SOUTH), world.cell(4).state);
        assertEquals(2, world.tileWrites);
        assertEquals(0, world.neighborUpdates);
        assertEquals(2, job.blockCount);
    }

    @Test public void finalChestPassDoesNotUndoAnotherBlocksNormalStateChange() throws Exception {
        FakeWorld world = world();
        IBlockState lever = Blocks.LEVER.getDefaultState().withProperty(BlockLever.FACING, BlockLever.EnumOrientation.UP_X);
        WorldEditJob job = pasteBesideChest(lever);
        for (int i = 0; i < 8 && job.blockCount < 2; i++) job.step(world);
        assertEquals(2, job.blockCount);
        world.cell(4).state = lever.withProperty(BlockLever.POWERED, true);
        int notifications = world.neighborUpdates;
        finish(job, world);
        assertEquals(lever.withProperty(BlockLever.POWERED, true), world.cell(4).state);
        assertEquals(notifications + 2, world.neighborUpdates);
        assertEquals(2, job.blockCount);
    }

    @Test public void silentPasteDoesNotAddStateWritesOrNeighborNotifications() throws Exception {
        FakeWorld world = world();
        world.chunk = new Chunk(world, 0, 0);
        WorldEditJob job = paste(EnumFacing.NORTH, 2, true);
        finish(job, world);
        assertEquals(chest(EnumFacing.NORTH), world.getBlockState(new BlockPos(4, 70, 6)));
        assertEquals(chest(EnumFacing.NORTH), world.getBlockState(new BlockPos(5, 70, 6)));
        assertEquals(2, world.tileWrites);
        assertEquals(0, world.restorations);
        assertEquals(0, world.neighborUpdates);
        assertEquals(0, world.blockUpdates);
        assertEquals(2, job.blockCount);
    }

    @Test public void updateProgressAccountsForBothPassesWithoutGoingBackwards() throws Exception {
        FakeWorld world = doubleChestWorld();
        WorldEditJob job = paste(EnumFacing.NORTH, 2, false);
        TaskRegistry registry = new TaskRegistry();
        TaskRegistry.Task task = registry.start(job.player, 0, job.taskKind(), TaskRegistry.Backend.SERVER, "");
        long completed = 0;
        boolean sawUpdates = false;
        while (!job.step(world)) {
            job.publishProgress(task);
            if (task.progress().stage == TaskRegistry.Stage.UPDATES) {
                sawUpdates = true;
                assertEquals(4, task.progress().total);
                assertTrue(task.progress().completed >= completed);
                completed = task.progress().completed;
            }
        }
        assertTrue(sawUpdates);
        assertEquals(4, completed);
        task.finish();
    }

    private static void assertPair(FakeWorld world, EnumFacing facing) {
        assertEquals(chest(facing), world.cell(4).state);
        assertEquals(chest(facing), world.cell(5).state);
    }

    private static void finish(WorldEditJob job, FakeWorld world) {
        for (int i = 0; i < 100; i++) if (job.step(world)) return;
        throw new AssertionError("Paste did not finish");
    }

    private static WorldEditJob paste(EnumFacing facing) {
        return paste(facing, 1, false);
    }

    /** A paste of the given block at x=4 and a south-facing chest beside it at x=5. */
    private static WorldEditJob pasteBesideChest(IBlockState state) {
        WorldEditJob job = paste(EnumFacing.SOUTH, 2, false);
        NBTTagCompound tag = job.write();
        tag.getTagList("palette", 8).appendTag(new NBTTagString(BlockStates.toString(state)));
        tag.setByteArray("cells", new byte[] {0, 1, 0, 0});
        return WorldEditJob.read(tag, job.player, 0);
    }

    private static WorldEditJob paste(EnumFacing facing, int width, boolean silent) {
        WorldEditJob job = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.PASTE, 4, 70, 6,
            width, 1, 1, null, null, silent, ReplaceBehavior.ALL);
        NBTTagCompound tag = job.write();
        NBTTagList palette = new NBTTagList();
        palette.appendTag(new NBTTagString(BlockStates.toString(chest(facing))));
        tag.setTag("palette", palette);
        tag.setByteArray("cells", new byte[width * 2]);
        NBTTagList tiles = new NBTTagList();
        for (int index = 0; index < width; index++) {
            NBTTagCompound chest = new NBTTagCompound();
            chest.setString("id", "minecraft:chest");
            chest.setString("CustomName", "Saved chest" + (width == 1 ? "" : " " + (index + 1)));
            NBTTagCompound tile = new NBTTagCompound();
            tile.setInteger("index", index);
            tile.setTag("data", chest);
            tiles.appendTag(tile);
        }
        tag.setTag("tiles", tiles);
        return WorldEditJob.read(tag, job.player, 0);
    }

    /** Walls north of x=4 and x=5: a chest pair there turns south when its second half is placed. */
    private static FakeWorld doubleChestWorld() throws Exception {
        // Skip WorldServer's constructor, which creates a live server and dimension.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        FakeWorld world = (FakeWorld) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), FakeWorld.class);
        world.cells = new HashMap<>();
        world.cell(4, 70, 5).state = WALL;
        world.cell(5, 70, 5).state = WALL;
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        return world;
    }

    /** A south-facing chest at x=3 with a wall north of it: a chest placed at x=4 joins it and is turned south. */
    private static FakeWorld world() throws Exception {
        FakeWorld world = doubleChestWorld();
        world.cell(3, 70, 5).state = WALL;
        world.cell(3).state = chest(EnumFacing.SOUTH);
        return world;
    }

    private static final class Cell {
        IBlockState state = AIR;
        TileEntity tile;
    }

    private static final class FakeWorld extends WorldServer {
        Map<BlockPos, Cell> cells;
        Chunk chunk;
        Runnable onNotify;
        IBlockState stateAfterAdded;
        int restorations, restoreFlags, tileWrites, neighborUpdates, blockUpdates;
        boolean adding, skipAdded, replaceOnAdded, replaceOnRestore, rejectRestore, rejectPlacement;

        private FakeWorld() { super(null, null, null, 0, null); }

        Cell cell(int x) { return cell(x, 70, 6); }
        Cell cell(int x, int y, int z) { return cells.computeIfAbsent(new BlockPos(x, y, z), ignored -> new Cell()); }
        Cell cell(BlockPos pos) { return cell(pos.getX(), pos.getY(), pos.getZ()); }

        @Override public IBlockState getBlockState(BlockPos pos) {
            if (chunk == null) return cell(pos).state;
            ExtendedBlockStorage section = chunk.getBlockStorageArray()[pos.getY() >> 4];
            return section == Chunk.NULL_BLOCK_STORAGE ? AIR : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        @Override public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) { return pos.getY() >= 0 && pos.getY() < 256; }
        @Override public Chunk getChunkFromBlockCoords(BlockPos pos) { return chunk; }
        @Override public void playSound(EntityPlayer player, BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {}
        @Override public boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
            Cell cell = cell(pos);
            if (adding) { cell.state = state; return true; }
            if (cell.state.getBlock() == state.getBlock()) {
                restorations++;
                restoreFlags = flags;
                if (rejectRestore) return false;
                cell.state = replaceOnRestore ? WALL : state;
                return true;
            }
            if (rejectPlacement) return false;
            cell.state = state;
            cell.tile = null;
            adding = true;
            try {
                if (!skipAdded) state.getBlock().onBlockAdded(this, pos, state);
            } finally {
                adding = false;
            }
            stateAfterAdded = cell.state;
            if (replaceOnAdded) cell.state = WALL;
            return true;
        }
        @Override public void removeTileEntity(BlockPos pos) { cell(pos).tile = null; }
        @Override public TileEntity getTileEntity(BlockPos pos) {
            return chunk == null ? cell(pos).tile : chunk.getTileEntityMap().get(pos);
        }
        @Override public void setTileEntity(BlockPos pos, TileEntity value) {
            if (chunk != null) chunk.getTileEntityMap().put(pos, value);
            else cell(pos).tile = value;
            tileWrites++;
        }
        @Override public void notifyBlockUpdate(BlockPos pos, IBlockState oldState, IBlockState newState, int flags) { blockUpdates++; }
        @Override public void notifyNeighborsOfStateChange(BlockPos pos, Block block, boolean updateObservers) {
            if (onNotify != null) onNotify.run();
            neighborUpdates++;
        }
    }
}
