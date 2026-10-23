package com.github.lunatrius.schematica.tool;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.util.MessageException;
import com.github.lunatrius.schematica.util.SchematicLimits;
import com.github.lunatrius.schematica.world.schematic.BlockStates;
import com.github.lunatrius.schematica.world.storage.RegionMask;

/** A snapshot of an edit. Only the server tick queue advances its mutable cursor. */
public final class WorldEditJob extends WorldEditTask {
    public enum Kind { PASTE, FILL, REPLACE, DELETE_PLACEMENT }
    /** The longest command a 1.12 client may send in chat. */
    private static final int MAX_COMMAND = 256;
    public final int width, height, length, volume;
    public final Kind kind;
    public final boolean pasteWithoutUpdates;
    public final ReplaceBehavior replace;
    /** For DELETE_PLACEMENT: which non-air world blocks inside the placement are removed. */
    public PlacementDeletionMode deletion = PlacementDeletionMode.ENTIRE_VOLUME;
    private List<SchematicRegion> entityRemoval = new ArrayList<>();
    private boolean entitiesRemoved;
    private final IBlockState replacement, target;
    /** commandNameSetblock, commandNameFill */
    public static volatile String setblockCommand = "setblock", fillCommand = "fill", summonCommand = "summon",
        replaceitemCommand = "replaceitem";
    /** Block.getStateId of every cell: the stored (metadata) form of the state, as the world keeps it. */
    private int[] states;
    private final Map<Integer, NBTTagCompound> tiles = new HashMap<>();
    private final List<NBTTagCompound> entities = new ArrayList<>();
    private final BitSet placed = new BitSet();
    private BitSet selected;
    private final SilentBlockPlacement silentPlacement;
    private int cursor, phase, entityCursor;
    private boolean metadataPassComplete;

    public WorldEditJob(UUID player, int dimension, Kind kind, int x, int y, int z,
        int width, int height, int length, IBlockState replacement, IBlockState target) {
        this(player, dimension, kind, x, y, z, width, height, length, replacement, target, false, ReplaceBehavior.ALL);
    }

    public WorldEditJob(UUID player, int dimension, Kind kind, int x, int y, int z,
        int width, int height, int length, IBlockState replacement, IBlockState target,
        boolean pasteWithoutUpdates, ReplaceBehavior replace) {
        super(player, dimension, x, y, z);
        SchematicLimits.worldBounds(x, y, z, (long) x + width - 1, (long) y + height - 1, (long) z + length - 1);
        this.kind = kind;
        this.width = width; this.height = height; this.length = length;
        this.volume = SchematicLimits.volume(width, height, length);
        this.replacement = stored(replacement);
        this.target = stored(target);
        this.pasteWithoutUpdates = kind == Kind.PASTE && pasteWithoutUpdates;
        this.replace = kind == Kind.PASTE ? replace : ReplaceBehavior.ALL;
        this.silentPlacement = this.pasteWithoutUpdates ? new SilentBlockPlacement() : null;
        this.metadataPassComplete = kind != Kind.PASTE || this.pasteWithoutUpdates;
    }

    /** The state as the world stores it (only the properties that metadata keeps). */
    private static IBlockState stored(IBlockState state) {
        return state == null ? null : Block.getStateById(Block.getStateId(state));
    }

    private static boolean same(IBlockState a, IBlockState b) {
        return Block.getStateId(a) == Block.getStateId(b);
    }

    public void capture(ISchematic source, boolean blockNBT, boolean includeEntities) {
        capture(source, blockNBT, includeEntities, null, false);
    }

