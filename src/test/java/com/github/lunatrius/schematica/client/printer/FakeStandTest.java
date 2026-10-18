package com.github.lunatrius.schematica.client.printer;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.world.storage.Schematic;

import static org.junit.Assert.*;

public class FakeStandTest {
    private static final BlockPos TARGET = new BlockPos(4, 4, 4);

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static SchematicWorld world() { return new SchematicWorld(new Schematic(SchematicWorld.DEFAULT_ICON, 9, 9, 9)); }

    @Test public void zonesFollowGetDirectionFromEntityLiving() {
        assertEquals(FakeStand.Zone.ABOVE, FakeStand.zone(TARGET, 4.5, 5, 4.5, 1.62));
        assertEquals(FakeStand.Zone.ABOVE, FakeStand.zone(TARGET, 4.5, 5, 4.5, 1.54));
        assertEquals(FakeStand.Zone.BELOW, FakeStand.zone(TARGET, 5.5, 2, 3.5, 1.62));
        assertEquals(FakeStand.Zone.SIDE, FakeStand.zone(TARGET, 4.5, 3, 4.5, 1.62));
        assertEquals(FakeStand.Zone.SIDE, FakeStand.zone(TARGET, 6.5, 5, 4.5, 1.62));
    }

    @Test public void everySpotIsInItsZone() {
        for (FakeStand.Zone zone : FakeStand.Zone.values()) {
            for (Vec3d feet : FakeStand.spots(TARGET, zone)) {
                assertEquals(zone, FakeStand.zone(TARGET, feet.x, feet.y, feet.z, 1.62));
                assertEquals(zone, FakeStand.zone(TARGET, feet.x, feet.y, feet.z, 1.54));
                assertFalse(FakeStand.body(feet).shrink(0.001).intersects(new AxisAlignedBB(TARGET)));
            }
        }
    }

    @Test public void aFreeWayIsReachable() {
        assertTrue(FakeStand.reachable(world(), TARGET, new Vec3d(4.5, 4, 7.5), new Vec3d(4.5, 5, 5.5)));
    }

    @Test public void blocksOnTheWayAreNot() {
        SchematicWorld world = world();
        world.setBlockState(new BlockPos(4, 5, 7), Blocks.STONE.getDefaultState(), 3);
        assertFalse(FakeStand.reachable(world, TARGET, new Vec3d(4.5, 4, 7.5), new Vec3d(4.5, 5, 5.5)));
    }

    @Test public void theWayBackMayNotCrossThePlacedBlock() {
        // straight up past the target: the block placed there stops the move back
        assertFalse(FakeStand.reachable(world(), TARGET, new Vec3d(4.5, 1, 4.5), new Vec3d(4.5, 5, 4.5)));
    }

    @Test public void farPositionsAreRefused() {
        assertFalse(FakeStand.reachable(world(), TARGET, new Vec3d(4.5, 4, 4.5 + 9), new Vec3d(4.5, 5, 4.5)));
    }
}
