package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.annotation.Nullable;

import com.google.common.base.Predicate;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;

import com.github.lunatrius.schematica.world.storage.SaveHandlerSchematic;

/**
 * A server-side world without chunks for tests: blocks in a map, vanilla's setBlockState semantics (breakBlock,
 * onBlockAdded, tile entities, neighbour and observer notifications), scheduled block ticks, queued block events and
 * ticking tile entities, so that redstone, pistons, observers, tripwire and fluids behave as in a real world.
 */
final class SimWorld extends World {
    private final Map<BlockPos, IBlockState> blocks = new HashMap<>();
    private final Map<BlockPos, TileEntity> tiles = new HashMap<>();
    private final List<Object[]> scheduled = new ArrayList<>();
    private final List<Object[]> events = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    private final Random random = new Random(0);
    long time;
    int spawned;
    /** Observer pulses (an observer switching on) and piston extensions during the run. */
    int observerPulses, pistonExtends;
    /** Tick and position of each spawned entity. */
    final List<Object[]> spawns = new ArrayList<>();

    SimWorld() {
        super(new SaveHandlerSchematic(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.FLAT), "sim"),
            new WorldProviderSurface(), new Profiler(), false);
        this.provider.setWorld(this);
    }

    @Override protected IChunkProvider createChunkProvider() { return null; }
    @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return true; }
    @Override public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) { return pos.getY() >= 0 && pos.getY() < 256; }
    @Override public boolean isAreaLoaded(BlockPos from, BlockPos to, boolean allowEmpty) { return true; }
    @Override public void markChunkDirty(BlockPos pos, TileEntity unused) {}
    @Override public boolean checkLight(BlockPos pos) { return true; }
    @Override public int getLight(BlockPos pos) { return 15; }

    @Override public IBlockState getBlockState(BlockPos pos) {
        IBlockState state = blocks.get(pos);
        return state == null ? Blocks.AIR.getDefaultState() : state;
    }

    /** Chunk.setBlockState and World.markAndNotifyBlock without chunks. */
    @Override public boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
        if (pos.getY() < 0 || pos.getY() >= 256) return false;
        pos = pos.toImmutable();
        IBlockState old = getBlockState(pos);
        if (old == state) return false;
        Block oldBlock = old.getBlock(), block = state.getBlock();
        // Chunk.setBlockState stores the new state before breakBlock (a rail breaking itself would recurse otherwise)
        if (block == Blocks.AIR) blocks.remove(pos); else blocks.put(pos, state);
        if (oldBlock != block) oldBlock.breakBlock(this, pos, old);
        TileEntity te = tiles.get(pos);
        if (te != null && te.shouldRefresh(this, pos, old, state)) removeTileEntity(pos);
        if (oldBlock != block) block.onBlockAdded(this, pos, state);
        if (block.hasTileEntity(state) && getBlockState(pos).getBlock() == block && tiles.get(pos) == null) {
            TileEntity created = block.createTileEntity(this, state);
            if (created != null) setTileEntity(pos, created);
        }
        if ((flags & 1) != 0) {
            notifyNeighborsOfStateChange(pos, oldBlock, true);
            if (state.hasComparatorInputOverride()) updateComparatorOutputLevel(pos, block);
        }
        if ((flags & 16) == 0) updateObservingBlocksAt(pos, block);
        return true;
    }

    @Nullable @Override public TileEntity getTileEntity(BlockPos pos) { return tiles.get(pos); }

    @Override public void setTileEntity(BlockPos pos, @Nullable TileEntity tile) {
        pos = pos.toImmutable();
        if (tile == null) { removeTileEntity(pos); return; }
        TileEntity old = tiles.put(pos, tile);
        if (old != null && old != tile) old.invalidate();
        tile.setWorld(this);
        tile.setPos(pos);
        tile.validate();
    }

    @Override public void removeTileEntity(BlockPos pos) {
        TileEntity old = tiles.remove(pos);
        if (old != null) old.invalidate();
    }

    @Override public void scheduleUpdate(BlockPos pos, Block block, int delay) { updateBlockTick(pos, block, delay, 0); }

    @Override public void updateBlockTick(BlockPos pos, Block block, int delay, int priority) {
        if (isBlockTickPending(pos, block)) return;
        scheduled.add(new Object[] {pos.toImmutable(), block, time + Math.max(1, delay)});
    }

    @Override public void scheduleBlockUpdate(BlockPos pos, Block block, int delay, int priority) { updateBlockTick(pos, block, delay, priority); }

    @Override public boolean isBlockTickPending(BlockPos pos, Block block) {
        for (Object[] entry : scheduled) if (entry[0].equals(pos) && entry[1] == block) return true;
        return false;
    }

    /** World's default answers true, which stops observers from ever firing. */
    @Override public boolean isUpdateScheduled(BlockPos pos, Block block) { return isBlockTickPending(pos, block); }

    @Override public void addBlockEvent(BlockPos pos, Block block, int id, int param) {
        events.add(new Object[] {pos.toImmutable(), block, id, param});
    }

    @Override public boolean spawnEntity(Entity entity) {
        spawned++;
        spawns.add(new Object[] {time, new BlockPos(entity)});
        log.add("t" + time + " spawned " + entity.getClass().getSimpleName() + " at " + new BlockPos(entity));
        return true;
    }

    @Override public List<Entity> getEntitiesInAABBexcluding(@Nullable Entity except, AxisAlignedBB box, @Nullable Predicate<? super Entity> filter) {
        return new ArrayList<>();
    }

    @Override public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> type, AxisAlignedBB box, @Nullable Predicate<? super T> filter) {
        return new ArrayList<>();
    }

    /** One server tick: due block ticks, block events, ticking tile entities. */
    void step() {
        time++;
        this.worldInfo.setWorldTotalTime(time);
        List<Object[]> due = new ArrayList<>();
        for (Iterator<Object[]> it = scheduled.iterator(); it.hasNext();) {
            Object[] entry = it.next();
            if ((long) entry[2] <= time) { due.add(entry); it.remove(); }
        }
        for (Object[] entry : due) {
            BlockPos pos = (BlockPos) entry[0];
            IBlockState state = getBlockState(pos);
            if (state.getBlock() != entry[1]) continue;
            if (state.getBlock() instanceof net.minecraft.block.BlockObserver && !state.getValue(net.minecraft.block.BlockObserver.POWERED)) observerPulses++;
            state.getBlock().updateTick(this, pos, state, random);
        }
        for (int round = 0; round < 8 && !events.isEmpty(); round++) {
            List<Object[]> current = new ArrayList<>(events);
            events.clear();
            for (Object[] event : current) {
                BlockPos pos = (BlockPos) event[0];
                IBlockState state = getBlockState(pos);
                if (state.getBlock() != event[1]) continue;
                if (state.getBlock() instanceof net.minecraft.block.BlockPistonBase && (int) event[2] == 0) pistonExtends++;
                state.onBlockEventReceived(this, pos, (int) event[2], (int) event[3]);
            }
        }
        for (TileEntity tile : new ArrayList<>(tiles.values())) {
            if (tile instanceof ITickable && !tile.isInvalid()) ((ITickable) tile).update();
        }
    }

    boolean idle() { return scheduled.isEmpty() && events.isEmpty(); }

    Map<BlockPos, IBlockState> blocks() { return blocks; }
}
