# Unreleased changes

Collected here until the next release, then used as the GitHub / Modrinth notes.

- Pasting on servers without the server mod (#3): hoppers, chests and other containers too full for one chat command are now placed and then filled slot by slot (after the whole build is placed, so sorter hoppers are locked first) instead of losing their items. Minecart items are written much more compactly, so full hopper minecarts keep everything; chest minecarts with many different stacks keep the stacks that fit (a vanilla 1.12 limit - the server mod pastes them fully).
