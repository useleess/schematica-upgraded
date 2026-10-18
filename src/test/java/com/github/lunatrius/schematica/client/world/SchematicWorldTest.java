package com.github.lunatrius.schematica.client.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class SchematicWorldTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    /** 3 wide (x), 2 high (y), 4 long (z): stone at the origin, an east facing stair at (2,0,0), a chest holding items at (0,1,3). */
    private static SchematicWorld sample() {
        Schematic schematic = new Schematic(SchematicWorld.DEFAULT_ICON, 3, 2, 4);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(2, 0, 0, Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST));
        schematic.setBlockState(0, 1, 3, Blocks.CHEST.getDefaultState());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "minecraft:chest");
        tag.setInteger("x", 0);
        tag.setInteger("y", 1);
        tag.setInteger("z", 3);
        NBTTagList items = new NBTTagList();
        NBTTagCompound stack = new NBTTagCompound();
        new ItemStack(Items.DIAMOND, 4).writeToNBT(stack);
        stack.setByte("Slot", (byte) 1);
        items.appendTag(stack);
        tag.setTag("Items", items);
        TileEntity tile = NBTHelper.readTileEntityFromCompound(tag);
        schematic.setTileEntity(tile.getPos(), tile);
        SchematicWorld world = new SchematicWorld(schematic, "sample.litematic");
        world.isRendering = true;
        return world;
    }

    @Test
    public void worldClockCanBeSetEveryFrame() {
        // The renderer syncs the clock of each placement with the real world on every frame.
        SchematicWorld world = sample();
        world.setWorldTime(6000L);
        world.getWorldInfo().setWorldTotalTime(20218081L);
        assertEquals(6000L, world.getWorldTime());
        assertEquals(20218081L, world.getTotalWorldTime());
    }

    @Test
    public void providersAreLinkedToTheirWorlds() {
        com.github.lunatrius.schematica.world.WorldDummy dummy = com.github.lunatrius.schematica.world.WorldDummy.instance();
        assertEquals(0L, dummy.getWorldTime());
        dummy.getBiome(new BlockPos(0, 64, 0));
    }

    @Test
    public void exposesTheSchematicAsAWorld() {
        SchematicWorld world = sample();
        assertEquals("sample", world.name);
        assertSame(Blocks.STONE, world.getBlockState(new BlockPos(0, 0, 0)).getBlock());
        assertTrue(world.isAirBlock(new BlockPos(1, 0, 0)));
        assertTrue(world.isAirBlock(new BlockPos(50, 0, 0)));
        assertTrue(world.isBlockLoaded(new BlockPos(2, 1, 3)));
        assertTrue(!world.isBlockLoaded(new BlockPos(3, 0, 0)));
        TileEntity chest = world.getTileEntity(new BlockPos(0, 1, 3));
        assertTrue(chest instanceof TileEntityChest);
        assertSame(world, chest.getWorld());
        assertEquals(Items.DIAMOND, ((TileEntityChest) chest).getStackInSlot(1).getItem());
    }

    @Test
    public void rotatingAroundYMovesBlocksStatesAndTileEntities() {
        SchematicWorld world = sample();
        world.position.set(100, 64, 100);
        world.rotate(EnumFacing.Axis.Y);
        // x/z swap: 3x2x4 becomes 4x2x3; (x, z) -> (l - 1 - z, x)
        assertEquals(4, world.getWidth());
        assertEquals(3, world.getLength());
        assertSame(Blocks.STONE, world.getBlockState(new BlockPos(3, 0, 0)).getBlock());
        IBlockState stairs = world.getBlockState(new BlockPos(3, 0, 2));
        assertSame(Blocks.OAK_STAIRS, stairs.getBlock());
        // east turned clockwise seen from above is south
        assertEquals(EnumFacing.SOUTH, stairs.getValue(BlockStairs.FACING));
        TileEntity chest = world.getTileEntity(new BlockPos(0, 1, 0));
        assertTrue(String.valueOf(chest), chest instanceof TileEntityChest);
        assertEquals(Items.DIAMOND, ((TileEntityChest) chest).getStackInSlot(1).getItem());
        assertNull(world.getTileEntity(new BlockPos(0, 1, 3)));
        assertEquals(1, world.rotationStateY);
        assertEquals("Y", world.transformOperations.get(0));
    }

    @Test
    public void fourTurnsAreTheIdentity() {
        SchematicWorld world = sample();
        for (int i = 0; i < 4; i++) world.rotate(EnumFacing.Axis.Y);
        assertEquals(3, world.getWidth());
        assertEquals(4, world.getLength());
        assertSame(Blocks.STONE, world.getBlockState(new BlockPos(0, 0, 0)).getBlock());
        assertEquals(EnumFacing.EAST, world.getBlockState(new BlockPos(2, 0, 0)).getValue(BlockStairs.FACING));
        assertTrue(world.getTileEntity(new BlockPos(0, 1, 3)) instanceof TileEntityChest);
        assertEquals(0, world.rotationStateY);
    }

    @Test
    public void mirroringKeepsTheSizeAndFlipsFacing() {
        SchematicWorld world = sample();
        world.flip(EnumFacing.Axis.X);
        assertEquals(3, world.getWidth());
        assertSame(Blocks.STONE, world.getBlockState(new BlockPos(2, 0, 0)).getBlock());
        assertEquals(EnumFacing.WEST, world.getBlockState(new BlockPos(0, 0, 0)).getValue(BlockStairs.FACING));
        assertTrue(world.getTileEntity(new BlockPos(2, 1, 3)) instanceof TileEntityChest);
        assertEquals(1, world.flipStateX);
    }

    @Test
    public void rotatingAroundXSwapsHeightAndLength() {
        SchematicWorld world = sample();
        world.rotate(EnumFacing.Axis.X);
        assertEquals(3, world.getWidth());
        assertEquals(4, world.getHeight());
        assertEquals(2, world.getLength());
        assertSame(Blocks.STONE.getClass(), world.getBlockState(new BlockPos(0, 0, 1)).getBlock().getClass());
    }

    @Test
    public void lockedPlacementsDoNotTransform() {
        SchematicWorld world = sample();
        world.setPlacementSettings(world.placementSettings().locked(true));
        world.rotate(EnumFacing.Axis.Y);
        assertEquals(3, world.getWidth());
        assertTrue(world.transformOperations.isEmpty());
    }

    @Test
    public void movingAndTheOrigin() {
        SchematicWorld world = sample();
        world.moveMinimumTo(10, 20, 30);
        assertEquals(10, world.position.x);
        assertEquals(20, world.position.y);
        assertEquals(30, world.position.z);
        int[] box = world.enclosingBox();
        assertEquals(12, box[3]);
        assertEquals(21, box[4]);
        assertEquals(33, box[5]);
        world.moveOriginTo(5, 6, 7);
        assertEquals(5, world.originPosition().x);
    }

    @Test
    public void rayTracesOnlyWhatIsRendered() {
        SchematicWorld world = sample();
        Vec3d start = new Vec3d(0.5, 0.5, -3);
        Vec3d end = new Vec3d(0.5, 0.5, 3);
        RayTraceResult hit = world.rayTraceRendered(start, end);
        assertNotNull(hit);
        assertEquals(new BlockPos(0, 0, 0), hit.getBlockPos());
        world.isRendering = false;
        assertNull(world.rayTraceRendered(start, end));
        world.isRendering = true;
        world.isRenderingLayer = true;
        world.renderingLayer = 1;
        // layer 0 is hidden, so the stone no longer blocks the ray
        RayTraceResult chestHit = world.rayTraceRendered(new Vec3d(0.5, 1.5, -3), new Vec3d(0.5, 1.5, 4));
        assertNotNull(chestHit);
        assertEquals(new BlockPos(0, 1, 3), chestHit.getBlockPos());
        assertNull(world.rayTraceRendered(start, end));
    }

    @Test
    public void rotation16FollowsTheOperation() {
        // facing south (0) turned clockwise about Y faces west (4); mirrored across x it stays south
        assertEquals(4, SchematicWorld_rotation16(0, 'Y'));
        assertEquals(0, SchematicWorld_rotation16(0, 'x'));
        assertEquals(12, SchematicWorld_rotation16(0, 'z') == 8 ? 12 : 0);
    }

    private static int SchematicWorld_rotation16(int rotation, char operation) {
        try {
            java.lang.reflect.Method method = SchematicWorld.class.getDeclaredMethod("rotation16", int.class, char.class);
            method.setAccessible(true);
            return (Integer) method.invoke(null, rotation, operation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