    /**
     * bounds: the local [minX, minY, minZ, maxX, maxY, maxZ) part to paste (pasteLayerBehavior rendered_only), or null for all;
     * ignoreInventories: containers are pasted empty (pasteIgnoreInventories).
     */
    public void capture(ISchematic source, boolean blockNBT, boolean includeEntities, int[] bounds, boolean ignoreInventories) {
        List<SchematicRegion> regions = source.getRegions();
        if (bounds != null) {
            if (regions.isEmpty()) regions = Collections.singletonList(new SchematicRegion("all", 0, 0, 0, width - 1, height - 1, length - 1));
            List<SchematicRegion> clipped = new ArrayList<>();
            for (SchematicRegion region : regions) {
                int ax = Math.max(region.minX, bounds[0]), ay = Math.max(region.minY, bounds[1]), az = Math.max(region.minZ, bounds[2]);
                int bx = Math.min(region.maxX, bounds[3] - 1), by = Math.min(region.maxY, bounds[4] - 1), bz = Math.min(region.maxZ, bounds[5] - 1);
                if (ax <= bx && ay <= by && az <= bz) clipped.add(new SchematicRegion(region.name, ax, ay, az, bx, by, bz));
            }
            if (clipped.isEmpty()) throw new MessageException("schematica.message.paste.outside_layers");
            regions = clipped;
        }
        setRegions(regions);
        this.states = new int[volume];
        for (int i = 0; i < volume; i++) {
            int sx = i % width, sz = i / width % length, sy = i / width / length;
            IBlockState state = source.getBlockState(sx, sy, sz);
            states[i] = state == null ? 0 : Block.getStateId(state);
        }
        if (blockNBT) {
            for (TileEntity tile : source.getTileEntities()) {
                BlockPos pos = tile.getPos();
                if (!source.containsBlock(pos)) continue;
                if (pos.getX() < 0 || pos.getX() >= width || pos.getY() < 0 || pos.getY() >= height
                    || pos.getZ() < 0 || pos.getZ() >= length) continue;
                if (bounds != null && !inside(bounds, pos.getX(), pos.getY(), pos.getZ())) continue;
                NBTTagCompound tag = NBTHelper.writeTileEntityToCompound(tile);
                if (ignoreInventories && tile instanceof IInventory) tag = emptied(tag);
                tiles.put(pos.getX() + width * (pos.getZ() + length * pos.getY()), tag);
            }
        }
        if (includeEntities) {
            for (Entity entity : source.getEntities()) {
                if (bounds != null && !inside(bounds, (int) Math.floor(entity.posX), (int) Math.floor(entity.posY), (int) Math.floor(entity.posZ))) continue;
                NBTTagCompound tag = NBTHelper.writeEntityToCompound(entity);
                if (tag != null) entities.add(tag);
            }
        }
    }

    private static boolean inside(int[] bounds, int x, int y, int z) {
        return x >= bounds[0] && y >= bounds[1] && z >= bounds[2] && x < bounds[3] && y < bounds[4] && z < bounds[5];
    }

    /** The tile entity's data with its slots cleared, read back through a copy so mod containers clear their own items. */
    private static NBTTagCompound emptied(NBTTagCompound tag) {
        try {
            TileEntity copy = NBTHelper.readTileEntityFromCompound(tag);
            if (copy instanceof IInventory) {
                IInventory inventory = (IInventory) copy;
                for (int i = 0; i < inventory.getSizeInventory(); i++) inventory.setInventorySlotContents(i, ItemStack.EMPTY);
                NBTTagCompound cleared = NBTHelper.writeTileEntityToCompound(copy);
                if (cleared != null) return cleared;
            }
        } catch (RuntimeException error) {
            Reference.logger.debug("Could not empty a pasted container", error);
        }
        tag.removeTag("Items");
        return tag;
    }

    /** pasteIgnoreBlockEntitiesEntirely: command pasting leaves block entity data out instead of refusing. */
    public void dropTiles() { tiles.clear(); }

    /** Rejects edits whose block names cannot fit a chat command; block data that does not fit is left out per block. */
    public void validateCommandFallback() {
        Set<Integer> checked = new HashSet<>();
        for (int i = 0; i < (kind == Kind.PASTE ? volume : 1); i++) {
            IBlockState state = kind == Kind.PASTE ? Block.getStateById(states[i]) : replacement;
            if (state != null && checked.add(Block.getStateId(state))) {
                if (blockCommand(-30000000, 255, -30000000, state).length() > MAX_COMMAND) {
                    throw new MessageException("schematica.message.edit.command_too_long");
                }
            }
        }
    }

    /** The block entity data to paste in a cell, or null. */
    public NBTTagCompound tileTag(int index) { return tiles.get(index); }

