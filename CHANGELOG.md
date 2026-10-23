# Schematica Upgraded changelog

## 1.0.0 - 2026-10-23

- Pasting on servers without the server mod (#3): hoppers, chests and other containers too full for one chat command are now placed and then filled slot by slot (after the whole build is placed, so sorter hoppers are locked first) instead of losing their items. Minecart items are written much more compactly, so full hopper minecarts keep everything; chest minecarts with many different stacks keep the stacks that fit (a vanilla 1.12 limit - the server mod pastes them fully).
- Saving a schematic now keeps entities by default: "Ignore entities" starts unticked (#2).
- Loading schematics from newer versions (1.13 - 1.21, .litematic / .schem / .nbt) now keeps chest, barrel, hopper, shulker box and other container contents, item frame and armor stand items (#1). Items are turned into their 1.12 versions with names, enchantments, damage, potions, colours, books, fireworks and shulker box contents; a barrel loaded as a chest keeps its items; blocks 1.12 lacks become the same stand-in as in the missing-blocks window. Items 1.12 has no version of (copper ingots, netherite, pottery sherds...) are left out and listed in chat. Glow item frames load as item frames; frames on floors/ceilings (impossible in 1.12) are left out.
