// SPDX-License-Identifier: LGPL-3.0-only
// Block entity, entity and item NBT between 1.7.10 and 1.12 (vanilla data fixer steps up to 1.12), by HackerRouter, 2026.
package com.github.lunatrius.schematica.world.schematic;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import com.google.gson.JsonObject;

import com.github.lunatrius.schematica.util.SchematicLimits;

/**
 * 1.12 files carry data version 1343, so readers apply no fixes from before 1.12: block entity and entity ids,
 * string item ids, sign JSON text, spawner data and equipment lists are converted here as the vanilla fixers would.
 */
public final class LegacyNbt {
    private static final Map<String, String> TILES = new HashMap<>();
    private static final Map<String, String> ENTITIES = new HashMap<>();
    private static final String[] HORSES = {"horse", "donkey", "mule", "zombie_horse", "skeleton_horse"};
    private static final int MAX_RIDING = 16;
    /** The 1.9 ItemPotionFix table: potion damage & 127 to the potion name (null is water). */
    private static final String[] POTIONS = {
        "water", "regeneration", "swiftness", "fire_resistance", "poison", "healing", "night_vision", null,
        "weakness", "strength", "slowness", "leaping", "harming", "water_breathing", "invisibility", null,
        "awkward", "regeneration", "swiftness", "fire_resistance", "poison", "healing", "night_vision", null,
        "weakness", "strength", "slowness", "leaping", "harming", "water_breathing", "invisibility", null,
        "thick", "strong_regeneration", "strong_swiftness", "fire_resistance", "strong_poison", "strong_healing", "night_vision", null,
        "weakness", "strong_strength", "slowness", "strong_leaping", "strong_harming", "water_breathing", "invisibility", null,
        null, "strong_regeneration", "strong_swiftness", "fire_resistance", "strong_poison", "strong_healing", "night_vision", null,
        "weakness", "strong_strength", "slowness", "strong_leaping", "strong_harming", "water_breathing", "invisibility", null,
        "mundane", "long_regeneration", "long_swiftness", "long_fire_resistance", "long_poison", "healing", "long_night_vision", null,
        "long_weakness", "long_strength", "long_slowness", "long_leaping", "harming", "long_water_breathing", "long_invisibility", null,
        "awkward", "long_regeneration", "long_swiftness", "long_fire_resistance", "long_poison", "healing", "long_night_vision", null,
        "long_weakness", "long_strength", "long_slowness", "long_leaping", "harming", "long_water_breathing", "long_invisibility", null,
        "thick", "regeneration", "swiftness", "long_fire_resistance", "poison", "strong_healing", "long_night_vision", null,
        "long_weakness", "strength", "long_slowness", "leaping", "strong_harming", "long_water_breathing", "long_invisibility", null,
        null, "regeneration", "swiftness", "long_fire_resistance", "poison", "strong_healing", "long_night_vision", null,
        "long_weakness", "strength", "long_slowness", "leaping", "strong_harming", "long_water_breathing", "long_invisibility", null,
    };

    /** The numeric entity ids that 1.7.10 spawn eggs carry as damage. */
    private static final Map<Integer, String> EGG_ENTITIES = new HashMap<>();