    /** Captured entities, positioned relative to the edit's origin. */
    public List<NBTTagCompound> entityTags() { return Collections.unmodifiableList(entities); }

    /** World boxes whose non-player entities this edit removes first. */
    public List<SchematicRegion> entityRemovalBoxes() { return Collections.unmodifiableList(entityRemoval); }

    public String command(int index, World world) {
        IBlockState state = target(index);
        if (state == null) return null;
        BlockPos pos = position(index);
        if (!world.isBlockLoaded(pos)) return null;
        if (!writes(index, world.getBlockState(pos), state)) return null;
        return blockCommand(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /** The state this edit wants in a cell, or null for cells outside the edit. */
    public IBlockState target(int index) {
        if (selected != null && !selected.get(index)) return null;
        return kind == Kind.PASTE ? Block.getStateById(states[index]) : replacement;
    }

    /** Whether the edit writes its target over this world state: replace rules, the replace target and the deletion mode. */
    public boolean writes(int index, IBlockState existing, IBlockState state) {
        if (state == null || same(existing, state)) return false;
        boolean worldAir = existing.getMaterial() == Material.AIR;
        if (kind == Kind.REPLACE && !same(existing, target)) return false;
        if (kind == Kind.DELETE_PLACEMENT && !deletes(index, existing)) return false;
        return pastes(state, worldAir);
    }

    public int index(int localX, int localY, int localZ) { return localX + width * (localZ + length * localY); }

    public BlockPos position(int index) {
        return new BlockPos(x + index % width, y + index / width / length, z + index / width % length);
    }

    /** Encodes the whole edit for the remote edit protocol; states are stored by name and properties. */
    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("version", 2);
        tag.setString("kind", kind.name());
        tag.setIntArray("bounds", new int[] {x, y, z, width, height, length});
        tag.setBoolean("updates", pasteWithoutUpdates);
        tag.setString("replace", replace.value);
        tag.setString("deletion", deletion.value);
        if (replacement != null) tag.setString("replacement", BlockStates.toString(replacement));
        if (target != null) tag.setString("target", BlockStates.toString(target));
        if (selected != null) tag.setByteArray("mask", selected.toByteArray());
        if (states != null) {
            List<String> palette = new ArrayList<>();
            Map<Integer, Integer> indices = new HashMap<>();
            byte[] cells = new byte[volume * 2];
            for (int i = 0; i < volume; i++) {
                Integer index = indices.get(states[i]);
                if (index == null) {
                    index = palette.size();
                    palette.add(BlockStates.toString(Block.getStateById(states[i])));
                    indices.put(states[i], index);
                }
                cells[i * 2] = (byte) (index >> 8);
                cells[i * 2 + 1] = (byte) (int) index;
            }
            NBTTagList names = new NBTTagList();
            for (String entry : palette) names.appendTag(new NBTTagString(entry));
            tag.setTag("palette", names);
            tag.setByteArray("cells", cells);
        }
        NBTTagList tileList = new NBTTagList();
        for (Map.Entry<Integer, NBTTagCompound> entry : tiles.entrySet()) {
            NBTTagCompound tile = new NBTTagCompound();
            tile.setInteger("index", entry.getKey());
            tile.setTag("data", entry.getValue());
            tileList.appendTag(tile);
        }
        tag.setTag("tiles", tileList);
        NBTTagList entityList = new NBTTagList();
        for (NBTTagCompound entity : entities) entityList.appendTag(entity);
        tag.setTag("entities", entityList);
        NBTTagList removal = new NBTTagList();
        for (SchematicRegion box : entityRemoval) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setIntArray("box", new int[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
            removal.appendTag(entry);
        }
        tag.setTag("removeEntities", removal);
        return tag;
    }

    private static IBlockState state(String name) {
        IBlockState state = BlockStates.parse(name);
        if (state == null) throw new IllegalArgumentException("Unknown block " + name);
        return state;
    }

    /** Decodes a remote edit, checking every size and reference against this server. */
    public static WorldEditJob read(NBTTagCompound tag, UUID player, int dimension) {
        if (tag.getInteger("version") != 2) throw new IllegalArgumentException("Unsupported edit version");
        Kind kind = Kind.valueOf(tag.getString("kind"));
        int[] bounds = tag.getIntArray("bounds");
        if (bounds.length != 6) throw new IllegalArgumentException("Invalid edit bounds");
        IBlockState replacement = tag.hasKey("replacement", 8) ? state(tag.getString("replacement")) : null;
        IBlockState target = tag.hasKey("target", 8) ? state(tag.getString("target")) : null;
        if ((kind == Kind.FILL || kind == Kind.REPLACE || kind == Kind.DELETE_PLACEMENT) && replacement == null) throw new IllegalArgumentException("Missing fill block");
        if (kind == Kind.REPLACE && target == null) throw new IllegalArgumentException("Missing target block");
        if (kind == Kind.DELETE_PLACEMENT && replacement.getBlock() != Blocks.AIR) throw new IllegalArgumentException("Deletion must place air");
        WorldEditJob job = new WorldEditJob(player, dimension, kind, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
            replacement, target, tag.getBoolean("updates"), ReplaceBehavior.parse(tag.getString("replace")));
        job.deletion = PlacementDeletionMode.parse(tag.getString("deletion"));
        if (tag.hasKey("mask", 7)) {
            BitSet mask = BitSet.valueOf(tag.getByteArray("mask"));
            if (mask.length() > job.volume) throw new IllegalArgumentException("Invalid region mask");
            job.selected = mask;
        }
        if (kind == Kind.PASTE || kind == Kind.DELETE_PLACEMENT) {
            NBTTagList names = tag.getTagList("palette", 8);
            byte[] cells = tag.getByteArray("cells");
            if (names.tagCount() == 0 || names.tagCount() > 65536 || cells.length != job.volume * 2) {
                throw new IllegalArgumentException("Invalid edit blocks");
            }
            int[] ids = new int[names.tagCount()];
            for (int i = 0; i < ids.length; i++) ids[i] = Block.getStateId(state(names.getStringTagAt(i)));
            job.states = new int[job.volume];
            for (int i = 0; i < job.volume; i++) {
                int index = (cells[i * 2] & 0xff) << 8 | cells[i * 2 + 1] & 0xff;
                if (index >= ids.length) throw new IllegalArgumentException("Invalid palette index");
                job.states[i] = ids[index];
            }
        }
        NBTTagList tileList = tag.getTagList("tiles", 10);
        if (kind != Kind.PASTE && tileList.tagCount() > 0) throw new IllegalArgumentException("Tile data outside a paste");
        for (int i = 0; i < tileList.tagCount(); i++) {
            NBTTagCompound tile = tileList.getCompoundTagAt(i);
            int index = tile.getInteger("index");
            if (index < 0 || index >= job.volume || !tile.hasKey("data", 10)) throw new IllegalArgumentException("Invalid tile entry");
            job.tiles.put(index, tile.getCompoundTag("data"));
        }
        NBTTagList entityList = tag.getTagList("entities", 10);
        if (kind != Kind.PASTE && entityList.tagCount() > 0) throw new IllegalArgumentException("Entities outside a paste");
        for (int i = 0; i < entityList.tagCount(); i++) job.entities.add(entityList.getCompoundTagAt(i));
        NBTTagList removal = tag.getTagList("removeEntities", 10);
        if (removal.tagCount() > 256) throw new IllegalArgumentException("Too many entity boxes");
        for (int i = 0; i < removal.tagCount(); i++) {
            int[] box = removal.getCompoundTagAt(i).getIntArray("box");
            if (box.length != 6 || box[0] < job.x || box[1] < job.y || box[2] < job.z
                || box[3] >= job.x + job.width || box[4] >= job.y + job.height || box[5] >= job.z + job.length
                || box[0] > box[3] || box[1] > box[4] || box[2] > box[5]) throw new IllegalArgumentException("Invalid entity box");
            job.entityRemoval.add(new SchematicRegion(Integer.toString(i), box[0], box[1], box[2], box[3], box[4], box[5]));
        }
        return job;
    }

    /** TaskDeleteBlocksByPlacement: only non-air world blocks the deletion mode selects. */
    private boolean deletes(int index, IBlockState existing) {
        if (existing.getMaterial() == Material.AIR) return false;
        boolean schematicAir = Block.getStateById(states[index]).getBlock() == Blocks.AIR;
        return deletion.deletes(schematicAir, Block.getStateId(existing) == states[index]);
    }

    /** TaskFillArea.directRemoveEntities: non-player entities inside these world boxes are removed before deleting. */
    public void removeEntitiesIn(List<SchematicRegion> boxes) { entityRemoval = new ArrayList<>(boxes); }

    public boolean removesEntities() { return !entityRemoval.isEmpty(); }

    private void removeEntities(WorldServer world) {
        for (SchematicRegion box : entityRemoval) {
            AxisAlignedBB bounds = new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX + 1, box.maxY + 1, box.maxZ + 1);
            for (Entity entity : world.getEntitiesWithinAABBExcludingEntity(null, bounds)) {
                if (!(entity instanceof EntityPlayer) && !entity.isDead) {
                    entity.setDead();
                    entityCount++;
                }
            }
        }
    }

    public interface CellFilter { boolean keep(int x, int y, int z); }

    /** Drops world positions the filter rejects, such as those outside the render layer range. */
    public void restrict(CellFilter filter) {
        BitSet next = selected == null ? new BitSet(volume) : (BitSet) selected.clone();
        if (selected == null) next.set(0, volume);
        for (int index = next.nextSetBit(0); index >= 0; index = next.nextSetBit(index + 1)) {
            if (!filter.keep(x + index % width, y + index / width / length, z + index / width % length)) next.clear(index);
        }
        selected = next;
    }

    /** Paste skips per the replace behavior, and never writes air over air; fills and deletes always apply. */
    private boolean pastes(IBlockState state, boolean worldAir) {
        if (kind != Kind.PASTE) return true;
        boolean air = state.getBlock() == Blocks.AIR;
        return !(air && worldAir) && replace.places(worldAir, air);
    }

    public String blockCommand(int x, int y, int z, IBlockState state) {
        return "/" + setblockCommand + " " + x + " " + y + " " + z + " " + stateArguments(state);
    }

    public String fillCommand(int x1, int y1, int z1, int x2, int y2, int z2, IBlockState state) {
        return "/" + fillCommand + " " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " "
            + stateArguments(state);
    }

    /** A setblock that also writes block entity data (1.12 dataTag), or null when it would not fit in chat. */
    public String blockCommand(int x, int y, int z, IBlockState state, NBTTagCompound tile) {
        NBTTagCompound data = tile.copy();
        data.removeTag("x"); data.removeTag("y"); data.removeTag("z"); data.removeTag("id");
        String command = blockCommand(x, y, z, state) + " " + compact(data);
        return command.length() <= MAX_COMMAND ? command : null;
    }

    /**
     * For a container too full for one setblock: the setblock with its data but an empty inventory (which also clears
     * what the world block held), or null when even that does not fit. {@link #itemCommands} fills it afterwards.
     */
    public String emptiedBlockCommand(int x, int y, int z, IBlockState state, NBTTagCompound tile) {
        NBTTagCompound data = tile.copy();
        data.setTag("Items", new NBTTagList());
        return blockCommand(x, y, z, state, data);
    }

    /** One replaceitem per stack of a container's Items list; null entries are stacks too big for chat. */
    public List<String> itemCommands(int x, int y, int z, NBTTagCompound tile) {
        List<String> commands = new ArrayList<>();
        NBTTagList items = tile.getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            NBTTagCompound tag = item.getCompoundTag("tag");
            String command = "/" + replaceitemCommand + " block " + x + " " + y + " " + z + " slot.container." + (item.getByte("Slot") & 255)
                + " " + itemId(item.getString("id")) + " " + item.getByte("Count") + " " + item.getShort("Damage")
                + (tag.hasNoTags() ? "" : " " + tag);
            commands.add(command.length() <= MAX_COMMAND ? command : null);
        }
        return commands;
    }

