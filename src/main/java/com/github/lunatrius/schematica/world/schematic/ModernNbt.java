// Block entity, entity and item NBT of 1.13+ files as 1.12 stores it, by Useleess, 2026.
package com.github.lunatrius.schematica.world.schematic;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.potion.PotionType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants.NBT;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.github.lunatrius.schematica.util.SchematicLimits;

/**
 * Flattened (1.13+) files store items as {id, Count, tag} (to 1.20.4) or {id, count, components} (1.20.5+) under
 * names 1.12 partly does not have. Stacks become 1.12 stacks: the 1.13 renames are undone, block items of blocks 1.12
 * lacks become the item of the block they are loaded as, and items 1.12 has no counterpart for are left out (counted in
 * {@link ImportReport#items()}). Names, lore, enchantments, damage, potions, colours, books, fireworks and container
 * contents carry over. Block entities take the id of the block they are loaded as (a barrel's items go into the chest).
 */
public final class ModernNbt {
    /** Item renames of 1.13 and later items with a 1.12 counterpart: name to "1.12 name" or "1.12 name:damage". */
    private static final Map<String, String> ITEMS = new HashMap<>();
    private static final Map<String, String> ENTITIES = new HashMap<>();
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] FIREWORK_SHAPES = {"small_ball", "large_ball", "star", "creeper", "burst"};
    /** 1.12 item frames face south, west, north, east (0-3); 1.13+ frames use the 3D index (down, up, north, south, west, east). */
    private static final int[] FRAME_FACING = {-1, -1, 2, 0, 1, 3};

    static {
        String[] dyes = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye",
            "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"};
        for (int i = 0; i < dyes.length; i++) item(dyes[i], "dye:" + i);
        item("rose_red", "dye:1"); item("cactus_green", "dye:2"); item("dandelion_yellow", "dye:11");
        item("black_dye", "dye:0"); item("brown_dye", "dye:3"); item("blue_dye", "dye:4"); item("white_dye", "dye:15");
        for (int i = 0; i < COLORS.length; i++) {
            item(COLORS[i] + "_bed", "bed:" + i);
            item(COLORS[i] + "_banner", "banner:" + (15 - i));
        }
        item("charcoal", "coal:1"); item("enchanted_golden_apple", "golden_apple:1");
        item("cod", "fish:0"); item("salmon", "fish:1"); item("tropical_fish", "fish:2"); item("pufferfish", "fish:3");
        item("cooked_cod", "cooked_fish:0"); item("cooked_salmon", "cooked_fish:1");
        for (String disc : new String[] {"13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward", "11", "wait"}) {
            item("music_disc_" + disc, "record_" + disc);
        }
        String[] skulls = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head"};
        for (int i = 0; i < skulls.length; i++) item(skulls[i], "skull:" + i);
        for (String wood : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"}) item(wood + "_sign", "sign");
        item("oak_boat", "boat"); item("oak_door", "wooden_door"); item("sugar_cane", "reeds");
        item("melon_slice", "melon"); item("glistering_melon_slice", "speckled_melon"); item("popped_chorus_fruit", "chorus_fruit_popped");
        item("firework_rocket", "fireworks"); item("firework_star", "firework_charge"); item("nether_brick", "netherbrick");
        String[][] entities = {{"snow_golem", "snowman"}, {"iron_golem", "villager_golem"}, {"evoker", "evocation_illager"},
            {"vindicator", "vindication_illager"}, {"illusioner", "illusion_illager"}, {"zombified_piglin", "zombie_pigman"},
            {"experience_orb", "xp_orb"}, {"experience_bottle", "xp_bottle"}, {"eye_of_ender", "eye_of_ender_signal"},
            {"firework_rocket", "fireworks_rocket"}, {"command_block_minecart", "commandblock_minecart"},
            {"end_crystal", "ender_crystal"}, {"oak_boat", "boat"}, {"glow_item_frame", "item_frame"}};
        for (String[] pair : entities) ENTITIES.put("minecraft:" + pair[0], "minecraft:" + pair[1]);
    }

    private ModernNbt() {}

    private static void item(String modern, String legacy) { ITEMS.put("minecraft:" + modern, legacy); }

    /** A block entity of a file as 1.12 stores it: 1.12 and older files through LegacyNbt, flattened ones here. */
    public static NBTTagCompound tile(NBTTagCompound tag, boolean legacy, IBlockState placed) {
        return legacy ? LegacyNbt.tileTo112(tag) : tileTo112(tag, placed);
    }

    /** An entity of a file as 1.12 stores it, or null when 1.12 cannot have it. */
    public static NBTTagCompound entity(NBTTagCompound tag, boolean legacy) {
        return legacy ? LegacyNbt.entityTo112(tag) : entityTo112(tag);
    }

    /** A block entity of a flattened file, for the 1.12 state it is loaded at (null if the block keeps none). */
    public static NBTTagCompound tileTo112(NBTTagCompound source, IBlockState placed) {
        NBTTagCompound tag = source.copy();
        if (placed != null && placed.getBlock().hasTileEntity(placed)) {
            TileEntity standIn = placed.getBlock().createTileEntity(null, placed);
            ResourceLocation id = standIn == null ? null : TileEntity.getKey(standIn.getClass());
            if (id != null) tag.setString("id", id.toString());
        }
        if (tag.hasKey("CustomName", NBT.TAG_STRING) || tag.hasKey("CustomName", NBT.TAG_COMPOUND)) {
            tag.setString("CustomName", plain(tag.getTag("CustomName")));
        }
        if (tag.hasKey("front_text", NBT.TAG_COMPOUND)) {
            // 1.20+ signs: front lines (JSON strings, or NBT components from 1.21.5); 1.12 has no back side
            NBTBase messages = tag.getCompoundTag("front_text").getTag("messages");
            int lines = messages instanceof NBTTagList ? ((NBTTagList) messages).tagCount() : 0;
            for (int i = 0; i < 4; i++) tag.setString("Text" + (i + 1), json(i < lines ? ((NBTTagList) messages).get(i) : new NBTTagString("")));
        }
        stacks(tag, 0);
        return tag;
    }

    /** An entity of a flattened file as 1.12 stores it; null when 1.12 cannot have it (a floor or ceiling item frame). */
    public static NBTTagCompound entityTo112(NBTTagCompound source) {
        NBTTagCompound tag = source.copy();
        String id = tag.getString("id");
        if (ENTITIES.containsKey(id)) tag.setString("id", ENTITIES.get(id));
        if (id.endsWith("item_frame")) {
            int facing = tag.getByte("Facing");
            if (facing < 0 || facing >= FRAME_FACING.length || FRAME_FACING[facing] < 0) return null;
            tag.setByte("Facing", (byte) FRAME_FACING[facing]);
        }
        if (tag.hasKey("CustomName", NBT.TAG_STRING) || tag.hasKey("CustomName", NBT.TAG_COMPOUND)) {
            tag.setString("CustomName", plain(tag.getTag("CustomName")));
        }
        if (tag.hasKey("equipment", NBT.TAG_COMPOUND)) {
            // 1.21.5+: slots by name; 1.12 has hand and armour lists
            NBTTagCompound equipment = tag.getCompoundTag("equipment");
            NBTTagList hand = new NBTTagList(), armor = new NBTTagList();
            for (String slot : new String[] {"mainhand", "offhand"}) hand.appendTag(equipment.getCompoundTag(slot));
            for (String slot : new String[] {"feet", "legs", "chest", "head"}) armor.appendTag(equipment.getCompoundTag(slot));
            tag.setTag("HandItems", hand);
            tag.setTag("ArmorItems", armor);
            tag.removeTag("equipment");
        }
        NBTTagList passengers = tag.getTagList("Passengers", NBT.TAG_COMPOUND), kept = new NBTTagList();
        for (int i = 0; i < passengers.tagCount(); i++) {
            NBTTagCompound passenger = entityTo112(passengers.getCompoundTagAt(i));
            if (passenger != null) kept.appendTag(passenger);
        }
        if (passengers.tagCount() > 0) tag.setTag("Passengers", kept);
        stacks(tag, 0);
        return tag;
    }

    /** Keys whose value is a stack or a list of stacks (in block entities, entities and nested items). */
    private static final String[] STACK_KEYS = {"Items", "Item", "RecordItem", "HandItems", "ArmorItems", "Inventory", "Book"};

    private static void stacks(NBTTagCompound tag, int depth) {
        for (String key : STACK_KEYS) {
            if (tag.hasKey(key, NBT.TAG_COMPOUND)) {
                NBTTagCompound stack = itemTo112(tag.getCompoundTag(key), depth);
                if (stack == null) tag.removeTag(key);
                else tag.setTag(key, stack);
            } else if (tag.hasKey(key, NBT.TAG_LIST)) {
                NBTTagList list = tag.getTagList(key, NBT.TAG_COMPOUND), converted = new NBTTagList();
                // hand and armour lists are positional: a missing item keeps its place as an empty stack
                boolean positional = key.equals("HandItems") || key.equals("ArmorItems");
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound stack = itemTo112(list.getCompoundTagAt(i), depth);
                    if (stack != null) converted.appendTag(stack);
                    else if (positional) converted.appendTag(new NBTTagCompound());
                }
                tag.setTag(key, converted);
            }
        }
    }

    /** A stack of a flattened file as 1.12 stores it, or null when it is empty or 1.12 has no such item. */
    public static NBTTagCompound itemTo112(NBTTagCompound stack, int depth) {
        if (depth > SchematicLimits.MAX_NBT_DEPTH) throw new IllegalArgumentException("NBT nesting is too deep");
        String id = stack.getString("id");
        if (id.isEmpty() || id.equals("minecraft:air")) return null;
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        int count = stack.hasKey("count", NBT.TAG_ANY_NUMERIC) ? stack.getInteger("count")
            : stack.hasKey("Count", NBT.TAG_ANY_NUMERIC) ? stack.getInteger("Count") : 1;
        if (count <= 0) return null;
        NBTTagCompound modern = stack.hasKey("tag", NBT.TAG_COMPOUND) ? stack.getCompoundTag("tag") : new NBTTagCompound();
        NBTTagCompound components = stack.getCompoundTag("components");

        String[] legacy = legacyItem(id, components, modern);
        if (legacy == null) {
            ItemIdMaps.Result report = ImportReport.items();
            report.removed++;
            report.unknownItems.add(id);
            return null;
        }
        NBTTagCompound result = new NBTTagCompound();
        if (stack.hasKey("Slot", NBT.TAG_ANY_NUMERIC)) result.setByte("Slot", stack.getByte("Slot"));
        result.setString("id", legacy[0]);
        result.setByte("Count", (byte) Math.min(count, 64));
        int damage = Integer.parseInt(legacy[1]);
        if (components.hasKey("minecraft:damage", NBT.TAG_ANY_NUMERIC)) damage = components.getInteger("minecraft:damage");
        else if (modern.hasKey("Damage", NBT.TAG_ANY_NUMERIC) && damage == 0) damage = modern.getInteger("Damage");
        result.setShort("Damage", (short) damage);
        NBTTagCompound tag = tag(id, legacy[0], modern, components, depth);
        if (!tag.hasNoTags()) result.setTag("tag", tag);
        return result;
    }

    /** {1.12 name, damage} of a flattened item name, or null when 1.12 has no such item. */
    static String[] legacyItem(String id, NBTTagCompound components, NBTTagCompound modern) {
        if (ITEMS.containsKey(id)) {
            String legacy = ITEMS.get(id);
            if (legacy == null) return null;
            int colon = legacy.indexOf(':');
            return new String[] {"minecraft:" + (colon < 0 ? legacy : legacy.substring(0, colon)), colon < 0 ? "0" : legacy.substring(colon + 1)};
        }
        if (id.endsWith("_spawn_egg")) {
            String entity = entityName("minecraft:" + id.substring(id.indexOf(':') + 1, id.length() - 10));
            return entity == null ? null : new String[] {"minecraft:spawn_egg", "0"};
        }
        if (id.equals("minecraft:filled_map")) {
            int map = components.hasKey("minecraft:map_id") ? components.getInteger("minecraft:map_id") : modern.getInteger("map");
            return new String[] {id, Integer.toString(map)};
        }
        // a block item: the item of the 1.12 block it loads as, in its plain form (a single bottom slab, not a double one)
        NBTTagCompound entry = new NBTTagCompound(), properties = new NBTTagCompound();
        entry.setString("Name", id);
        properties.setString("type", "bottom"); properties.setString("half", "lower"); properties.setString("part", "foot");
        entry.setTag("Properties", properties);
        IBlockState state = BlockStates.fromModern(entry);
        if (state == null && isMissingBlock(id)) state = MissingBlocks.replacement(id);
        if (state != null && state.getBlock() != Blocks.AIR) {
            net.minecraft.item.ItemStack item = blockItem(state);
            if (!item.isEmpty()) return new String[] {Item.REGISTRY.getNameForObject(item.getItem()).toString(), Integer.toString(item.getMetadata())};
        }
        // an item 1.12 has under the same name
        Item item = Item.getByNameOrId(id);
        return item == null || item == net.minecraft.init.Items.AIR ? null : new String[] {id, "0"};
    }

    /** The item of a block: what pick block gives (tall grass, not a dead bush; a door's item), else what it drops as. */
    private static net.minecraft.item.ItemStack blockItem(IBlockState state) {
        try {
            net.minecraft.item.ItemStack picked = state.getBlock().getItem(null, net.minecraft.util.math.BlockPos.ORIGIN, state);
            if (!picked.isEmpty()) return picked;
        } catch (RuntimeException e) {
            // a block that needs the world for its item
        }
        Item item = Item.getItemFromBlock(state.getBlock());
        return item == net.minecraft.init.Items.AIR ? net.minecraft.item.ItemStack.EMPTY : new net.minecraft.item.ItemStack(item, 1, state.getBlock().damageDropped(state));
    }

    /** Whether a name 1.12 does not have is a block: missing in this file, picked before, or guessed by the table. */
    private static boolean isMissingBlock(String id) {
        return ImportReport.reportedMissing(id) || MissingBlocks.choice(id) != null || MissingBlockGuesses.resolvable(id);
    }

    /** The 1.12 tag of a stack from its 1.13-1.20.4 tag or 1.20.5+ components. */
    private static NBTTagCompound tag(String id, String legacyId, NBTTagCompound modern, NBTTagCompound components, int depth) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound display = new NBTTagCompound();
        NBTBase name = components.hasKey("minecraft:custom_name") ? components.getTag("minecraft:custom_name") : modern.getCompoundTag("display").getTag("Name");
        if (name != null) display.setString("Name", plain(name));
        NBTBase lore = components.hasKey("minecraft:lore") ? components.getTag("minecraft:lore") : modern.getCompoundTag("display").getTag("Lore");
        if (lore instanceof NBTTagList && ((NBTTagList) lore).tagCount() > 0) {
            NBTTagList lines = new NBTTagList();
            for (int i = 0; i < ((NBTTagList) lore).tagCount(); i++) lines.appendTag(new NBTTagString(plain(((NBTTagList) lore).get(i))));
            display.setTag("Lore", lines);
        }
        NBTBase color = components.hasKey("minecraft:dyed_color") ? components.getTag("minecraft:dyed_color") : modern.getCompoundTag("display").getTag("color");
        if (color instanceof NBTPrimitive) display.setInteger("color", ((NBTPrimitive) color).getInt());
        else if (color instanceof NBTTagCompound) display.setInteger("color", ((NBTTagCompound) color).getInteger("rgb"));
        if (!display.hasNoTags()) tag.setTag("display", display);

        NBTTagList enchantments = enchantments(components.hasKey("minecraft:enchantments") ? components.getTag("minecraft:enchantments") : modern.getTag("Enchantments"));
        if (enchantments.tagCount() > 0) tag.setTag("ench", enchantments);
        NBTTagList stored = enchantments(components.hasKey("minecraft:stored_enchantments") ? components.getTag("minecraft:stored_enchantments") : modern.getTag("StoredEnchantments"));
        if (stored.tagCount() > 0) tag.setTag("StoredEnchantments", stored);
        if (components.hasKey("minecraft:unbreakable") || modern.getBoolean("Unbreakable")) tag.setBoolean("Unbreakable", true);

        String potion = null;
        if (components.hasKey("minecraft:potion_contents", NBT.TAG_STRING)) potion = components.getString("minecraft:potion_contents");
        else if (components.hasKey("minecraft:potion_contents", NBT.TAG_COMPOUND)) potion = components.getCompoundTag("minecraft:potion_contents").getString("potion");
        else if (modern.hasKey("Potion", NBT.TAG_STRING)) potion = modern.getString("Potion");
        if (potion != null && !potion.isEmpty()) tag.setString("Potion", PotionType.getPotionTypeForName(potion) != null ? potion : "minecraft:water");

        if (legacyId.equals("minecraft:spawn_egg")) {
            NBTTagCompound entity = components.hasKey("minecraft:entity_data") ? components.getCompoundTag("minecraft:entity_data").copy() : modern.getCompoundTag("EntityTag").copy();
            String type = entity.hasKey("id") ? entity.getString("id") : id.substring(0, id.length() - "_spawn_egg".length());
            entity.setString("id", entityName(type) == null ? type : entityName(type));
            tag.setTag("EntityTag", entity);
        }
        if (components.hasKey("minecraft:fireworks", NBT.TAG_COMPOUND)) {
            NBTTagCompound fireworks = components.getCompoundTag("minecraft:fireworks"), converted = new NBTTagCompound();
            converted.setByte("Flight", (byte) fireworks.getInteger("flight_duration"));
            NBTTagList explosions = new NBTTagList();
            NBTTagList list = fireworks.getTagList("explosions", NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) explosions.appendTag(explosion(list.getCompoundTagAt(i)));
            converted.setTag("Explosions", explosions);
            tag.setTag("Fireworks", converted);
        } else if (modern.hasKey("Fireworks", NBT.TAG_COMPOUND)) tag.setTag("Fireworks", modern.getTag("Fireworks").copy());
        if (components.hasKey("minecraft:firework_explosion", NBT.TAG_COMPOUND)) tag.setTag("Explosion", explosion(components.getCompoundTag("minecraft:firework_explosion")));
        else if (modern.hasKey("Explosion", NBT.TAG_COMPOUND)) tag.setTag("Explosion", modern.getTag("Explosion").copy());

        books(tag, modern, components);
        if (components.hasKey("minecraft:profile")) {
            NBTBase profile = components.getTag("minecraft:profile");
            String owner = profile instanceof NBTTagString ? ((NBTTagString) profile).getString() : ((NBTTagCompound) profile).getString("name");
            if (!owner.isEmpty()) tag.setString("SkullOwner", owner);
        } else if (modern.hasKey("SkullOwner", NBT.TAG_STRING)) tag.setString("SkullOwner", modern.getString("SkullOwner"));
        else if (modern.hasKey("SkullOwner", NBT.TAG_COMPOUND) && !modern.getCompoundTag("SkullOwner").getString("Name").isEmpty()) {
            tag.setString("SkullOwner", modern.getCompoundTag("SkullOwner").getString("Name"));
        }

        // contents of shulker boxes and other containers carried as items
        NBTTagList contents = new NBTTagList();
        if (components.hasKey("minecraft:container", NBT.TAG_LIST)) {
            NBTTagList slots = components.getTagList("minecraft:container", NBT.TAG_COMPOUND);
            for (int i = 0; i < slots.tagCount(); i++) {
                NBTTagCompound item = slots.getCompoundTagAt(i).getCompoundTag("item").copy();
                item.setByte("Slot", (byte) slots.getCompoundTagAt(i).getInteger("slot"));
                NBTTagCompound converted = itemTo112(item, depth + 1);
                if (converted != null) contents.appendTag(converted);
            }
        } else if (modern.getCompoundTag("BlockEntityTag").hasKey("Items", NBT.TAG_LIST)) {
            NBTTagList items = modern.getCompoundTag("BlockEntityTag").getTagList("Items", NBT.TAG_COMPOUND);
            for (int i = 0; i < items.tagCount(); i++) {
                NBTTagCompound converted = itemTo112(items.getCompoundTagAt(i), depth + 1);
                if (converted != null) contents.appendTag(converted);
            }
        }
        if (contents.tagCount() > 0) {
            NBTTagCompound blockEntity = new NBTTagCompound();
            blockEntity.setTag("Items", contents);
            tag.setTag("BlockEntityTag", blockEntity);
        }
        return tag;
    }

    private static void books(NBTTagCompound tag, NBTTagCompound modern, NBTTagCompound components) {
        NBTTagCompound content = components.hasKey("minecraft:written_book_content", NBT.TAG_COMPOUND) ? components.getCompoundTag("minecraft:written_book_content")
            : components.hasKey("minecraft:writable_book_content", NBT.TAG_COMPOUND) ? components.getCompoundTag("minecraft:writable_book_content") : null;
        boolean written = components.hasKey("minecraft:written_book_content");
        if (content == null) {
            for (String key : new String[] {"pages", "title", "author", "generation", "resolved"}) if (modern.hasKey(key)) tag.setTag(key, modern.getTag(key).copy());
            return;
        }
        NBTTagList pages = new NBTTagList();
        NBTBase pageList = content.getTag("pages");
        if (pageList instanceof NBTTagList) {
            for (int i = 0; i < ((NBTTagList) pageList).tagCount(); i++) {
                NBTBase page = ((NBTTagList) pageList).get(i);
                NBTBase raw = page instanceof NBTTagCompound ? ((NBTTagCompound) page).getTag("raw") : page;
                // written pages are JSON text in 1.12, writable pages plain text
                pages.appendTag(new NBTTagString(raw instanceof NBTTagString ? ((NBTTagString) raw).getString() : written ? json(raw) : plain(raw)));
            }
        }
        tag.setTag("pages", pages);
        if (written) {
            NBTBase title = content.getTag("title");
            NBTBase raw = title instanceof NBTTagCompound ? ((NBTTagCompound) title).getTag("raw") : title;
            tag.setString("title", raw instanceof NBTTagString ? ((NBTTagString) raw).getString() : "");
            tag.setString("author", content.getString("author"));
            tag.setInteger("generation", content.getInteger("generation"));
            tag.setBoolean("resolved", true);
        }
    }

    private static NBTTagCompound explosion(NBTTagCompound explosion) {
        NBTTagCompound converted = new NBTTagCompound();
        int shape = java.util.Arrays.asList(FIREWORK_SHAPES).indexOf(explosion.getString("shape"));
        converted.setByte("Type", (byte) Math.max(shape, 0));
        converted.setTag("Colors", new NBTTagIntArray(explosion.getIntArray("colors")));
        converted.setTag("FadeColors", new NBTTagIntArray(explosion.getIntArray("fade_colors")));
        converted.setBoolean("Trail", explosion.getBoolean("has_trail"));
        converted.setBoolean("Flicker", explosion.getBoolean("has_twinkle"));
        return converted;
    }

    /** 1.12 enchantment entries ({id: short, lvl: short}) of a 1.13-1.20.4 list or a 1.20.5+ component. */
    private static NBTTagList enchantments(NBTBase source) {
        Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        if (source instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) source;
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                levels.put(entry.getString("id"), entry.getInteger("lvl"));
            }
        } else if (source instanceof NBTTagCompound) {
            NBTTagCompound map = (NBTTagCompound) source;
            if (map.hasKey("levels", NBT.TAG_COMPOUND)) map = map.getCompoundTag("levels");
            for (String key : map.getKeySet()) if (map.hasKey(key, NBT.TAG_ANY_NUMERIC)) levels.put(key, map.getInteger(key));
        }
        NBTTagList result = new NBTTagList();
        for (Map.Entry<String, Integer> level : levels.entrySet()) {
            String name = level.getKey().equals("minecraft:sweeping_edge") ? "minecraft:sweeping" : level.getKey();
            Enchantment enchantment = Enchantment.getEnchantmentByLocation(name);
            if (enchantment == null) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setShort("id", (short) Enchantment.getEnchantmentID(enchantment));
            entry.setShort("lvl", level.getValue().shortValue());
            result.appendTag(entry);
        }
        return result;
    }

    /** The 1.12 name of an entity, or null when 1.12 has no such entity. */
    private static String entityName(String id) {
        String name = ENTITIES.containsKey(id) ? ENTITIES.get(id) : id;
        return EntityList.isRegistered(new ResourceLocation(name)) ? name : null;
    }

    /** The plain text of a text component: JSON text (1.13+), a 1.21.5+ NBT component, or a plain string. */
    static String plain(NBTBase text) {
        if (text instanceof NBTTagString) {
            String value = ((NBTTagString) text).getString();
            try {
                return plain(new JsonParser().parse(value));
            } catch (RuntimeException e) {
                return value;
            }
        }
        if (text instanceof NBTTagCompound) {
            NBTTagCompound component = (NBTTagCompound) text;
            StringBuilder out = new StringBuilder(component.getString("text"));
            NBTTagList extra = component.getTagList("extra", NBT.TAG_COMPOUND);
            for (int i = 0; i < extra.tagCount(); i++) out.append(plain(extra.getCompoundTagAt(i)));
            if (component.getTagId("extra") == NBT.TAG_LIST && extra.tagCount() == 0) {
                NBTTagList strings = component.getTagList("extra", NBT.TAG_STRING);
                for (int i = 0; i < strings.tagCount(); i++) out.append(strings.getStringTagAt(i));
            }
            return out.toString();
        }
        if (text instanceof NBTTagList) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < ((NBTTagList) text).tagCount(); i++) out.append(plain(((NBTTagList) text).get(i)));
            return out.toString();
        }
        return "";
    }

    private static String plain(JsonElement json) {
        if (json == null || json.isJsonNull()) return "";
        if (json.isJsonPrimitive()) return json.getAsString();
        StringBuilder out = new StringBuilder();
        if (json.isJsonArray()) {
            for (JsonElement part : json.getAsJsonArray()) out.append(plain(part));
            return out.toString();
        }
        JsonObject object = json.getAsJsonObject();
        if (object.has("text")) out.append(object.get("text").getAsString());
        if (object.has("extra")) for (JsonElement part : object.getAsJsonArray("extra")) out.append(plain(part));
        return out.toString();
    }

    /** A sign line as 1.12 JSON text: JSON strings stay, NBT components become {"text": ...}. */
    private static String json(NBTBase text) {
        if (text instanceof NBTTagString) {
            String value = ((NBTTagString) text).getString();
            try {
                JsonElement parsed = new JsonParser().parse(value);
                if (parsed.isJsonObject() || parsed.isJsonArray()) return value;
            } catch (RuntimeException e) {
                // a plain string
            }
            return LegacyNbt.json(value);
        }
        return LegacyNbt.json(plain(text));
    }
}
