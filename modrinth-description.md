# Schematica upgraded

A **Forge 1.12.2** port of [Schematica Plus](https://github.com/HackerRouter/Schematica-Plus) (HackerRouter's 1.7.10 Litematica-style Schematica), with a lot of extra optimization work and some new features on top.

It is a full **replacement for Schematica**: no LunatriusCore needed, and it will refuse to load next to the original Schematica (same mod ID).

> **Beta.** Please report bugs. Back up your schematics before trying new features.

## Highlights

- **Litematica-style menus and tools** - main menu, placements, area selections, config and hotkey screens, in 13 languages.
- **Loads `.litematic` and `.schematic`** files, including multi-region litematics with independent sub-regions you can move, rotate, mirror and toggle.
- **Huge schematics** - sparse storage and render culling let very large builds (world-eater sized) load and render without freezing the game.
- **Printer and Easy Place** - survival printer with configurable range, redstone-aware placement order, fake rotation/position so most blocks face the right way on any server, and hand-mining that keeps working while the printer is on.
- **Material lists** - fast lists for placements, files and real-world areas, with search, and a **Replace** that really swaps a block in the schematic (Save / Save as).
- **Verifier** - missing, extra, wrong block and wrong state, with in-world highlights.
- **Newer-version blocks** - schematics made in newer Minecraft versions are converted: known blocks are swapped automatically and a pick screen lets you choose replacements for the rest.
- **Creative tools** - fill, replace, paste and move, including a command-based paste that works on servers without the mod.

## Client and server

The mod works fully **client-side** on any server (vanilla, Spigot, etc.).

Installing it on the **server too** is optional and adds:
- exact block orientation when printing (pistons, observers, stairs, slabs, rails, repeaters...), even where fake rotation can't reach,
- fast server-side paste / move / upload,
- printer restocking from shulker boxes you carry.

## Default controls

| Key | Action |
| --- | --- |
| Release `M` | Main menu |
| `M + C` | Config and hotkeys |
| `M + S` | Area selections |
| `M + P` | Placements |
| `M + L` | Material list |
| `M + V` | Verifier |
| Stick + `Left Ctrl` + wheel | Cycle tool mode |

All hotkeys can be rebound in the config screen. If you also run Litematica (LiteLoader), both use `M` - rebind one of them.

## Credits

- **Lunatrius** - original Schematica and LunatriusCore (MIT).
- **HackerRouter** - Schematica Plus for 1.7.10, which this is ported from.
- **masa, Sakura-Ryoko and contributors** - Litematica and MaLiLib, whose UI, textures and translations are included (LGPL-3.0).

**See what's being worked on next:** [Useless Mods Workshop](https://useleess.github.io/workshop)