    /** NBT text as vanilla writes it, except Items lists: short ids, untyped numbers, default damage left out. */
    static String compact(NBTTagCompound data) {
        StringBuilder text = new StringBuilder("{");
        for (String key : data.getKeySet()) {
            if (text.length() > 1) text.append(',');
            text.append(key.matches("[A-Za-z0-9._+-]+") ? key : NBTTagString.quoteAndEscape(key)).append(':');
            if (key.equals("Items") && data.hasKey(key, 9) && data.getTagList(key, 10).tagCount() == ((NBTTagList) data.getTag(key)).tagCount()) {
                NBTTagList items = data.getTagList(key, 10);
                text.append('[');
                for (int i = 0; i < items.tagCount(); i++) text.append(i > 0 ? "," : "").append(compactItem(items.getCompoundTagAt(i)));
                text.append(']');
            } else text.append(data.getTag(key));
        }
        return text.append('}').toString();
    }

    private static String compactItem(NBTTagCompound item) {
        StringBuilder text = new StringBuilder("{Slot:").append(item.getByte("Slot")).append(",id:").append(itemId(item.getString("id")))
            .append(",Count:").append(item.getByte("Count"));
        if (item.getShort("Damage") != 0) text.append(",Damage:").append(item.getShort("Damage"));
        for (String key : item.getKeySet()) {
            if (key.equals("Slot") || key.equals("id") || key.equals("Count") || key.equals("Damage")) continue;
            text.append(',').append(key.matches("[A-Za-z0-9._+-]+") ? key : NBTTagString.quoteAndEscape(key)).append(':').append(item.getTag(key));
        }
        return text.append('}').toString();
    }

