package com.github.lunatrius.schematica.client.world;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.util.MessageException;
import com.github.lunatrius.schematica.util.SchematicLimits;
import com.github.lunatrius.schematica.world.schematic.SchematicFileSnapshot;
import com.github.lunatrius.schematica.world.schematic.SchematicFormat;
import com.github.lunatrius.schematica.world.storage.Schematic;
import com.github.lunatrius.schematica.world.storage.SchematicCopies;

public final class SchematicSourceData {

    public final SchematicFileSnapshot snapshot;
    public final int width;
    public final int height;
    public final int length;
    private Schematic edited;
    private int revision;
    private int savedRevision;
    private long modifiedTime;
    private final java.util.Map<String, String> unknownBlocks;
    private boolean unknownBlocksReported;
    /** Blocks of the file this game does not have: block name to the number of blocks. */
    private final java.util.Map<String, Long> missingBlocks;
    private com.github.lunatrius.schematica.world.schematic.ItemIdMaps.Result itemIds;
    /** The schematic decoded while reading, handed to the first placement instead of decoding the file again. */
    private java.lang.ref.SoftReference<ISchematic> decoded;
    /** A decoded copy no placement owns, read (never changed) when sub-regions are recomposed; dropped under memory pressure. */
    private java.lang.ref.SoftReference<ISchematic> composeSource;

    private SchematicSourceData(SchematicFileSnapshot snapshot, ISchematic data) {
        this.snapshot = snapshot;
        this.unknownBlocks = com.github.lunatrius.schematica.world.schematic.ImportReport.last();
        this.missingBlocks = com.github.lunatrius.schematica.world.schematic.ImportReport.lastCounts();
        this.itemIds = com.github.lunatrius.schematica.world.schematic.ImportReport.lastItems();
        width = data.getWidth();
        height = data.getHeight();
        length = data.getLength();
        SchematicLimits.boxVolume(width, height, length);
        decoded = new java.lang.ref.SoftReference<>(data);
    }

    public static SchematicSourceData read(File file) throws IOException {
        String stamp = stamp(file);
        SchematicSourceData data = of(SchematicFileSnapshot.read(file));
        data.fileStamp = stamp;
        return data;
    }

    /** The time and size of the file when it was read, or null for data not read from a file. */
    private String fileStamp;

    /** Whether the file was changed or replaced on disk since it was read. */
    public boolean changedOnDisk(File file) {
        return fileStamp != null && file.isFile() && !fileStamp.equals(stamp(file));
    }

    /**
     * When the file was changed, how big it is and when it was created: a file copied over another one keeps its
     * modification time (Windows), but it is created anew.
     */
    private static String stamp(File file) {
        String created = "";
        try {
            created = String.valueOf(java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class)
                .creationTime().toMillis());
        } catch (IOException | RuntimeException ignored) {}
        return file.lastModified() + ":" + file.length() + ":" + created;
    }

    public static SchematicSourceData of(SchematicFileSnapshot snapshot) throws IOException {
        return new SchematicSourceData(snapshot, SchematicFormat.readFromSnapshot(snapshot));
    }

    public synchronized ISchematic instantiate() throws IOException {
        ISchematic first = decoded == null ? null : decoded.get();
        decoded = null;
        if (modified()) return SchematicCopies.copy(edited);
        return first != null ? first : SchematicFormat.readFromSnapshot(snapshot);
    }

    /**
     * The source to compose sub-regions from: read only, shared by every placement of this file.
     * The file is decoded at most once while memory allows, instead of on every sub-region change.
     */
    public synchronized ISchematic composeSource() throws IOException {
        if (modified()) return edited;
        ISchematic cached = composeSource == null ? null : composeSource.get();
        if (cached == null) {
            cached = SchematicFormat.readFromSnapshot(snapshot);
            composeSource = new java.lang.ref.SoftReference<>(cached);
        }
        return cached;
    }

    /** Block names or states of the file this game does not have, to the block that replaced them; taken once. */
    public java.util.Map<String, String> takeUnknownBlocks() {
        if (unknownBlocksReported) return java.util.Collections.emptyMap();
        unknownBlocksReported = true;
        return unknownBlocks;
    }

    /** The blocks of the file this game does not have, per block name, with how many there are. */
    public java.util.Map<String, Long> missingBlocks() { return missingBlocks; }

    /** Whether the player went through these missing blocks ("Use these") since the file was read. */
    private boolean missingBlocksAnswered;

    public boolean missingBlocksAnswered() { return missingBlocksAnswered; }

    public void markMissingBlocksAnswered() { missingBlocksAnswered = true; }

    /** What loading did with the item ids of the file; taken once, then null. */
    public com.github.lunatrius.schematica.world.schematic.ItemIdMaps.Result takeItemIds() {
        com.github.lunatrius.schematica.world.schematic.ItemIdMaps.Result result = itemIds;
        itemIds = null;
        return result;
    }

    public boolean modified() { return revision > 0; }
    public int revision() { return revision; }
    /** Whether edits exist that were not written to any file yet. */
    public boolean unsaved() { return revision != savedRevision; }
    public long modifiedTime() { return modifiedTime; }
    public String saveExtension() { return modified() ? ".schemplus" : snapshot.extension(); }

    /** The in-memory working copy edited by the rebuild tool; the original file snapshot is never changed. */
    public Schematic editable() throws IOException {
        if (edited == null) {
            ISchematic data = SchematicFormat.readFromSnapshot(snapshot);
            edited = data instanceof Schematic ? (Schematic) data : SchematicCopies.copy(data);
        }
        return edited;
    }

    public void changed() {
        revision++;
        composeSource = null;
        modifiedTime = System.currentTimeMillis();
    }

    /** The edits were written to a file another way (the material list's Save). */
    public void markSaved() { savedRevision = revision; }

    public void save(File file, boolean replace) throws IOException {
        if (!modified()) { snapshot.write(file, replace); return; }
        SchematicFileSnapshot.capture(edited).write(file, replace);
        savedRevision = revision;
    }
}
