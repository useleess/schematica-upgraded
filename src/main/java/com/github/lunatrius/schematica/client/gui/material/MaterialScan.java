package com.github.lunatrius.schematica.client.gui.material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import com.github.lunatrius.schematica.util.BlockGroups;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.chunk.Chunk;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.reference.Reference;


public final class MaterialScan implements MaterialScanner {
    private static final long BUDGET_NANOS = 8_000_000L;
    /** stateItems markers: needs no item (air, door tops); items depend on the tile entity; no item to build it with. */
    private static final Object NONE = new Object(), PER_POSITION = new Object(), NO_ITEM = new Object();

    private final SchematicWorld schematic;
    private final WorldClient world;
    private final EntityPlayer player;

    private final Map<MaterialItemKey, int[]> counts = new LinkedHashMap<>();
    private final OriginKeys originKeys = new OriginKeys();
    private final Map<IBlockState, Object> stateItems = new IdentityHashMap<>();
    private final SchematicSections sections;
    private final int[] bounds;
    private final boolean empty;
    private final boolean ignoreState = com.github.lunatrius.schematica.handler.ConfigurationHandler.materialListIgnoreState;
    private final BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
    private int cursor;
    private int skipped;
    private Chunk chunk;

    public MaterialScan(SchematicWorld schematic, WorldClient world, EntityPlayer player, boolean renderLayers) {
        this.schematic = schematic;
        this.world = world;
        this.player = player;
        bounds = renderLayers ? schematic.renderBounds()
            : new int[] {0, 0, 0, schematic.getWidth(), schematic.getHeight(), schematic.getLength()};
        sections = new SchematicSections(schematic.getSchematic());
        empty = !schematic.isEnabled() || bounds[3] <= bounds[0] || bounds[4] <= bounds[1] || bounds[5] <= bounds[2];
    }

    public boolean done() { return empty || cursor >= sections.count(); }

    public int percent() { return done() ? 100 : (int) (cursor * 100L / sections.count()); }

    public int skipped() { return skipped; }

    public void step() {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (!done()) {
            sections.visit(cursor++, bounds, this::collect);
            if (System.nanoTime() >= deadline) break;
        }
        chunk = null;
    }

    private void collect(int x, int y, int z, IBlockState state, String origin) {
        try {
            MaterialCache.BuildItems items = items(x, y, z, state);
            if (items == null || items.isEmpty()) return;
            worldPos.setPos(schematic.position.x + x, schematic.position.y + y, schematic.position.z + z);
            int chunkX = worldPos.getX() >> 4, chunkZ = worldPos.getZ() >> 4;
            if (chunk == null || chunk.x != chunkX || chunk.z != chunkZ) chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
            boolean unknown = !verifiedChunk(chunk, worldPos.getY());
            IBlockState real = unknown ? null : chunk.getBlockState(worldPos);
            boolean missing = unknown || real.getBlock() != state.getBlock()
                || !ignoreState && !BlockGroups.sameStored(state, real) && !BlockGroups.tolerated(state, real);
            boolean mismatched = missing && !unknown && !real.getBlock().isAir(real, world, worldPos);
            for (int i = 0; i < items.size(); i++) {
                int amount = items.count(i);
                int missingAmount = missing ? amount : 0;
                int[] count = counts.computeIfAbsent(originKeys.key(items.key(i), origin), ignored -> new int[4]);
                count[0] += amount;
                count[1] += missingAmount;
                if (mismatched) count[2] += missingAmount;
                if (unknown) count[3] += amount;
            }
        } catch (Exception e) {
            skipped++;
            if (skipped <= 3) Reference.logger.debug("Could not count schematic material at {}, {}, {}", x, y, z, e);
        }
    }

    /** The items of a block (cached per state unless they depend on its tile entity); null when skipped or none. */
    private MaterialCache.BuildItems items(int x, int y, int z, IBlockState state) {
        Object known = stateItems.get(state);
        if (known == NONE) return null;
        if (known == NO_ITEM) { skipped++; return null; }
        if (known instanceof MaterialCache.BuildItems) return (MaterialCache.BuildItems) known;
        BlockPos local = new BlockPos(x, y, z);
        if (known == null) {
            if (state.getBlock().isAir(state, schematic, local)) { stateItems.put(state, NONE); return null; }
            boolean tile;
            try { tile = state.getBlock().hasTileEntity(state); }
            catch (RuntimeException | LinkageError error) { tile = true; }
            if (tile) stateItems.put(state, PER_POSITION);
        }
        MaterialCache.BuildItems items = MaterialCache.INSTANCE.items(schematic, local, state, player);
        if (known == null && stateItems.get(state) == null) stateItems.put(state, items == null ? NO_ITEM : items.isEmpty() ? NONE : items);
        if (items == null) skipped++;
        return items;
    }

    public List<MaterialListModel.Entry<MaterialItemKey>> result() {
        if (!done()) throw new IllegalStateException("Material scan is incomplete");
        List<MaterialListModel.Entry<MaterialItemKey>> result = new ArrayList<>();
        for (Map.Entry<MaterialItemKey, int[]> counted : counts.entrySet()) {
            MaterialItemKey key = counted.getKey();
            String registry = String.valueOf(net.minecraft.item.Item.REGISTRY.getNameForObject(key.stack().getItem()));
            int[] c = counted.getValue();
            result.add(new MaterialListModel.Entry<>(key, name(key), registry, c[0], c[1], c[2], c[3]));
        }
        updateAvailable(result, player);
        return result;
    }

    /** The row name of a key: the item's name, with the block it stands in for ("Stone (from Deepslate)"). */
    public static String name(MaterialItemKey key) {
        ItemStack stack = key.stack();
        String name;
        try { name = stack.getDisplayName(); }
        catch (Exception e) { name = String.valueOf(net.minecraft.item.Item.REGISTRY.getNameForObject(stack.getItem())); }
        if (key.origin() == null) return name;
        String origin = com.github.lunatrius.schematica.world.schematic.MissingBlockGuesses.displayName(key.origin());
        try { return com.github.lunatrius.schematica.client.gui.framework.UiTranslations.format("schematica.ui.material.from", name, origin); }
        catch (RuntimeException e) { return name + " (" + origin + ")"; }
    }

    /** Keys with an origin, made once per item and origin instead of for every block. */
    static final class OriginKeys {
        private final Map<String, Map<MaterialItemKey, MaterialItemKey>> keys = new HashMap<>();

        MaterialItemKey key(MaterialItemKey plain, String origin) {
            if (origin == null) return plain;
            return keys.computeIfAbsent(origin, ignored -> new IdentityHashMap<>()).computeIfAbsent(plain, key -> key.withOrigin(origin));
        }
    }

    static boolean verifiedChunk(Chunk chunk, int y) {
        return y >= 0 && y < 256 && chunk != null && chunk.isLoaded() && !chunk.isEmpty();
    }

    public static boolean updateAvailable(List<MaterialListModel.Entry<MaterialItemKey>> entries, EntityPlayer player) {
        Map<MaterialItemKey, Long> inventory = new HashMap<>();
        if (player != null) {
            for (ItemStack stack : player.inventory.mainInventory) {
                if (!stack.isEmpty()) {
                    MaterialItemKey key = new MaterialItemKey(stack);
                    inventory.put(key, inventory.getOrDefault(key, 0L) + stack.getCount());
                }
            }
        }
        boolean changed = false;
        for (MaterialListModel.Entry<MaterialItemKey> entry : entries) {
            long available = inventory.getOrDefault(entry.key.plain(), 0L);
            changed |= entry.available != available;
            entry.available = available;
        }
        return changed;
    }
}