    /** Vanilla ids without their namespace; anything the NBT parser could read as another type stays quoted. */
    private static String itemId(String id) {
        String shortId = id.startsWith("minecraft:") ? id.substring(10) : id;
        return shortId.matches("[a-z_][a-z0-9_]*") && !shortId.equals("true") && !shortId.equals("false") ? shortId : NBTTagString.quoteAndEscape(id);
    }

    /** Keys a summoned entity does not need: the command sets position and identity, the rest are defaults. */
    private static final String[] SUMMON_SKIPPED = {"Pos", "Motion", "UUIDMost", "UUIDLeast", "UUID", "id", "Dimension",
        "PortalCooldown", "Air", "Fire", "FallDistance", "OnGround", "Attributes", "HurtTime", "HurtByTimestamp", "DeathTime",
        "AbsorptionAmount", "FallFlying", "Invulnerable", "Leashed", "Health", "Rotation"};

    /** A summon for a captured entity at its world position, or null when it would not fit in chat. */
    public String summonCommand(NBTTagCompound entity) {
        return summonCommand(entity, false);
    }

    /**
     * With trim, a container entity (chest or hopper minecart) whose items do not all fit keeps the first stacks that
     * do: vanilla 1.12 cannot add items to an entity afterwards (replaceitem refuses minecarts, entitydata replaces lists).
     */
    public String summonCommand(NBTTagCompound entity, boolean trim) {
        String command = summonText(entity);
        NBTTagList items = entity.getTagList("Items", 10);
        if (command != null || !trim || items.hasNoTags()) return command;
        NBTTagCompound trimmed = entity.copy();
        NBTTagList kept = items.copy();
        while (command == null && !kept.hasNoTags()) {
            kept.removeTag(kept.tagCount() - 1);
            trimmed.setTag("Items", kept);
            command = summonText(trimmed);
        }
        return command;
    }

