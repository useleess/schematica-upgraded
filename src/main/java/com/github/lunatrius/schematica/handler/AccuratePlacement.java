// SPDX-License-Identifier: LGPL-3.0-only
// Server side of the accurate placement protocol, the Plus counterpart of Litematica's easyPlaceProtocol v3, by HackerRouter, 2026.
package com.github.lunatrius.schematica.handler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockQuartz;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.github.lunatrius.schematica.network.message.MessagePlacementIntent;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

/**
 * Applies the state a Plus client asked for to the block its placement creates. As with Litematica's whitelisted
 * properties, only orientation-like properties (facing, slab half, axis, rail shape, repeater delay, comparator mode,
 * door hinge, open, sign rotation) may change; powered states, ages and contents stay as vanilla placed them, and the
 * block must drop the same items as before.
 */
public final class AccuratePlacement {
    public static final AccuratePlacement INSTANCE = new AccuratePlacement();
    static final long LIFETIME_NANOS = 2_000_000_000L;
    static final int MAX_PENDING = 64;
    static final Set<String> WHITELIST = new HashSet<>(Arrays.asList("facing", "half", "axis", "shape", "delay", "mode", "hinge", "open", "rotation"));

    private static final class Intent {
        final String state;
        final long time = System.nanoTime();

        Intent(MessagePlacementIntent message) { state = message.state; }
    }

    /** Intents arrive on the network thread ahead of the placements queued for the server thread, so several can wait. */
    private final Map<UUID, Map<Long, Intent>> intents = new ConcurrentHashMap<>();

    private AccuratePlacement() {}

    /** Whether the block has a property the protocol may set (quartz pillars: their axis variant). */
    public static boolean supports(Block block) {
        if (block == null) return false;
        if (block instanceof BlockQuartz) return true;
        for (IProperty<?> property : block.getBlockState().getProperties()) if (WHITELIST.contains(property.getName())) return true;
        return false;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    public void intent(EntityPlayerMP player, MessagePlacementIntent message) {
        if (player == null || !ConfigurationHandler.accuratePlacementEnabled) return;
        Map<Long, Intent> pending = intents.computeIfAbsent(player.getUniqueID(), id -> new LinkedHashMap<>());
        synchronized (pending) {
            long now = System.nanoTime();
            Iterator<Intent> iterator = pending.values().iterator();
            while (iterator.hasNext() && (now - iterator.next().time > LIFETIME_NANOS || pending.size() >= MAX_PENDING)) iterator.remove();
            long key = key(message.x, message.y, message.z);
            pending.remove(key);
            pending.put(key, new Intent(message));
        }
    }

    public void forget(EntityPlayer player) { intents.remove(player.getUniqueID()); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent event) {
        if (event.getWorld().isRemote || !(event.getPlayer() instanceof EntityPlayerMP) || event.isCanceled()) return;
        Map<Long, Intent> pending = intents.get(event.getPlayer().getUniqueID());
        if (pending == null) return;
        if (event instanceof BlockEvent.MultiPlaceEvent) {
            // Doors: both halves come from one item use; only door intents apply here
            if (!(event.getPlacedBlock().getBlock() instanceof BlockDoor)) return;
            for (BlockSnapshot snapshot : ((BlockEvent.MultiPlaceEvent) event).getReplacedBlockSnapshots()) {
                apply(event.getWorld(), pending, snapshot.getPos());
            }
        } else {
            apply(event.getWorld(), pending, event.getPos());
        }
    }

    private void apply(World world, Map<Long, Intent> pending, BlockPos pos) {
        Intent intent;
        synchronized (pending) {
            intent = pending.remove(key(pos.getX(), pos.getY(), pos.getZ()));
        }
        if (intent == null || !ConfigurationHandler.accuratePlacementEnabled || System.nanoTime() - intent.time > LIFETIME_NANOS) return;
        IBlockState current = world.getBlockState(pos);
        IBlockState desired = BlockStates.parse(intent.state);
        if (desired == null || desired.getBlock() != current.getBlock()) return;
        IBlockState next = merge(current, desired);
        if (next != null) world.setBlockState(pos, next, 2);
    }

    /**
     * The current state with the desired values of the whitelisted properties, or null when nothing changes or the
     * change is not allowed (it would drop other items).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static IBlockState merge(IBlockState current, IBlockState desired) {
        Block block = current.getBlock();
        IBlockState next = current;
        for (IProperty property : current.getPropertyKeys()) {
            boolean quartzAxis = block instanceof BlockQuartz && property == BlockQuartz.VARIANT;
            if (!WHITELIST.contains(property.getName()) && !quartzAxis) continue;
            Comparable value = desired.getValue(property);
            if (quartzAxis && !(pillar(value) && pillar(current.getValue(property)))) continue;
            next = next.withProperty(property, value);
        }
        if (next == current) return null;
        Random random = new Random(0);
        if (block.damageDropped(next) != block.damageDropped(current)) return null;
        if (block.getItemDropped(next, new Random(0), 0) != block.getItemDropped(current, random, 0)) return null;
        if (block.quantityDropped(next, 0, new Random(0)) != block.quantityDropped(current, 0, new Random(0))) return null;
        return next;
    }

    private static boolean pillar(Object variant) {
        return variant == BlockQuartz.EnumType.LINES_X || variant == BlockQuartz.EnumType.LINES_Y || variant == BlockQuartz.EnumType.LINES_Z;
    }
}