    static {
        int[] ids = {50, 51, 52, 54, 55, 56, 57, 58, 59, 60, 61, 62, 65, 66, 90, 91, 92, 93, 94, 95, 96, 98, 100, 120};
        String[] names = {"Creeper", "Skeleton", "Spider", "Zombie", "Slime", "Ghast", "PigZombie", "Enderman", "CaveSpider",
            "Silverfish", "Blaze", "LavaSlime", "Bat", "Witch", "Pig", "Sheep", "Cow", "Chicken", "Squid", "Wolf",
            "MushroomCow", "Ozelot", "EntityHorse", "Villager"};
        for (int i = 0; i < ids.length; i++) EGG_ENTITIES.put(ids[i], names[i]);
        tile("Furnace", "furnace"); tile("Chest", "chest"); tile("EnderChest", "ender_chest");
        tile("RecordPlayer", "jukebox"); tile("Trap", "dispenser"); tile("Dropper", "dropper");
        tile("Sign", "sign"); tile("MobSpawner", "mob_spawner"); tile("Music", "noteblock");
        tile("Piston", "piston"); tile("Cauldron", "brewing_stand"); tile("EnchantTable", "enchanting_table");
        tile("Airportal", "end_portal"); tile("Control", "command_block"); tile("Beacon", "beacon");
        tile("Skull", "skull"); tile("DLDetector", "daylight_detector"); tile("Hopper", "hopper");
        tile("Comparator", "comparator"); tile("FlowerPot", "flower_pot");
        String[][] entities = {
            {"Item", "item"}, {"XPOrb", "xp_orb"}, {"LeashKnot", "leash_knot"}, {"Painting", "painting"},
            {"Arrow", "arrow"}, {"Snowball", "snowball"}, {"Fireball", "fireball"}, {"SmallFireball", "small_fireball"},
            {"ThrownEnderpearl", "ender_pearl"}, {"EyeOfEnderSignal", "eye_of_ender_signal"}, {"ThrownPotion", "potion"},
            {"ThrownExpBottle", "xp_bottle"}, {"ItemFrame", "item_frame"}, {"WitherSkull", "wither_skull"},
            {"PrimedTnt", "tnt"}, {"FallingSand", "falling_block"}, {"FireworksRocketEntity", "fireworks_rocket"},
            {"Boat", "boat"}, {"MinecartRideable", "minecart"}, {"MinecartChest", "chest_minecart"},
            {"MinecartFurnace", "furnace_minecart"}, {"MinecartTNT", "tnt_minecart"}, {"MinecartHopper", "hopper_minecart"},
            {"MinecartSpawner", "spawner_minecart"}, {"MinecartCommandBlock", "commandblock_minecart"},
            {"Creeper", "creeper"}, {"Skeleton", "skeleton"}, {"Spider", "spider"}, {"Giant", "giant"},
            {"Zombie", "zombie"}, {"Slime", "slime"}, {"Ghast", "ghast"}, {"PigZombie", "zombie_pigman"},
            {"Enderman", "enderman"}, {"CaveSpider", "cave_spider"}, {"Silverfish", "silverfish"}, {"Blaze", "blaze"},
            {"LavaSlime", "magma_cube"}, {"EnderDragon", "ender_dragon"}, {"WitherBoss", "wither"}, {"Bat", "bat"},
            {"Witch", "witch"}, {"Pig", "pig"}, {"Sheep", "sheep"}, {"Cow", "cow"}, {"Chicken", "chicken"},
            {"Squid", "squid"}, {"Wolf", "wolf"}, {"MushroomCow", "mooshroom"}, {"SnowMan", "snowman"},
            {"Ozelot", "ocelot"}, {"VillagerGolem", "villager_golem"}, {"EntityHorse", "horse"},
            {"Villager", "villager"}, {"EnderCrystal", "ender_crystal"}};
        for (String[] pair : entities) {
            ENTITIES.put(pair[0], "minecraft:" + pair[1]);
        }
    }

    private LegacyNbt() {}

    private static void tile(String legacy, String modern) {
        TILES.put(legacy, "minecraft:" + modern);
    }

    /** A 1.7.10 item stack tag ({id, Count, Damage, tag}) as 1.12 stores it. */
    public static NBTTagCompound itemTo112(NBTTagCompound source) {
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        itemsTo112(tag, 0);
        return tag;
    }

    /** A 1.7.10 block entity tag as 1.12 stores it. */
    public static NBTTagCompound tileTo112(NBTTagCompound source) {
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        String id = tag.getString("id");
        if (TILES.containsKey(id)) tag.setString("id", TILES.get(id));
        if ("Sign".equals(id)) {
            for (int i = 1; i <= 4; i++) tag.setString("Text" + i, json(tag.getString("Text" + i)));
        } else if ("MobSpawner".equals(id)) {
            spawnerTo112(tag);
        } else if ("FlowerPot".equals(id) && tag.hasKey("Item", NBT.TAG_ANY_NUMERIC)) {
            Item item = Item.getItemById(tag.getInteger("Item"));
            String name = item == null ? null : net.minecraft.item.Item.REGISTRY.getNameForObject(item) == null ? null : net.minecraft.item.Item.REGISTRY.getNameForObject(item).toString();
            tag.setString("Item", name == null ? "minecraft:air" : name);
        }
        itemsTo112(tag, 0);
        return tag;
    }