    private String summonText(NBTTagCompound entity) {
        String id = entity.getString("id");
        NBTTagList pos = entity.getTagList("Pos", 6);
        if (id.isEmpty() || pos.tagCount() != 3) return null;
        NBTTagCompound data = entity.copy();
        for (String key : SUMMON_SKIPPED) data.removeTag(key);
        if (data.hasKey("TileX")) {
            data.setInteger("TileX", data.getInteger("TileX") + x);
            data.setInteger("TileY", data.getInteger("TileY") + y);
            data.setInteger("TileZ", data.getInteger("TileZ") + z);
        }
        NBTTagList rotation = entity.getTagList("Rotation", 5);
        if (rotation.tagCount() == 2 && (rotation.getFloatAt(0) != 0 || rotation.getFloatAt(1) != 0)) data.setTag("Rotation", rotation);
        // Hopper minecart defaults, left out so a full cart's items fit
        if (data.getInteger("TransferCooldown") == 0) data.removeTag("TransferCooldown");
        if (data.hasKey("Enabled", 1) && data.getBoolean("Enabled")) data.removeTag("Enabled");
        String command = "/" + summonCommand + " " + id + " " + coordinate(pos.getDoubleAt(0) + x) + " "
            + coordinate(pos.getDoubleAt(1) + y) + " " + coordinate(pos.getDoubleAt(2) + z) + (data.hasNoTags() ? "" : " " + compact(data));
        return command.length() <= MAX_COMMAND ? command : null;
    }

