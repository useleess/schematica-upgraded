package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;

import org.junit.BeforeClass;
import org.junit.Test;

import com.mojang.authlib.GameProfile;

import com.github.lunatrius.schematica.handler.AirPlacement;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AirPlacementTest {
    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        // ConfigurationHandler's defaults need a proxy (forkEvery = 1 gives each test class its own JVM)
        if (com.github.lunatrius.schematica.SchematicaPlus.proxy == null) {
            com.github.lunatrius.schematica.SchematicaPlus.proxy = new com.github.lunatrius.schematica.proxy.ServerProxy() {
                @Override public java.io.File getDataDirectory() { return new java.io.File("build/tmp"); }
            };
        }
    }

    private static final class Player extends EntityPlayer {
        Player(SimWorld world) { super(world, new GameProfile(java.util.UUID.randomUUID(), "air")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    private static EntityPlayer player(SimWorld world, ItemStack held) {
        EntityPlayer player = new Player(world);
        player.setPosition(0.5, 64, 0.5);
        player.setHeldItem(EnumHand.MAIN_HAND, held);
        return player;
    }

    private static String gate(EnumFacing facing, boolean open) {
        return BlockStates.toString(Blocks.OAK_FENCE_GATE.getDefaultState()
            .withProperty(BlockFenceGate.FACING, facing).withProperty(BlockFenceGate.OPEN, open));
    }

    @Test public void placesAGateInTheAirWithTheWantedFacing() {
        SimWorld world = new SimWorld();
        EntityPlayer player = player(world, new ItemStack(Blocks.OAK_FENCE_GATE, 2));
        BlockPos pos = new BlockPos(2, 66, 0);
        assertTrue(AirPlacement.place(player, pos, gate(EnumFacing.EAST, true)));
        IBlockState placed = world.getBlockState(pos);
        assertEquals(Blocks.OAK_FENCE_GATE, placed.getBlock());
        assertEquals(EnumFacing.EAST, placed.getValue(BlockFenceGate.FACING));
        assertTrue(placed.getValue(BlockFenceGate.OPEN));
        assertEquals(1, player.getHeldItemMainhand().getCount());
    }

    @Test public void refusesWhatANormalPlacementCouldNot() {
        SimWorld world = new SimWorld();
        EntityPlayer player = player(world, new ItemStack(Blocks.OAK_FENCE_GATE));
        // out of reach
        assertFalse(AirPlacement.place(player, new BlockPos(20, 66, 0), gate(EnumFacing.EAST, false)));
        // not a gate
        assertFalse(AirPlacement.place(player, new BlockPos(2, 66, 0), BlockStates.toString(Blocks.STONE.getDefaultState())));
        // occupied
        world.setBlockState(new BlockPos(2, 66, 0), Blocks.STONE.getDefaultState());
        assertFalse(AirPlacement.place(player, new BlockPos(2, 66, 0), gate(EnumFacing.EAST, false)));
        // another gate type than the held one
        assertFalse(AirPlacement.place(player, new BlockPos(2, 67, 0), BlockStates.toString(Blocks.BIRCH_FENCE_GATE.getDefaultState())));
        // disabled by the server
        ConfigurationHandler.airPlacementEnabled = false;
        try {
            assertFalse(AirPlacement.place(player, new BlockPos(2, 67, 0), gate(EnumFacing.EAST, false)));
        } finally {
            ConfigurationHandler.airPlacementEnabled = true;
        }
        assertTrue(AirPlacement.place(player, new BlockPos(2, 67, 0), gate(EnumFacing.EAST, false)));
        assertTrue(player.getHeldItemMainhand().isEmpty());
    }
}
