// SPDX-License-Identifier: LGPL-3.0-only
// Litematica MaterialListSchematic / MaterialListUtils.createMaterialListFor, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.reference.Reference;


/**
 * Counts the items of a schematic that is not placed; everything counts as missing, as upstream does. The blocks are
 * tallied per block state on a background thread (only the schematic's own storage is read there); the items of each
 * distinct state, and of each block with a tile entity, are then worked out on the client thread.
 */
public final class SchematicFileMaterialScan implements MaterialScanner {
    private static final long BUDGET_NANOS = 8_000_000L;

    private static final class Tally {
        final BlockPos first;
        final IBlockState state;
        final String origin;
        long count;
        Tally(BlockPos first, IBlockState state, String origin) { this.first = first; this.state = state; this.origin = origin; }
    }

    /** Tallies are per state, and per state and origin for blocks standing in for blocks this game does not have. */
    private static final class StateOrigin {
        final IBlockState state;
        final String origin;
        StateOrigin(IBlockState state, String origin) { this.state = state; this.origin = origin; }
        @Override public boolean equals(Object other) {
            return other instanceof StateOrigin && ((StateOrigin) other).state == state && ((StateOrigin) other).origin.equals(origin);
        }
        @Override public int hashCode() { return System.identityHashCode(state) * 31 + origin.hashCode(); }
    }

    private final SchematicWorld schematic;
    private final EntityPlayer player;
    private final List<SchematicRegion> regions;
    private final SchematicSections sections;
    private final Map<MaterialItemKey, long[]> counts = new LinkedHashMap<>();
    // written by the worker only, read on the client thread once tallied is set
    private final Map<Object, Tally> tallies = new LinkedHashMap<>();
    private final MaterialScan.OriginKeys originKeys = new MaterialScan.OriginKeys();
    private final Map<IBlockState, Boolean> tileStates = new IdentityHashMap<>();
    private final List<BlockPos> tiles = new ArrayList<>();
    private volatile int sectionsDone;
    private volatile boolean tallied;
    private volatile Throwable failure;
    private Thread worker;
    private Iterator<Tally> resolving;
    private int tileCursor;
    private boolean done;
    private long skipped;

    /** regions: the sub-regions to count, or all of the schematic when empty. */
    public SchematicFileMaterialScan(SchematicWorld schematic, Collection<SchematicRegion> regions, EntityPlayer player) {
        this.schematic = schematic;
        this.player = player;
        this.regions = new ArrayList<>(regions);
        sections = new SchematicSections(schematic.getSchematic());
    }

    @Override public boolean done() { return done; }

    @Override public int percent() {
        if (done) return 100;
        if (tallied) return 99;
        return sections.count() == 0 ? 0 : (int) (sectionsDone * 98L / sections.count());
    }

    @Override public int skipped() { return (int) Math.min(Integer.MAX_VALUE, skipped); }

    @Override
    public void step() {
        if (done) return;
        if (worker == null) {
            worker = new Thread(this::tally, "Schematica material list");
            worker.setDaemon(true);
            worker.start();
        }
        if (!tallied) return;
        if (failure != null) {
            Reference.logger.error("Could not count the schematic's materials", failure);
            skipped++;
            done = true;
            return;
        }
        if (resolving == null) resolving = tallies.values().iterator();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (resolving.hasNext()) {
            Tally tally = resolving.next();
            add(tally.first, tally.state, tally.count, tally.origin);
            if (System.nanoTime() >= deadline) return;
        }
        while (tileCursor < tiles.size()) {
            BlockPos pos = tiles.get(tileCursor++);
            add(pos, schematic.getBlockState(pos), 1,
                com.github.lunatrius.schematica.world.storage.Schematic.originOf(schematic.getSchematic(), pos.getX(), pos.getY(), pos.getZ()));
            if (System.nanoTime() >= deadline) return;
        }
        done = true;
    }

    /** Worker thread: counts every non-air block per state; blocks with a tile entity are kept by position. */
    private void tally() {
        try {
            for (int index = 0; index < sections.count(); index++) {
                sections.visit(index, null, (x, y, z, state, origin) -> {
                    if (!selected(x, y, z)) return;
                    if (hasTile(state)) { tiles.add(new BlockPos(x, y, z)); return; }
                    Object key = origin == null ? state : new StateOrigin(state, origin);
                    Tally tally = tallies.get(key);
                    if (tally == null) tallies.put(key, tally = new Tally(new BlockPos(x, y, z), state, origin));
                    tally.count++;
                });
                sectionsDone = index + 1;
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            tallied = true;
        }
    }

    private boolean hasTile(IBlockState state) {
        Boolean tile = tileStates.get(state);
        if (tile == null) {
            // like MaterialCache: a block whose tile check fails is resolved per position
            try { tile = state.getBlock().hasTileEntity(state); }
            catch (RuntimeException | LinkageError error) { tile = true; }
            tileStates.put(state, tile);
        }
        return tile;
    }

    private boolean selected(int x, int y, int z) {
        if (regions.isEmpty()) return true;
        for (SchematicRegion region : regions) if (region.contains(x, y, z)) return true;
        return false;
    }

    private void add(BlockPos pos, IBlockState state, long times, String origin) {
        try {
            if (state.getBlock().isAir(state, schematic, pos)) return;
            MaterialCache.BuildItems items = MaterialCache.INSTANCE.items(schematic, pos, state, player);
            if (items == null) { skipped += times; return; }
            for (int i = 0; i < items.size(); i++) {
                counts.computeIfAbsent(originKeys.key(items.key(i), origin), ignored -> new long[1])[0] += items.count(i) * times;
            }
        } catch (Exception e) {
            skipped += times;
            if (skipped <= 3) Reference.logger.debug("Could not count schematic material at {}", pos, e);
        }
    }

    @Override
    public List<MaterialListModel.Entry<MaterialItemKey>> result() {
        if (!done()) throw new IllegalStateException("Material scan is incomplete");
        List<MaterialListModel.Entry<MaterialItemKey>> result = new ArrayList<>();
        for (Map.Entry<MaterialItemKey, long[]> counted : counts.entrySet()) {
            ItemStack stack = counted.getKey().stack();
            String registry = String.valueOf(net.minecraft.item.Item.REGISTRY.getNameForObject(stack.getItem()));
            int total = (int) Math.min(Integer.MAX_VALUE, counted.getValue()[0]);
            result.add(new MaterialListModel.Entry<>(counted.getKey(), MaterialScan.name(counted.getKey()), registry, total, total, 0, 0));
        }
        MaterialScan.updateAvailable(result, player);
        return result;
    }
}