    /** A 1.7.10 entity tag as 1.12 stores it; a rider becomes a passenger of its mount, which becomes the stored entity. */
    public static NBTTagCompound entityTo112(NBTTagCompound source) {
        NBTTagCompound root = null, rider = null;
        NBTTagCompound current = source;
        for (int depth = 0; current != null && depth < MAX_RIDING; depth++) {
            NBTTagCompound mount = current.hasKey("Riding", NBT.TAG_COMPOUND) ? current.getCompoundTag("Riding") : null;
            NBTTagCompound converted = singleEntityTo112(current);
            converted.removeTag("Riding");
            if (rider != null) {
                NBTTagList passengers = new NBTTagList();
                passengers.appendTag(rider);
                converted.setTag("Passengers", passengers);
            }
            rider = converted;
            root = converted;
            current = mount;
        }
        return root;
    }

    private static NBTTagCompound singleEntityTo112(NBTTagCompound source) {
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        entityIdTo112(tag);
        if (tag.hasKey("Equipment", NBT.TAG_LIST)) {
            NBTTagList equipment = tag.getTagList("Equipment", NBT.TAG_COMPOUND);
            NBTTagList hand = new NBTTagList(), armor = new NBTTagList();
            for (int i = 0; i < 5; i++) {
                NBTTagCompound item = i < equipment.tagCount() ? equipment.getCompoundTagAt(i) : new NBTTagCompound();
                (i == 0 ? hand : armor).appendTag(item);
            }
            hand.appendTag(new NBTTagCompound());
            tag.setTag("HandItems", hand);
            tag.setTag("ArmorItems", armor);
            tag.removeTag("Equipment");
        }
        if (tag.hasKey("DropChances", NBT.TAG_LIST)) {
            NBTTagList chances = tag.getTagList("DropChances", NBT.TAG_FLOAT);
            NBTTagList hand = new NBTTagList(), armor = new NBTTagList();
            for (int i = 0; i < 5; i++) (i == 0 ? hand : armor).appendTag(new NBTTagFloat(i < chances.tagCount() ? chances.getFloatAt(i) : 0.085F));
            hand.appendTag(new NBTTagFloat(0.085F));
            tag.setTag("HandDropChances", hand);
            tag.setTag("ArmorDropChances", armor);
            tag.removeTag("DropChances");
        }
        itemsTo112(tag, 0);
        return tag;
    }

    private static void entityIdTo112(NBTTagCompound tag) {
        String id = tag.getString("id");
        if ("Skeleton".equals(id) && tag.getByte("SkeletonType") == 1) tag.setString("id", "minecraft:wither_skeleton");
        else if ("Zombie".equals(id) && tag.getBoolean("IsVillager")) {
            tag.setString("id", "minecraft:zombie_villager");
            if (tag.hasKey("VillagerProfession")) tag.setInteger("Profession", tag.getInteger("VillagerProfession"));
        } else if ("EntityHorse".equals(id)) {
            int type = tag.getInteger("Type");
            tag.setString("id", "minecraft:" + HORSES[type >= 0 && type < HORSES.length ? type : 0]);
        } else if (ENTITIES.containsKey(id)) tag.setString("id", ENTITIES.get(id));
        tag.removeTag("SkeletonType");
        tag.removeTag("IsVillager");
    }

