package com.github.lunatrius.schematica.client.gui.material;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSlab;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.client.printer.MultiBlockPlacement;
import com.github.lunatrius.schematica.client.world.CellState;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.storage.Schematic;

/**
 * The material list's Replace: every block of a schematic that a row counts is swapped for another block. The new
 * block keeps the properties both blocks share (facing, half, axis...) as long as they don't change which item it is,
 * so the picked variant (wood type, colour) stays. Region payloads are changed together with the combined schematic.
 */
public final class SchematicBlockReplace {
    private SchematicBlockReplace() {}

    private static final class Container {
        final ISchematic schematic;
        final int ox, oy, oz;
        Container(ISchematic schematic, int ox, int oy, int oz) { this.schematic = schematic; this.ox = ox; this.oy = oy; this.oz = oz; }
    }

    /**
     * Swaps the blocks counted as from (and the second halves of those blocks, like door tops) for to.
     * keyWorld shows root, so blocks whose item depends on their tile entity resolve like in the list; returns the
     * number of changed blocks.
     */
    public static long replace(ISchematic root, World keyWorld, EntityPlayer player, MaterialItemKey from, IBlockState to) {
        return replace(root, keyWorld, player, from, to, false);
    }

    /**
     * anyOrigin: the row is a merged one (Merge sources), so blocks standing in for missing blocks are swapped too;
     * otherwise only the blocks of from's origin are.
     */
    public static long replace(ISchematic root, World keyWorld, EntityPlayer player, MaterialItemKey from, IBlockState to, boolean anyOrigin) {
        List<Container> containers = new ArrayList<>();
        containers.add(new Container(root, 0, 0, 0));
        for (SchematicRegion region : root.getRegions()) {
            ISchematic payload = root.getRegionSchematic(region.name);
            if (payload != null && payload != root) containers.add(new Container(payload, region.minX, region.minY, region.minZ));
        }
        // Which states are counted as the row: per state, or per position for blocks with a tile entity
        Map<IBlockState, Boolean> matches = new HashMap<>();
        Map<IBlockState, Boolean> tiles = new IdentityHashMap<>();
        Map<IBlockState, Boolean> secondary = new IdentityHashMap<>();
        Set<Block> blocks = new HashSet<>();
        for (Container container : containers) {
            SchematicSections sections = new SchematicSections(container.schematic);
            for (int index = 0; index < sections.count(); index++) {
                sections.visit(index, null, (x, y, z, state, origin) -> {
                    if (!anyOrigin && !Objects.equals(origin, from.origin())) return;
                    if (hasTile(tiles, state)) {
                        if (counted(keyWorld, player, from, state, container, x, y, z)) blocks.add(state.getBlock());
                    } else if (!matches.containsKey(state)) {
                        boolean counted = counted(keyWorld, player, from, state, container, x, y, z);
                        matches.put(state, counted);
                        if (counted) blocks.add(state.getBlock());
                        else secondary.put(state, noItem(keyWorld, player, state, container, x, y, z));
                    }
                });
            }
        }
        if (blocks.isEmpty()) return 0;
        Map<IBlockState, CellState> converted = new IdentityHashMap<>();
        long[] changed = new long[1];
        for (Container container : containers) {
            SchematicSections sections = new SchematicSections(container.schematic);
            for (int index = 0; index < sections.count(); index++) {
                sections.visit(index, null, (x, y, z, state, origin) -> {
                    // a row of blocks standing in for a missing block changes only those, and they keep their origin
                    if (!anyOrigin && !Objects.equals(origin, from.origin())) return;
                    boolean swap;
                    if (hasTile(tiles, state)) swap = counted(keyWorld, player, from, state, container, x, y, z);
                    else swap = matches.get(state) || secondary.get(state) && blocks.contains(state.getBlock());
                    if (!swap) return;
                    CellState next = converted.computeIfAbsent(state, ignored -> CellState.of(convert(state, to)));
                    if (next.state == state && next.tile == null) return;
                    next.write(container.schematic, x, y, z);
                    if (origin != null) Schematic.set(container.schematic, x, y, z, next.state, origin);
                    changed[0]++;
                });
            }
        }
        return changed[0];
    }

