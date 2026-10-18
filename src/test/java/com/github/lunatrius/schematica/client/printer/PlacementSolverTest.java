package com.github.lunatrius.schematica.client.printer;

import java.lang.reflect.Field;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockObserver;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

import static org.junit.Assert.*;

public class PlacementSolverTest {
    private static final BlockPos TARGET = new BlockPos(1, 1, 1);

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    /** A 3x3x3 world with stone under the target. */
    private static SchematicWorld world() {
        SchematicWorld world = new SchematicWorld(new Schematic(SchematicWorld.DEFAULT_ICON, 3, 3, 3));
        world.setBlockState(TARGET.down(), Blocks.STONE.getDefaultState(), 3);
        return world;
    }

    /** A player looking north, without running EntityPlayer's constructor (which needs a live world). */
    private static EntityPlayer player() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafe = unsafeClass.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        EntityPlayer player = (EntityPlayer) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), FakePlayer.class);
        Field inventory = EntityPlayer.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(player, new InventoryPlayer(player));
        Field capabilities = EntityPlayer.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(player, new PlayerCapabilities());
        player.rotationYaw = 180;
        player.posX = 1.5; player.posY = 2; player.posZ = 4.5;
        return player;
    }

    private static final class FakePlayer extends EntityPlayer {
        FakePlayer() { super(null, new GameProfile(null, "test")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isSneaking() { return false; }
        @Override public float getEyeHeight() { return 1.62f; }
    }

    private static PlacementSolver.Solution solve(IBlockState target, ItemStack stack, boolean inAir) throws Exception {
        return solve(target, stack, inAir, player());
    }

    private static PlacementSolver.Solution solve(IBlockState target, ItemStack stack, boolean inAir, EntityPlayer player) {
        SchematicWorld world = world();
        return PlacementSolver.solve(world, player, stack, target, TARGET, PlacementSolver.solidSides(world, TARGET), inAir, true);
    }

    /** The direction getDirectionFromEntityLiving gives with the player at the solution's position and rotation. */
    private static EnumFacing direction(PlacementSolver.Solution solution, EntityPlayer player) {
        if (solution.stand != null) { player.posX = solution.stand.x; player.posY = solution.stand.y; player.posZ = solution.stand.z; }
        if (solution.look != null) { player.rotationYaw = solution.look.yaw; player.rotationPitch = solution.look.pitch; }
        return EnumFacing.getDirectionFromEntityLiving(TARGET, player);
    }

    @Test public void onlyTheBlockBelowIsSolid() {
        assertArrayEquals(new EnumFacing[] {EnumFacing.DOWN}, PlacementSolver.solidSides(world(), TARGET));
    }

    @Test public void stairsTurnThePlayerToTheirFacing() throws Exception {
        IBlockState east = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST);
        PlacementSolver.Solution solution = solve(east, new ItemStack(Blocks.OAK_STAIRS), false);
        assertNotNull(solution);
        assertNotSame(PlacementSolver.REFUSE, solution);
        assertEquals(TARGET.down(), solution.pos);
        assertEquals(EnumFacing.UP, solution.side);
        assertTrue(solution.neighbor);
        assertNotNull(solution.look);
        assertEquals(EnumFacing.EAST, EnumFacing.fromAngle(solution.look.yaw));
    }

    @Test public void theCurrentRotationIsKeptWhenItFits() throws Exception {
        IBlockState north = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.NORTH);
        PlacementSolver.Solution solution = solve(north, new ItemStack(Blocks.OAK_STAIRS), false);
        assertNotNull(solution);
        assertNull(solution.look);
    }

    @Test public void upsideDownStairsNeedAClickInTheAir() throws Exception {
        IBlockState top = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP);
        assertSame(PlacementSolver.REFUSE, solve(top, new ItemStack(Blocks.OAK_STAIRS), false));
        PlacementSolver.Solution solution = solve(top, new ItemStack(Blocks.OAK_STAIRS), true);
        assertNotNull(solution);
        assertNotSame(PlacementSolver.REFUSE, solution);
        assertEquals(TARGET, solution.pos);
        assertFalse(solution.neighbor);
    }

    @Test public void logsTakeTheAxisOfTheClickedFace() throws Exception {
        IBlockState sideways = Blocks.LOG.getDefaultState().withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.X);
        assertSame(PlacementSolver.REFUSE, solve(sideways, new ItemStack(Blocks.LOG), false));
        PlacementSolver.Solution solution = solve(sideways, new ItemStack(Blocks.LOG), true);
        assertNotNull(solution);
        assertEquals(EnumFacing.Axis.X, solution.side.getAxis());
    }

    @Test public void blocksWithoutOrientationNeedNoDecision() throws Exception {
        PlacementSolver.Solution solution = solve(Blocks.STONE.getDefaultState(), new ItemStack(Blocks.STONE), false);
        assertNotNull(solution);
        assertNull(solution.look);
        assertEquals(EnumFacing.UP, solution.side);
    }

    @Test public void pistonsFacingUpGetAPositionAboveTheBlock() throws Exception {
        EntityPlayer player = player();
        IBlockState up = Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.UP);
        PlacementSolver.Solution solution = solve(up, new ItemStack(Blocks.PISTON), false, player);
        assertNotNull(solution);
        assertNotSame(PlacementSolver.REFUSE, solution);
        assertNotNull(solution.stand);
        // the player itself is put back
        assertEquals(4.5, player.posZ, 0);
        assertEquals(EnumFacing.UP, direction(solution, player));
    }

    @Test public void pistonsFacingDownGetAPositionBelowTheBlock() throws Exception {
        EntityPlayer player = player();
        IBlockState down = Blocks.STICKY_PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.DOWN);
        PlacementSolver.Solution solution = solve(down, new ItemStack(Blocks.STICKY_PISTON), false, player);
        assertNotNull(solution);
        assertNotNull(solution.stand);
        assertEquals(EnumFacing.DOWN, direction(solution, player));
    }

    @Test public void observersFacingUpGetAPositionBelowTheBlock() throws Exception {
        EntityPlayer player = player();
        IBlockState up = Blocks.OBSERVER.getDefaultState().withProperty(BlockObserver.FACING, EnumFacing.UP);
        PlacementSolver.Solution solution = solve(up, new ItemStack(Blocks.OBSERVER), false, player);
        assertNotNull(solution);
        assertNotNull(solution.stand);
        // an observer faces away from the player's direction
        assertEquals(EnumFacing.DOWN, direction(solution, player));
    }

    @Test public void sidewaysPistonsUnderThePlayerGetAPositionAside() throws Exception {
        EntityPlayer player = player();
        player.posZ = 1.5;
        IBlockState east = Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.EAST);
        PlacementSolver.Solution solution = solve(east, new ItemStack(Blocks.PISTON), false, player);
        assertNotNull(solution);
        assertNotNull(solution.stand);
        assertEquals(EnumFacing.EAST, direction(solution, player));
    }

    @Test public void sidewaysPistonsOnlyTurnThePlayer() throws Exception {
        IBlockState east = Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.EAST);
        PlacementSolver.Solution solution = solve(east, new ItemStack(Blocks.PISTON), false);
        assertNotNull(solution);
        assertNull(solution.stand);
        assertNotNull(solution.look);
    }

    @Test public void withoutFakePositionsUpFacingPistonsWait() throws Exception {
        IBlockState up = Blocks.PISTON.getDefaultState().withProperty(BlockPistonBase.FACING, EnumFacing.UP);
        SchematicWorld world = world();
        assertSame(PlacementSolver.REFUSE, PlacementSolver.solve(world, player(), new ItemStack(Blocks.PISTON), up, TARGET,
            PlacementSolver.solidSides(world, TARGET), false, false));
    }
}