    private static String coordinate(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.4f", value);
        return text.replaceFirst("\\.?0+$", "");
    }

    /** Removes non-player entities in a world box (the part of a removal box the client has loaded). */
    public static String killCommand(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "/kill @e[x=" + x1 + ",y=" + y1 + ",z=" + z1 + ",dx=" + (x2 - x1) + ",dy=" + (y2 - y1) + ",dz=" + (z2 - z1) + ",type=!player]";
    }

    private String stateArguments(IBlockState state) {
        return BlockStates.name(state.getBlock()) + " " + state.getBlock().getMetaFromState(state)
            + (replace == ReplaceBehavior.NONE ? " keep" : " replace");
    }

    public void setRegions(List<SchematicRegion> regions) {
        selected = regions.isEmpty() ? null : RegionMask.create(regions, width, height, length);
    }

    @Override
    public void flushBlockChanges(WorldServer world) {
        if (silentPlacement != null) silentPlacement.flush(world);
    }

    @Override
    public ITextComponent finishedMessage(boolean success) {
        if (success && blockCount == 0 && entityCount == 0) {
            return new TextComponentTranslation("schematica.message.edit.no_changes");
        }
        if (kind != Kind.DELETE_PLACEMENT) return super.finishedMessage(success);
        return new TextComponentString(success ? String.format("Deleted %d blocks", blockCount) : "Deletion task failed");
    }

    @Override
    public TaskRegistry.Kind taskKind() {
        if (kind == Kind.PASTE) return TaskRegistry.Kind.PASTE;
        if (kind == Kind.REPLACE) return TaskRegistry.Kind.REPLACE;
        return replacement != null && replacement.getBlock() == Blocks.AIR ? TaskRegistry.Kind.DELETE : TaskRegistry.Kind.FILL;
    }

    @Override
    public void publishProgress(TaskRegistry.Task task) {
        TaskRegistry.Stage stage = phase == 0 ? TaskRegistry.Stage.STRUCTURE : phase == 1
            ? TaskRegistry.Stage.DECORATIONS : phase == 2 ? TaskRegistry.Stage.UPDATES : TaskRegistry.Stage.ENTITIES;
        long completed = phase < 3 ? cursor : entityCursor, total = phase < 3 ? volume : entities.size();
        if (phase == 2 && kind == Kind.PASTE && !pasteWithoutUpdates) {
            total *= 2;
            if (metadataPassComplete) completed += volume;
        }
        task.update(stage, completed, total, blockCount, entityCount);
    }

    private boolean restoreState(WorldServer world, BlockPos pos, IBlockState state) {
        IBlockState stored = world.getBlockState(pos);
        if (stored.getBlock() != state.getBlock()) return false;
        // Placement callbacks may change this block's facing when another block is placed later.
        if (silentPlacement == null && !same(stored, state)) {
            world.setBlockState(pos, state, 2);
            stored = world.getBlockState(pos);
        }
        return same(stored, state);
    }