    private static void spawnerTo112(NBTTagCompound tag) {
        if (tag.hasKey("EntityId", NBT.TAG_STRING)) {
            NBTTagCompound data = tag.getCompoundTag("SpawnData");
            data.setString("id", tag.getString("EntityId"));
            entityIdTo112(data);
            tag.setTag("SpawnData", data);
            tag.removeTag("EntityId");
        }
        NBTTagList potentials = tag.getTagList("SpawnPotentials", NBT.TAG_COMPOUND), converted = new NBTTagList();
        for (int i = 0; i < potentials.tagCount(); i++) {
            NBTTagCompound entry = potentials.getCompoundTagAt(i), result = new NBTTagCompound();
            NBTTagCompound entity = entry.hasKey("Properties", NBT.TAG_COMPOUND) ? entry.getCompoundTag("Properties") : new NBTTagCompound();
            entity.setString("id", entry.getString("Type"));
            entityIdTo112(entity);
            result.setTag("Entity", entity);
            result.setInteger("Weight", entry.getInteger("Weight"));
            converted.appendTag(result);
        }
        if (potentials.tagCount() > 0) tag.setTag("SpawnPotentials", converted);
    }

    private static boolean itemStack(NBTTagCompound tag) {
        return tag.hasKey("Count", NBT.TAG_BYTE) && tag.hasKey("id");
    }

    /** Numeric item ids in every nested stack become registry names. */
    private static void itemsTo112(NBTTagCompound tag, int depth) {
        if (depth > SchematicLimits.MAX_NBT_DEPTH) throw new IllegalArgumentException("NBT nesting is too deep");
        if (itemStack(tag) && tag.hasKey("id", NBT.TAG_SHORT)) {
            Item item = Item.getItemById(tag.getShort("id"));
            String name = item == null ? null : net.minecraft.item.Item.REGISTRY.getNameForObject(item) == null ? null : net.minecraft.item.Item.REGISTRY.getNameForObject(item).toString();
            if (name != null) tag.setString("id", name);
        }
        if (itemStack(tag) && tag.hasKey("id", NBT.TAG_STRING)) itemDataTo112(tag);
        for (Object key : tag.getKeySet()) visit(tag.getTag((String) key), depth);
    }

    /** ItemPotionFix and ItemSpawnEggFix: the damage of potions and spawn eggs moves into their tag. */
    static void itemDataTo112(NBTTagCompound stack) {
        String id = stack.getString("id");
        int damage = stack.getShort("Damage");
        if ("minecraft:potion".equals(id)) {
            NBTTagCompound tag = stack.getCompoundTag("tag");
            if (!tag.hasKey("Potion", NBT.TAG_STRING)) {
                String potion = POTIONS[damage & 127];
                tag.setString("Potion", "minecraft:" + (potion == null ? "water" : potion));
            }
            stack.setTag("tag", tag);
            if ((damage & 16384) != 0) stack.setString("id", "minecraft:splash_potion");
            stack.setShort("Damage", (short) 0);
        } else if ("minecraft:spawn_egg".equals(id)) {
            String legacy = EGG_ENTITIES.get(damage);
            String modern = legacy == null ? null : ENTITIES.get(legacy);
            if (modern == null && legacy != null) modern = legacy;
            NBTTagCompound tag = stack.getCompoundTag("tag");
            NBTTagCompound entity = tag.getCompoundTag("EntityTag");
            if (modern != null && !entity.hasKey("id", NBT.TAG_STRING)) {
                entity.setString("id", modern);
                tag.setTag("EntityTag", entity);
                stack.setTag("tag", tag);
            }
            stack.setShort("Damage", (short) 0);
        }
    }

    private static void visit(NBTBase child, int depth) {
        if (child instanceof NBTTagCompound) {
            itemsTo112((NBTTagCompound) child, depth + 1);
        } else if (child instanceof NBTTagList && ((NBTTagList) child).getTagType() == NBT.TAG_COMPOUND) {
            NBTTagList list = (NBTTagList) child;
            for (int i = 0; i < list.tagCount(); i++) itemsTo112(list.getCompoundTagAt(i), depth + 1);
        }
    }

    /** Sign line as a JSON text component, as the 1.8 sign fixer stores it. */
    static String json(String text) {
        JsonObject object = new JsonObject();
        object.addProperty("text", text);
        return object.toString();
    }
}
