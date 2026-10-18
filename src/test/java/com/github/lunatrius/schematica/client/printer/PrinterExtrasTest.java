package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Falling blocks wait for support (9.12), snow layers (9.14), filter containers (9.17). */
public class PrinterExtrasTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static final class Player extends net.minecraft.entity.player.EntityPlayer {
        Player(SimWorld world) { super(world, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "snow")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    @Test public void fallingBlocksWaitForSupport() {
        SimWorld world = new SimWorld();
        BlockPos pos = new BlockPos(0, 64, 0);
        assertTrue(PrinterStates.fallsNow(world, pos, Blocks.SAND.getDefaultState()));
        assertTrue(PrinterStates.fallsNow(world, pos, Blocks.DRAGON_EGG.getDefaultState()));
        assertTrue(PrinterStates.fallsNow(world, pos, Blocks.ANVIL.getDefaultState()));
        assertFalse(PrinterStates.fallsNow(world, pos, Blocks.STONE.getDefaultState()));
        world.setBlockState(pos.down(), Blocks.WATER.getDefaultState(), 2);
        assertTrue(PrinterStates.fallsNow(world, pos, Blocks.GRAVEL.getDefaultState()));
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 2);
        assertFalse(PrinterStates.fallsNow(world, pos, Blocks.GRAVEL.getDefaultState()));
        assertFalse(PrinterStates.fallsNow(world, pos, Blocks.DRAGON_EGG.getDefaultState()));
    }

    @Test public void iceLeavesWaterOnlyOverSolidOrLiquid() {
        SimWorld world = new SimWorld();
        BlockPos pos = new BlockPos(0, 64, 0);
        assertFalse(IceWater.works(world, pos, false));
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 2);
        assertTrue(IceWater.works(world, pos, false));
        assertFalse(IceWater.works(world, pos, true));
        world.setBlockState(pos.down(), Blocks.WATER.getDefaultState(), 2);
        assertTrue(IceWater.works(world, pos, false));
        // vanilla: ice harvested without Silk Touch over a solid block becomes a water source
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 2);
        world.setBlockState(pos, Blocks.ICE.getDefaultState(), 2);
        net.minecraft.entity.player.EntityPlayer player = new Player(world);
        Blocks.ICE.harvestBlock(world, player, pos, Blocks.ICE.getDefaultState(), null, ItemStack.EMPTY);
        assertSame(Blocks.FLOWING_WATER, world.getBlockState(pos).getBlock());
        assertTrue(FluidPrinter.source(world, pos));
    }

    @Test public void silkTouchToolsAreRecognised() {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        assertFalse(IceWater.silk(pick));
        assertFalse(IceWater.silk(ItemStack.EMPTY));
        pick.addEnchantment(net.minecraft.init.Enchantments.SILK_TOUCH, 1);
        assertTrue(IceWater.silk(pick));
    }

    @Test public void snowLayersAreAddedByClicks() {
        IBlockState one = Blocks.SNOW_LAYER.getDefaultState(), five = one.withProperty(BlockSnow.LAYERS, 5);
        assertEquals(4, SnowLayers.missing(one, five));
        assertEquals(0, SnowLayers.missing(five, one));
        assertEquals(0, SnowLayers.missing(Blocks.AIR.getDefaultState(), five));
        assertEquals(0, SnowLayers.missing(Blocks.STONE.getDefaultState(), Blocks.STONE.getDefaultState()));
        // the first layer counts as the right placement; the solver does not refuse it
        assertTrue(StateClicks.placedMatches(one, five));
        assertFalse(StateClicks.placedMatches(Blocks.SNOW.getDefaultState(), five));
    }

    @Test public void snowLayersAddedInTheSimulatedWorld() {
        SimWorld world = new SimWorld();
        BlockPos pos = new BlockPos(0, 64, 0);
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState(), 2);
        world.setBlockState(pos, Blocks.SNOW_LAYER.getDefaultState(), 2);
        net.minecraft.entity.player.EntityPlayer player = new Player(world);
        player.setPosition(0.5, 66, 2.5);
        player.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, new ItemStack(Blocks.SNOW_LAYER, 8));
        SchematicPrinter.Click click = SnowLayers.click(pos, world.getBlockState(pos));
        player.getHeldItemMainhand().onItemUse(player, world, click.pos, net.minecraft.util.EnumHand.MAIN_HAND, click.side,
            (float) (click.hit.x - pos.getX()), (float) (click.hit.y - pos.getY()), (float) (click.hit.z - pos.getZ()));
        assertEquals(2, (int) world.getBlockState(pos).getValue(BlockSnow.LAYERS));
    }

    @Test public void filterItemsMatchByItemAndDamage() {
        ItemStack named = new ItemStack(Items.DYE, 1, 4).setStackDisplayName("filter");
        assertTrue(ContainerFiller.same(named, new ItemStack(Items.DYE, 64, 4)));
        assertFalse(ContainerFiller.same(named, new ItemStack(Items.DYE, 1, 3)));
        assertFalse(ContainerFiller.same(ItemStack.EMPTY, named));
        assertTrue(ContainerFiller.fillable(Blocks.HOPPER));
        assertTrue(ContainerFiller.fillable(Blocks.DROPPER));
        assertTrue(ContainerFiller.fillable(Blocks.DISPENSER));
        assertFalse(ContainerFiller.fillable(Blocks.CHEST));
    }
}
