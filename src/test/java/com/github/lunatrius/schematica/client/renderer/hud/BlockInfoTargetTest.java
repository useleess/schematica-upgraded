package com.github.lunatrius.schematica.client.renderer.hud;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import static org.junit.Assert.*;

import com.github.lunatrius.schematica.util.VoxelRayTrace;
import com.github.lunatrius.schematica.client.renderer.hud.BlockInfoTarget.State;

public class BlockInfoTargetTest {
    @Test public void comparesBlocksAtTheSamePositionAndGivesEqualHitsToTheSchematic() {
        FakeLayer schematic = new FakeLayer().block(3, 0, 0, "mod:cable", 0);
        FakeLayer client = new FakeLayer().block(3, 0, 0, "mod:cable", 0);
        BlockInfoTarget same = target(client, schematic);
        assertTrue(same.schematicHit);
        assertTrue(same.showSchematic());
        assertFalse(same.showComparison());
        client.block(3, 0, 0, "mod:cable", 8);
        BlockInfoTarget metadata = target(client, schematic);
        assertTrue(metadata.showComparison());
        assertEquals(8, metadata.client.metadata);
        client.block(3, 0, 0, "minecraft:stone", 0);
        assertTrue(target(client, schematic).showComparison());
        assertFalse(target(new FakeLayer(), schematic).showComparison());
    }

    @Test public void nearerRealBlocksOccludeSchematicsWithoutComparingDifferentPositions() {
        FakeLayer schematic = new FakeLayer().block(4, 0, 0, "mod:pipe", 2);
        FakeLayer client = new FakeLayer().block(2, 0, 0, "minecraft:stone", 0);
        BlockInfoTarget blocked = target(client, schematic);
        assertFalse(blocked.showSchematic());
        assertFalse(blocked.showComparison());
        assertNull(blocked.schematic);
        schematic.block(2, 0, 0, "mod:pipe", 2);
        schematic.inset = 0.4;
        BlockInfoTarget compared = target(client, schematic);
        assertFalse(compared.schematicHit);
        assertTrue(compared.showComparison());
        assertEquals("mod:pipe", compared.schematic.registryName);
    }

    @Test public void tracesAllPlacementsAndUsesWorldCoordinatesWithNegativeOrigins() {
        FakeLayer client = new FakeLayer().block(-97, 70, -400, "minecraft:stone", 0);
        FakeLayer far = new FakeLayer(-100, 70, -400).block(7, 0, 0, "mod:far", 0);
        FakeLayer near = new FakeLayer(-100, 70, -400).block(3, 0, 0, "mod:near", 6);
        Vec3d start = v(-99.5, 70.5, -399.5), end = v(-89.5, 70.5, -399.5);
        BlockInfoTarget result = BlockInfoTarget.trace(client, Arrays.asList(far, near), start, end, false);
        assertEquals("mod:near", result.schematic.registryName);
        assertEquals("minecraft:stone", result.client.registryName);
        assertTrue(result.showComparison());
        near.visible = false;
        result = BlockInfoTarget.trace(new FakeLayer(), Arrays.asList(far, near), start, end, false);
        assertEquals("mod:far", result.schematic.registryName);
        far.visible = false;
        assertNull(BlockInfoTarget.trace(new FakeLayer(), Arrays.asList(far, near), start, end, false));
    }

    @Test public void boundsReachHonorsFluidsAndDoesNotRequireASolidCollisionBox() {
        FakeLayer schematic = new FakeLayer().block(11, 0, 0, "minecraft:stone", 0);
        assertNull(target(new FakeLayer(), schematic));
        schematic.block(2, 0, 0, "mod:fluid", 7);
        schematic.fluid = true;
        assertNull(target(new FakeLayer(), schematic));
        BlockInfoTarget fluid = BlockInfoTarget.trace(new FakeLayer(), Arrays.asList(schematic), v(0.5, 0.5, 0.5), v(10.5, 0.5, 0.5), true);
        assertEquals(7, fluid.schematic.metadata);
        schematic.fluid = false;
        schematic.inset = 0.3;
        assertEquals("mod:fluid", target(new FakeLayer(), schematic).schematic.registryName);
    }

    private static BlockInfoTarget target(FakeLayer client, FakeLayer... schematics) {
        return BlockInfoTarget.trace(client, Arrays.asList(schematics), v(0.5, 0.5, 0.5), v(10.5, 0.5, 0.5), false);
    }

    private static Vec3d v(double x, double y, double z) { return new Vec3d(x, y, z); }

    private static class FakeLayer extends BlockInfoTarget.Layer {
        final Map<String, State> blocks = new HashMap<>();
        boolean visible = true, fluid;
        double inset;
        FakeLayer() { this(0, 0, 0); }
        FakeLayer(int x, int y, int z) { super(x, y, z); }
        FakeLayer block(int x, int y, int z, String name, int metadata) {
            blocks.put(x + "," + y + "," + z, new State(name, metadata));
            return this;
        }
        @Override public State state(int x, int y, int z) { return visible ? blocks.get(x + "," + y + "," + z) : null; }
        @Override public RayTraceResult trace(Vec3d start, Vec3d end, boolean fluids) {
            if (fluid && !fluids) return null;
            return VoxelRayTrace.trace(start, end, (x, y, z) -> {
                if (state(x, y, z) == null) return null;
                RayTraceResult shape = new AxisAlignedBB(x + inset, y + inset, z + inset, x + 1 - inset, y + 1 - inset, z + 1 - inset)
                    .calculateIntercept(start, end);
                return shape == null ? null : new RayTraceResult(shape.hitVec, shape.sideHit, new net.minecraft.util.math.BlockPos(x, y, z));
            });
        }
    }
}
