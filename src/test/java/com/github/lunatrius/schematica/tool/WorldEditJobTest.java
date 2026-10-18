package com.github.lunatrius.schematica.tool;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorldEditJobTest {
    @org.junit.BeforeClass public static void bootstrap() { net.minecraft.init.Bootstrap.register(); }

    @Test public void taskProgressTracksEachPassAndCancelledJobsFinishTheUpdatePass() {
        com.github.lunatrius.schematica.task.TaskRegistry registry = new com.github.lunatrius.schematica.task.TaskRegistry();
        WorldEditJob job = job(WorldEditJob.Kind.FILL, false, false);
        com.github.lunatrius.schematica.task.TaskRegistry.Task task = registry.start(job.player, job.dimension, job.taskKind(),
            com.github.lunatrius.schematica.task.TaskRegistry.Backend.SERVER, "");
        job.publishProgress(task);
        assertEquals(com.github.lunatrius.schematica.task.TaskRegistry.Stage.STRUCTURE, task.progress().stage);
        assertFalse(job.step(null));
        job.publishProgress(task);
        assertEquals(1, task.progress().completed);
        assertEquals(1, task.progress().total);
        assertFalse(job.step(null));
        job.publishProgress(task);
        assertEquals(com.github.lunatrius.schematica.task.TaskRegistry.Stage.DECORATIONS, task.progress().stage);
        job.cancelled = true;
        assertFalse(job.step(null));
        job.publishProgress(task);
        assertEquals(com.github.lunatrius.schematica.task.TaskRegistry.Stage.UPDATES, task.progress().stage);
        assertFalse(job.step(null));
        assertTrue(job.step(null));
        job.publishProgress(task);
        assertEquals(com.github.lunatrius.schematica.task.TaskRegistry.Stage.ENTITIES, task.progress().stage);
        task.finish();
    }

    @Test public void editsSkipUnselectedGapsBeforeReadingOrWritingTheWorld() {
        for (WorldEditJob.Kind kind : WorldEditJob.Kind.values()) {
            WorldEditJob job = new WorldEditJob(UUID.randomUUID(), 0, kind, 0, 64, 0, 3, 1, 1, null, null);
            job.setRegions(java.util.Collections.singletonList(new com.github.lunatrius.schematica.api.SchematicRegion("Box", 1, 0, 0, 1, 0, 0)));
            org.junit.Assert.assertNull(job.command(0, null));
            org.junit.Assert.assertNull(job.command(2, null));
            assertFalse(job.step(null));
        }
    }
    @Test public void pasteOptionsDefaultOffAndDoNotAffectOtherTools() {
        WorldEditJob defaults = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.PASTE, 0, 0, 0,
            1, 1, 1, null, null);
        assertFalse(defaults.pasteWithoutUpdates);
        assertEquals(ReplaceBehavior.ALL, defaults.replace);
        WorldEditJob fill = job(WorldEditJob.Kind.FILL, true, true);
        assertFalse(fill.pasteWithoutUpdates);
        assertEquals(ReplaceBehavior.ALL, fill.replace);
        assertTrue(fill.blockCommand(1, 2, 3, net.minecraft.init.Blocks.STONE.getDefaultState()).endsWith(" replace"));
    }

    @Test public void renderedLayersOnlyPastesTheLayerPartOfEachRegion() {
        com.github.lunatrius.schematica.world.storage.Schematic schematic = new com.github.lunatrius.schematica.world.storage.Schematic(null, 3, 3, 1);
        WorldEditJob job = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.PASTE, 0, 64, 0, 3, 3, 1, null, null);
        job.capture(schematic, false, false, new int[] {0, 1, 0, 3, 2, 1}, false);
        org.junit.Assert.assertNull(job.command(0, null));
        org.junit.Assert.assertNull(job.command(8, null));
        WorldEditJob outside = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.PASTE, 0, 64, 0, 3, 3, 1, null, null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> outside.capture(schematic, false, false, new int[] {0, 3, 0, 3, 3, 1}, false));
        assertEquals("schematica.message.paste.outside_layers", ((com.github.lunatrius.schematica.util.MessageException) error).key());
    }

    @Test public void airOnlyCommandsRequireTheServerToRecheckTheTarget() {
        WorldEditJob job = job(WorldEditJob.Kind.PASTE, false, true);
        assertEquals("/setblock -1 64 2 minecraft:stone 3 keep", job.blockCommand(-1, 64, 2, net.minecraft.init.Blocks.STONE.getStateFromMeta(3)));
    }

    @Test public void replaceBehaviorFollowsLitematicaPasteRules() {
        assertTrue(ReplaceBehavior.NONE.places(true, false));
        assertFalse(ReplaceBehavior.NONE.places(false, false));
        assertTrue(ReplaceBehavior.ALL.places(false, true));
        assertTrue(ReplaceBehavior.WITH_NON_AIR.places(false, false));
        assertFalse(ReplaceBehavior.WITH_NON_AIR.places(false, true));
        assertEquals(ReplaceBehavior.ALL, ReplaceBehavior.NONE.cycle(false));
        assertEquals(ReplaceBehavior.WITH_NON_AIR, ReplaceBehavior.NONE.cycle(true));
        assertEquals(ReplaceBehavior.NONE, ReplaceBehavior.parse("unknown"));
        assertEquals(ReplaceBehavior.WITH_NON_AIR, ReplaceBehavior.parse("with_non_air"));
    }

    @Test public void commandPastesAreAllowedWithUpdateSuppressionBlockDataAndEntities() throws Exception {
        nbtJob(true, true, true).validateCommandFallback();
        nbtJob(false, false, false).validateCommandFallback();
    }

    @Test public void blockDataTravelsInTheSetblockCommandWithoutPositionOrId() throws Exception {
        WorldEditJob job = nbtJob(false, true, false);
        assertEquals("/setblock 5 64 7 minecraft:stone 0 replace {CustomName:\"Preserved until accepted\"}",
            job.blockCommand(5, 64, 7, net.minecraft.init.Blocks.STONE.getDefaultState(), job.tileTag(0)));
        net.minecraft.nbt.NBTTagCompound huge = new net.minecraft.nbt.NBTTagCompound();
        huge.setString("Text1", new String(new char[300]).replace('\0', 'a'));
        org.junit.Assert.assertNull(job.blockCommand(5, 64, 7, net.minecraft.init.Blocks.STONE.getDefaultState(), huge));
    }

    @Test public void summonsPlaceEntitiesAtTheirWorldPositionWithoutVolatileData() {
        WorldEditJob job = new WorldEditJob(UUID.randomUUID(), 0, WorldEditJob.Kind.PASTE, 100, 64, -20, 1, 1, 1, null, null);
        net.minecraft.nbt.NBTTagCompound frame = new net.minecraft.nbt.NBTTagCompound();
        frame.setString("id", "minecraft:item_frame");
        net.minecraft.nbt.NBTTagList pos = new net.minecraft.nbt.NBTTagList();
        for (double value : new double[] {0.5, 1.0, 2.25}) pos.appendTag(new net.minecraft.nbt.NBTTagDouble(value));
        frame.setTag("Pos", pos);
        frame.setLong("UUIDMost", 1); frame.setShort("Air", (short) 300);
        frame.setByte("Facing", (byte) 2);
        frame.setInteger("TileX", 0); frame.setInteger("TileY", 1); frame.setInteger("TileZ", 2);
        String summon = job.summonCommand(frame);
        assertTrue(summon, summon.startsWith("/summon minecraft:item_frame 100.5 65 -17.75 {"));
        for (String part : new String[] {"Facing:2b", "TileX:100", "TileY:65", "TileZ:-18"}) assertTrue(summon, summon.contains(part));
        assertFalse(summon, summon.contains("UUID") || summon.contains("Air") || summon.contains("Pos"));
        assertEquals("/kill @e[x=1,y=2,z=3,dx=3,dy=0,dz=1,type=!player]", WorldEditJob.killCommand(1, 2, 3, 4, 2, 4));
    }

    private static WorldEditJob nbtJob(boolean silent, boolean tiles, boolean entities) throws Exception {
        WorldEditJob job = job(WorldEditJob.Kind.PASTE, silent, false);
        job.capture(new com.github.lunatrius.schematica.world.storage.Schematic(null, 1, 1, 1), false, false);
        net.minecraft.nbt.NBTTagCompound data = new net.minecraft.nbt.NBTTagCompound();
        data.setString("id", "SavedData");
        data.setString("CustomName", "Preserved until accepted");
        if (tiles) {
            java.lang.reflect.Field field = WorldEditJob.class.getDeclaredField("tiles");
            field.setAccessible(true);
            ((java.util.Map<Integer, net.minecraft.nbt.NBTTagCompound>) field.get(job)).put(0, data);
        }
        if (entities) {
            java.lang.reflect.Field field = WorldEditJob.class.getDeclaredField("entities");
            field.setAccessible(true);
            ((java.util.List<net.minecraft.nbt.NBTTagCompound>) field.get(job)).add((net.minecraft.nbt.NBTTagCompound) data.copy());
        }
        return job;
    }

    private static WorldEditJob job(WorldEditJob.Kind kind, boolean silent, boolean air) {
        return new WorldEditJob(UUID.randomUUID(), 0, kind, 0, 0, 0, 1, 1, 1, null, null, silent, air ? ReplaceBehavior.NONE : ReplaceBehavior.ALL);
    }

    @Test public void rejectsOutOfWorldPasteBeforeCapturingData() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new WorldEditJob(player, 0,
            WorldEditJob.Kind.PASTE, 0, 255, 0, 2, 2, 2, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorldEditJob(player, 0,
            WorldEditJob.Kind.PASTE, Integer.MAX_VALUE, 0, 0, 2, 2, 2, null, null));
        WorldEditJob job = new WorldEditJob(player, 0, WorldEditJob.Kind.PASTE, -1, 254, -1,
            2, 2, 2, null, null);
        assertEquals(8, job.volume);
        assertEquals(-1, job.x);
    }
}