    private static boolean hasTile(Map<IBlockState, Boolean> tiles, IBlockState state) {
        Boolean tile = tiles.get(state);
        if (tile == null) {
            try { tile = state.getBlock().hasTileEntity(state); }
            catch (RuntimeException | LinkageError error) { tile = true; }
            tiles.put(state, tile);
        }
        return tile;
    }

    private static MaterialCache.BuildItems items(World keyWorld, EntityPlayer player, IBlockState state, Container container, int x, int y, int z) {
        try {
            return MaterialCache.INSTANCE.items(keyWorld, new BlockPos(container.ox + x, container.oy + y, container.oz + z), state, player);
        } catch (RuntimeException | LinkageError error) {
            Reference.logger.debug("Could not resolve the item of {}", state, error);
            return null;
        }
    }

    private static boolean counted(World keyWorld, EntityPlayer player, MaterialItemKey from, IBlockState state, Container container, int x, int y, int z) {
        MaterialCache.BuildItems items = items(keyWorld, player, state, container, x, y, z);
        return items != null && !items.isEmpty() && items.key(0).equals(from.plain());
    }

    /** A part placed together with another one (door top, bed head, tall plant top): counted as nothing itself. */
    private static boolean noItem(World keyWorld, EntityPlayer player, IBlockState state, Container container, int x, int y, int z) {
        if (MultiBlockPlacement.kind(state.getBlock()) != null && MultiBlockPlacement.secondary(state)) return true;
        MaterialCache.BuildItems items = items(keyWorld, player, state, container, x, y, z);
        return items != null && items.isEmpty();
    }

    /** The picked block with the properties of the old one it can take without becoming another item. */
    public static IBlockState convert(IBlockState from, IBlockState picked) {
        if (picked.getBlock() == Blocks.AIR) return picked;
        IBlockState target = picked;
        if (isDoubleSlab(from.getBlock()) && picked.getBlock() instanceof BlockSlab && !((BlockSlab) picked.getBlock()).isDouble()) {
            BlockSlab full = doubleOf((BlockSlab) picked.getBlock());
            if (full != null) target = copy(full.getDefaultState(), picked, -1);
        }
        int drop = damageDropped(target);
        target = copy(target, from, drop);
        // Back to the stored form (meta) of the state, as the schematic formats keep it
        try { target = target.getBlock().getStateFromMeta(target.getBlock().getMetaFromState(target)); }
        catch (RuntimeException ignored) {}
        return target;
    }

    /** Copies the properties of source the target has too; with drop >= 0 only those that keep that dropped damage. */
    private static IBlockState copy(IBlockState target, IBlockState source, int drop) {
        for (IProperty<?> property : source.getPropertyKeys()) {
            IProperty<?> own = property(target, property.getName());
            if (own == null) continue;
            IBlockState next = with(target, own, name(source, property));
            if (next == null || next == target) continue;
            if (drop >= 0 && damageDropped(next) != drop) continue;
            target = next;
        }
        return target;
    }

    private static int damageDropped(IBlockState state) {
        try { return state.getBlock().damageDropped(state); }
        catch (RuntimeException error) { return 0; }
    }

    private static IProperty<?> property(IBlockState state, String name) {
        for (IProperty<?> property : state.getPropertyKeys()) if (property.getName().equals(name)) return property;
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String name(IBlockState state, IProperty property) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState with(IBlockState state, IProperty property, String value) {
        com.google.common.base.Optional<?> parsed = property.parseValue(value);
        if (!parsed.isPresent() || !property.getAllowedValues().contains(parsed.get())) return null;
        try { return state.withProperty(property, (Comparable) parsed.get()); }
        catch (IllegalArgumentException error) { return null; }
    }

    private static boolean isDoubleSlab(Block block) {
        return block instanceof BlockSlab && ((BlockSlab) block).isDouble();
    }

    /** The double block of a slab, from the slab item's fields (no field names, so it works for any mod's slab). */
    static BlockSlab doubleOf(BlockSlab single) {
        Item item = Item.getItemFromBlock(single);
        if (!(item instanceof ItemSlab)) return null;
        for (Field field : ItemSlab.class.getDeclaredFields()) {
            if (!BlockSlab.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value instanceof BlockSlab && ((BlockSlab) value).isDouble()) return (BlockSlab) value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }
}
