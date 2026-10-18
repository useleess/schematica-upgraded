package com.github.lunatrius.schematica.client.renderer.hud;

import java.util.Collections;
import java.util.List;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

public final class BlockInfoTarget {
    public abstract static class Layer {
        public final int x, y, z;
        protected Layer(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public abstract RayTraceResult trace(Vec3d start, Vec3d end, boolean fluids);
        public abstract State state(int x, int y, int z);
        private State at(int wx, int wy, int wz) { return state(wx - x, wy - y, wz - z); }
        /** The world this layer reads, for inventory previews; null when it has none. */
        public net.minecraft.world.World world() { return null; }
    }

    public static final class State {
        public final String registryName;
        public final int metadata;
        public State(String registryName, int metadata) { this.registryName = registryName; this.metadata = metadata; }
        boolean matches(State other) { return other != null && registryName.equals(other.registryName) && metadata == other.metadata; }
    }

    public final State schematic, client;
    public final boolean schematicHit;
    /** The targeted world position and the schematic layer holding the expected block there (or null). */
    public final int x, y, z;
    public final Layer schematicLayer;

    private BlockInfoTarget(State schematic, State client, boolean schematicHit, int x, int y, int z, Layer schematicLayer) {
        this.schematic = schematic;
        this.client = client;
        this.schematicHit = schematicHit;
        this.x = x; this.y = y; this.z = z;
        this.schematicLayer = schematicLayer;
    }

    public boolean showComparison() { return schematic != null && client != null && !schematic.matches(client); }
    public boolean showSchematic() { return schematic != null && (schematicHit || showComparison()); }

    public static BlockInfoTarget trace(Layer client, List<? extends Layer> schematics, Vec3d start, Vec3d end, boolean fluids) {
        Layer closestLayer = null;
        RayTraceResult closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (List<? extends Layer> layers : java.util.Arrays.asList(schematics, Collections.singletonList(client))) {
            for (Layer layer : layers) {
                RayTraceResult hit = layer.trace(start.addVector(-layer.x, -layer.y, -layer.z), end.addVector(-layer.x, -layer.y, -layer.z), fluids);
                if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || hit.hitVec == null) continue;
                double d = start.squareDistanceTo(hit.hitVec.addVector(layer.x, layer.y, layer.z));
                if (d < distance && d <= start.squareDistanceTo(end) + 1e-8) { closest = hit; closestLayer = layer; distance = d; }
            }
        }
        if (closest == null) return null;
        int x = closest.getBlockPos().getX() + closestLayer.x, y = closest.getBlockPos().getY() + closestLayer.y, z = closest.getBlockPos().getZ() + closestLayer.z;
        boolean schematicHit = closestLayer != client;
        State expected = schematicHit ? closestLayer.at(x, y, z) : null;
        Layer expectedLayer = schematicHit ? closestLayer : null;
        if (!schematicHit) for (Layer layer : schematics) {
            expected = layer.at(x, y, z);
            if (expected != null) { expectedLayer = layer; break; }
        }
        return new BlockInfoTarget(expected, client.at(x, y, z), schematicHit, x, y, z, expectedLayer);
    }
}
