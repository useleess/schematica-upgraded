package com.github.lunatrius.schematica.client.gui.load;

import static com.github.lunatrius.schematica.client.util.WorldServerName.worldServerName;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.client.world.SchematicLibrary;
import com.github.lunatrius.schematica.client.world.SchematicSourceData;
import com.github.lunatrius.schematica.handler.client.WorldHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.util.Coordinates;

public final class SchematicGuiLoader {

    private SchematicGuiLoader() {}

    public static SchematicLibrary.Source<SchematicSourceData> load(Minecraft minecraft, File file, boolean createPlacement) throws IOException {
        if (minecraft.world == null || minecraft.player == null || !SchematicaPlus.proxy.isLoadEnabled) {
            throw new IOException("Schematic loading is unavailable in this world");
        }
        SchematicLibrary.Source<SchematicSourceData> source = ClientProxy.loadSource(file);
        if (createPlacement) createPlacement(minecraft, source, true);
        WorldHandler.INSTANCE.saveSession();
        return source;
    }

    /** load() with the file read on a worker thread; done or failed runs on the client thread afterwards. */
    public static boolean loadAsync(Minecraft minecraft, File file, boolean createPlacement,
        java.util.function.Consumer<SchematicLibrary.Source<SchematicSourceData>> done, java.util.function.Consumer<Exception> failed) throws IOException {
        if (minecraft.world == null || minecraft.player == null || !SchematicaPlus.proxy.isLoadEnabled) {
            throw new IOException("Schematic loading is unavailable in this world");
        }
        return ClientProxy.loadSourceAsync(file, source -> {
            try {
                if (createPlacement) createPlacement(minecraft, source, true);
                WorldHandler.INSTANCE.saveSession();
                done.accept(source);
            } catch (IOException | RuntimeException e) {
                failed.accept(e);
            }
        }, failed);
    }

    public static SchematicWorld createPlacement(Minecraft minecraft, SchematicLibrary.Source<SchematicSourceData> source,
        boolean restoreCoordinates) throws IOException {
        if (minecraft.world == null || minecraft.player == null || !SchematicaPlus.proxy.isLoadEnabled) {
            throw new IOException("Schematic loading is unavailable in this world");
        }
        SchematicWorld previous = ClientProxy.schematic;
        SchematicWorld schematic = ClientProxy.createPlacement(source);
        try {
            Coordinates coord = restoreCoordinates ? ClientProxy.getCoordinates(worldServerName(minecraft), schematic.name) : null;
            if (!restoreCoordinates || coord == null) {
                // A new placement starts with its origin on the player, as in Litematica
                ClientProxy.moveSchematicToPlayer(schematic);
            } else {
                EnumFacing.Axis[] axes = {EnumFacing.Axis.X, EnumFacing.Axis.Y, EnumFacing.Axis.Z};
                int[] rotations = {coord.rotX, coord.rotY, coord.rotZ};
                int[] flips = {coord.flipX, coord.flipY, coord.flipZ};
                for (int axis = 0; axis < axes.length; axis++) {
                    for (int i = 0; i < Math.floorMod(rotations[axis], 4); i++) schematic.rotate(axes[axis]);
                }
                for (int axis = 0; axis < axes.length; axis++) {
                    if (Math.floorMod(flips[axis], 2) != 0) schematic.flip(axes[axis]);
                }
                ClientProxy.moveSchematic(schematic, coord.posX, coord.posY, coord.posZ);
            }
            if (net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) schematic.setPlacementSettings(schematic.placementSettings().enabled(false));
            ClientProxy.selectSchematic(schematic);
            ClientProxy.placementChanged();
            WorldHandler.INSTANCE.saveSession();
            return schematic;
        } catch (RuntimeException e) {
            ClientProxy.removePlacement(schematic);
            if (previous != null && ClientProxy.loadedSchematics.contains(previous)) ClientProxy.selectSchematic(previous);
            WorldHandler.INSTANCE.saveSession();
            throw e;
        }
    }
}