    /** Process one cell/entity, returning true only when all phases are finished. */
    @Override
    public boolean step(WorldServer world) {
        if (cancelled && phase < 2) { phase = 2; cursor = 0; }
        if (!entitiesRemoved) {
            entitiesRemoved = true;
            if (!cancelled) removeEntities(world);
        }
        if (phase < 3) {
            if (cursor == volume) {
                cursor = 0;
                if (phase == 2 && !metadataPassComplete) metadataPassComplete = true;
                else phase++;
                return false;
            }
            int index = cursor++;
            if (selected != null && !selected.get(index)) return false;
            BlockPos pos = new BlockPos(x + index % width, y + index / width / length, z + index / width % length);
            IBlockState state = kind == Kind.PASTE ? Block.getStateById(states[index]) : replacement;
            if (state == null) return false;
            Block block = state.getBlock();
            if (phase == 2) {
                if (!placed.get(index) || pasteWithoutUpdates) return false;
                // Later chest placement can turn the other half; preserve normal changes to other blocks.
                if (!metadataPassComplete) {
                    if (block instanceof BlockChest && world.getBlockState(pos).getBlock() == block
                        && !restoreState(world, pos, state) && world.getBlockState(pos).getBlock() == block) {
                        throw new IllegalStateException("Could not restore pasted chest facing at " + pos);
                    }
                    return false;
                }
                IBlockState current = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, current, current, 3);
                world.notifyNeighborsOfStateChange(pos, block, true);
                return false;
            }
            // Structural blocks first, then decorations. Neighbour notifications wait until phase 2.
            boolean structural = state.getMaterial().isSolid();
            if ((phase == 0) != structural) return false;
            IBlockState existing = world.getBlockState(pos);
            if (kind == Kind.REPLACE && !same(existing, target)) return false;
            if (kind == Kind.DELETE_PLACEMENT && !deletes(index, existing)) return false;
            if (!pastes(state, world.isAirBlock(pos))) return false;
            boolean hasTile = block.hasTileEntity(state);
            if (!hasTile && same(existing, state)) return false;
            if (silentPlacement != null) {
                silentPlacement.setBlock(world, pos, state);
            } else {
                if (kind == Kind.PASTE) world.removeTileEntity(pos);
                boolean previous = world.restoringBlockSnapshots;
                try {
                    world.restoringBlockSnapshots = true; // Suppress item drops from replaced inventories.
                    world.setBlockState(pos, state, 2);
                } finally {
                    world.restoringBlockSnapshots = previous;
                }
            }
            if (restoreState(world, pos, state)) {
                if (kind == Kind.PASTE && hasTile) {
                    NBTTagCompound tag = tiles.get(index);
                    TileEntity tile;
                    if (tag != null) {
                        tag = tag.copy();
                        tag.setInteger("x", pos.getX()); tag.setInteger("y", pos.getY()); tag.setInteger("z", pos.getZ());
                        tile = TileEntity.create(world, tag);
                    } else tile = block.createTileEntity(world, state);
                    if (tile == null) throw new MessageException("schematica.message.edit.tile_failed", pos.getX(), pos.getY(), pos.getZ());
                    tile.setPos(pos);
                    if (silentPlacement != null) silentPlacement.setTile(world, tile);
                    else {
                        world.setTileEntity(pos, tile);
                        tile.markDirty();
                    }
                }
                placed.set(index);
                blockCount++;
            } else throw new MessageException("schematica.message.edit.block_failed", pos.getX(), pos.getY(), pos.getZ());
            return false;
        }
        if (cancelled || entityCursor == entities.size()) return true;
        NBTTagCompound tag = entities.get(entityCursor++).copy();
        NBTTagList oldPos = tag.getTagList("Pos", 6);
        NBTTagList pos = new NBTTagList();
        pos.appendTag(new NBTTagDouble(oldPos.getDoubleAt(0) + x));
        pos.appendTag(new NBTTagDouble(oldPos.getDoubleAt(1) + y));
        pos.appendTag(new NBTTagDouble(oldPos.getDoubleAt(2) + z));
        tag.setTag("Pos", pos);
        if (tag.hasKey("TileX")) {
            tag.setInteger("TileX", tag.getInteger("TileX") + x);
            tag.setInteger("TileY", tag.getInteger("TileY") + y);
            tag.setInteger("TileZ", tag.getInteger("TileZ") + z);
        }
        tag.removeTag("UUIDMost"); tag.removeTag("UUIDLeast");
        Entity entity = EntityList.createEntityFromNBT(tag, world);
        if (entity != null && world.spawnEntity(entity)) entityCount++;
        return false;
    }
}
