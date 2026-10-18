package com.github.lunatrius.schematica.client.world;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

/** Loads, places and rotates every schematic in the import folder (skipped when there is none). */
public class ImportFolderLoadTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    public void everyImportedSchematicLoads() {
        File[] files = new File("../reference/test-schematics").listFiles((dir, name) -> name.matches("(?i).*\\.(litematic|schematic|schem|nbt)"));
        Assume.assumeTrue(files != null && files.length > 0);
        List<String> failures = new ArrayList<>();
        for (File file : files) {
            try {
                SchematicSourceData source = SchematicSourceData.read(file);
                ISchematic schematic = source.instantiate();
                int[] count = {0};
                ((Schematic) schematic).forEachBlock((x, y, z, state) -> count[0]++);
                SchematicWorld world = new SchematicWorld(schematic, file.getName());
                world.setPlacementSource(source);
                world.isRendering = true;
                world.position.set(0, 0, 0);
                world.rotate(EnumFacing.Axis.Y);
                System.out.println("LOADED " + file.getName() + " " + schematic.getWidth() + "x" + schematic.getHeight() + "x" + schematic.getLength()
                    + " regions=" + schematic.getRegions().size() + " blocks=" + count[0]);
            } catch (Throwable t) {
                StringWriter trace = new StringWriter();
                t.printStackTrace(new PrintWriter(trace));
                failures.add(file.getName() + ": " + trace);
            }
        }
        for (String failure : failures) System.out.println("FAILED " + failure);
        assertTrue(failures.size() + " of " + files.length + " failed:\n" + String.join("\n", failures), failures.isEmpty());
    }
}
