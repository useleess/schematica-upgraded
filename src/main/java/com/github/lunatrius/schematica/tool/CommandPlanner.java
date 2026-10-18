package com.github.lunatrius.schematica.tool;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDragonEgg;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Turns one chunk column of an edit into chat commands for servers without the mod: cells that need the same state
 * are merged into fill boxes (already matching cells may fill the gaps), each phase in its own pass so supports exist
 * before what hangs on them, and falling blocks and fluids come last.
 */
public final class CommandPlanner {
    /** 0: air and full blocks, 1: everything else that stays put, 2: falling blocks, 3: fluids. */
    public static final int PHASES = 4;
    private static final int MAX_COMMAND = 256;

    /** The world as the client sees it. */
    public interface View {
        boolean loaded(int chunkX, int chunkZ);
        IBlockState get(int x, int y, int z);
    }

    /** One command and the world box it writes. */
    public static final class Command {
        public final String text;
        public final IBlockState state;
        public final int x1, y1, z1, x2, y2, z2;
        /** Cells in the box that differed from the target when planned. */
        public final int changes;
        /** False for falling blocks and fluids, which may move away right after being placed. */
        public final boolean verify;
        public int attempts = 1;
        /** Block entity or entity data that did not fit in chat and was left out (or the entity skipped). */
        public boolean dataDropped;
        /** A summon: counts as one pasted entity. */
        public boolean entity;

        Command(String text, IBlockState state, int x1, int y1, int z1, int x2, int y2, int z2, int changes, boolean verify) {
            this.text = text; this.state = state;
            this.x1 = x1; this.y1 = y1; this.z1 = z1; this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.changes = changes; this.verify = verify;
        }

        /** A command that writes no blocks (kill, summon), sent once without checking the world. */
        static Command other(String text, int x, int z) {
            return new Command(text, null, x, 0, z, x, 0, z, 0, false);
        }

        public int volume() { return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }

        @Override public String toString() { return text; }
    }

    private CommandPlanner() {}

    public static int phase(IBlockState state) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        if (material.isLiquid() || block instanceof IFluidBlock) return 3;
        if (block instanceof BlockFalling || block instanceof BlockDragonEgg) return 2;
        if (material == Material.AIR || state.isFullCube()) return 0;
        return 1;
    }

    /** Chunk columns the edit touches, as {chunkX, chunkZ}. */
    public static List<int[]> chunks(WorldEditJob job) {
        List<int[]> chunks = new ArrayList<>();
        for (int cx = job.x >> 4; cx <= (job.x + job.width - 1) >> 4; cx++) {
            for (int cz = job.z >> 4; cz <= (job.z + job.length - 1) >> 4; cz++) chunks.add(new int[] {cx, cz});
        }
        return chunks;
    }

    /** The commands for one phase of one chunk column, lowest layers first; empty when nothing there needs changing. */
    public static List<Command> plan(WorldEditJob job, View view, int chunkX, int chunkZ, int phase, int maxVolume) {
        List<Command> commands = new ArrayList<>();
        int minX = Math.max(job.x, chunkX << 4), maxX = Math.min(job.x + job.width - 1, (chunkX << 4) + 15);
        int minZ = Math.max(job.z, chunkZ << 4), maxZ = Math.min(job.z + job.length - 1, (chunkZ << 4) + 15);
        int minY = Math.max(job.y, 0), maxY = Math.min(job.y + job.height - 1, 255);
        if (minX > maxX || minZ > maxZ || minY > maxY) return commands;
        if (phase == 0) {
            // Entity removal (deletes) goes first, for the part of each box in this chunk column
            for (com.github.lunatrius.schematica.api.SchematicRegion box : job.entityRemovalBoxes()) {
                int ax = Math.max(box.minX, minX), bx = Math.min(box.maxX, maxX), az = Math.max(box.minZ, minZ), bz = Math.min(box.maxZ, maxZ);
                if (ax > bx || az > bz) continue;
                commands.add(Command.other(WorldEditJob.killCommand(ax, Math.max(box.minY, 0), az, bx, Math.min(box.maxY, 255), bz), ax, az));
            }
        }
        int w = maxX - minX + 1, l = maxZ - minZ + 1, h = maxY - minY + 1;
        // target: the wanted state id where a box may cover the cell, else -1; need: the cell differs and must be written
        int[] target = new int[w * l * h];
        boolean[] need = new boolean[target.length];
        boolean any = false;
        List<int[]> tiled = new ArrayList<>();
        for (int ly = 0; ly < h; ly++) for (int lz = 0; lz < l; lz++) for (int lx = 0; lx < w; lx++) {
            int cell = lx + w * (lz + l * ly);
            target[cell] = -1;
            int index = job.index(minX + lx - job.x, minY + ly - job.y, minZ + lz - job.z);
            IBlockState state = job.target(index);
            if (state == null || phase(state) != phase) continue;
            IBlockState existing = view.get(minX + lx, minY + ly, minZ + lz);
            int id = Block.getStateId(state);
            if (job.writes(index, existing, state)) {
                if (job.tileTag(index) != null) {
                    tiled.add(new int[] {minX + lx, minY + ly, minZ + lz, index}); // own setblock carrying the data
                    continue;
                }
                target[cell] = id;
                need[cell] = any = true;
            } else if (Block.getStateId(existing) == id && !state.getBlock().hasTileEntity(state)) {
                target[cell] = id; // already right: rewriting it changes nothing, so boxes may span it
            }
        }
        if (!any) return withTiles(job, commands, tiled, phase);
        boolean[] used = new boolean[target.length];
        int limit = Math.max(1, maxVolume);
        for (int ly = 0; ly < h; ly++) for (int lz = 0; lz < l; lz++) for (int lx = 0; lx < w; lx++) {
            int seed = lx + w * (lz + l * ly);
            if (!need[seed] || used[seed]) continue;
            int id = target[seed];
            int ex = lx;
            while (ex + 1 < w && (ex + 2 - lx) <= limit && free(target, used, id, w, l, ex + 1, ex + 1, ly, lz, lz)) ex++;
            int dx = ex - lx + 1, ez = lz;
            while (ez + 1 < l && dx * (ez + 2 - lz) <= limit && free(target, used, id, w, l, lx, ex, ly, ez + 1, ez + 1)) ez++;
            int dz = ez - lz + 1, ey = ly;
            while (ey + 1 < h && dx * dz * (ey + 2 - ly) <= limit && free(target, used, id, w, l, lx, ex, ey + 1, lz, ez)) ey++;
            int changes = 0;
            for (int y = ly; y <= ey; y++) for (int z = lz; z <= ez; z++) for (int x = lx; x <= ex; x++) {
                int cell = x + w * (z + l * y);
                used[cell] = true;
                if (need[cell]) changes++;
            }
            IBlockState state = Block.getStateById(id);
            boolean verify = phase < 2;
            int x1 = minX + lx, y1 = minY + ly, z1 = minZ + lz, x2 = minX + ex, y2 = minY + ey, z2 = minZ + ez;
            String fill = x1 == x2 && y1 == y2 && z1 == z2 ? null : job.fillCommand(x1, y1, z1, x2, y2, z2, state);
            if (fill != null && fill.length() <= MAX_COMMAND) {
                commands.add(new Command(fill, state, x1, y1, z1, x2, y2, z2, changes, verify));
                continue;
            }
            // A single cell, or a fill too long for chat: one setblock per cell that needs it
            for (int y = ly; y <= ey; y++) for (int z = lz; z <= ez; z++) for (int x = lx; x <= ex; x++) {
                if (!need[x + w * (z + l * y)]) continue;
                commands.add(setblock(job, minX + x, minY + y, minZ + z, state, verify));
            }
        }
        return withTiles(job, commands, tiled, phase);
    }

    private static List<Command> withTiles(WorldEditJob job, List<Command> commands, List<int[]> tiled, int phase) {
        for (int[] cell : tiled) {
            IBlockState state = job.target(cell[3]);
            String text = job.blockCommand(cell[0], cell[1], cell[2], state, job.tileTag(cell[3]));
            Command command = new Command(text != null ? text : job.blockCommand(cell[0], cell[1], cell[2], state), state,
                cell[0], cell[1], cell[2], cell[0], cell[1], cell[2], 1, phase < 2);
            command.dataDropped = text == null;
            commands.add(command);
        }
        return commands;
    }

    /** Summons for the captured entities, each tagged with its chunk column; ones too big for chat are marked dropped. */
    public static List<Command> summons(WorldEditJob job) {
        List<Command> commands = new ArrayList<>();
        for (net.minecraft.nbt.NBTTagCompound entity : job.entityTags()) {
            net.minecraft.nbt.NBTTagList pos = entity.getTagList("Pos", 6);
            if (pos.tagCount() != 3) continue;
            int x = (int) Math.floor(pos.getDoubleAt(0) + job.x), z = (int) Math.floor(pos.getDoubleAt(2) + job.z);
            String text = job.summonCommand(entity);
            Command command = Command.other(text, x, z);
            command.entity = true;
            command.dataDropped = text == null;
            commands.add(command);
        }
        return commands;
    }

    public static Command setblock(WorldEditJob job, int x, int y, int z, IBlockState state, boolean verify) {
        return new Command(job.blockCommand(x, y, z, state), state, x, y, z, x, y, z, 1, verify);
    }

    private static boolean free(int[] target, boolean[] used, int id, int w, int l, int x1, int x2, int y, int z1, int z2) {
        for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) {
            int cell = x + w * (z + l * y);
            if (target[cell] != id || used[cell]) return false;
        }
        return true;
    }
}
