package com.github.lunatrius.schematica.tool;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.util.SchematicLimits;
import com.github.lunatrius.schematica.world.storage.RegionMask;
import com.github.lunatrius.schematica.world.storage.RegionSelection;

public final class WorldMoveJob extends WorldEditTask {
    private final RegionSelection selection;
    private final int dx, dy, dz;
    private final BitSet mask;
    private final BlockMoveTransaction<Cell> transaction;
    private final SilentBlockPlacement writer = new SilentBlockPlacement();
    private final List<Entity> entities = new ArrayList<>();
    private boolean capturedEntities;

    public WorldMoveJob(UUID player, int dimension, List<SchematicRegion> regions, int dx, int dy, int dz) {
        this(player, dimension, new RegionSelection(regions), dx, dy, dz);
    }
    private WorldMoveJob(UUID player, int dimension, RegionSelection selection, int dx, int dy, int dz) {
        super(player, dimension, Math.addExact(selection.minX, dx), Math.addExact(selection.minY, dy), Math.addExact(selection.minZ, dz));
        this.selection = selection; this.dx = dx; this.dy = dy; this.dz = dz;
        SchematicLimits.worldBounds(selection.minX, selection.minY, selection.minZ, selection.maxX, selection.maxY, selection.maxZ);
        SchematicLimits.worldBounds(x, y, z, (long) selection.maxX + dx, (long) selection.maxY + dy, (long) selection.maxZ + dz);
        int width = selection.maxX - selection.minX + 1, height = selection.maxY - selection.minY + 1, length = selection.maxZ - selection.minZ + 1;
        mask = RegionMask.create(selection.localRegions, width, height, length);
        transaction = new BlockMoveTransaction<>(selection.minX, selection.minY, selection.minZ, width, height, length, dx, dy, dz, mask,
            new Cell(Blocks.AIR.getDefaultState(), null, null));
    }
    @Override public boolean step(WorldServer world) {
        boolean done = transaction.step(new BlockMoveTransaction.Access<Cell>() {
            @Override public Cell read(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!world.isBlockLoaded(pos)) throw new IllegalArgumentException("Move requires loaded source and target chunks");
                IBlockState state = world.getBlockState(pos);
                TileEntity tile = world.getTileEntity(pos);
                if (state.getBlock().hasTileEntity(state) && tile == null) throw new IllegalArgumentException("Missing tile entity at " + x + ", " + y + ", " + z);
                return new Cell(state, tile == null ? null : NBTHelper.writeTileEntityToCompound(tile), tile);
            }
            @Override public void write(int x, int y, int z, Cell cell) {
                BlockPos pos = new BlockPos(x, y, z);
                writer.setBlock(world, pos, cell.state);
                if (cell.tag != null) {
                    NBTTagCompound tag = cell.tag.copy();
                    boolean restoring = transaction.phase() == BlockMoveTransaction.Phase.RESTORE_SOURCE || transaction.phase() == BlockMoveTransaction.Phase.RESTORE_TARGET;
                    tag.setInteger("x", x); tag.setInteger("y", y); tag.setInteger("z", z);
                    TileEntity tile = restoring ? cell.original : TileEntity.create(world, tag);
                    if (tile == null) throw new IllegalArgumentException("Unable to restore moved tile at " + x + ", " + y + ", " + z);
                    if (restoring) { tile.validate(); tile.readFromNBT(tag); }
                    tile.setPos(pos);
                    writer.setTile(world, tile);
                }
            }
            @Override public void changed(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                world.notifyNeighborsOfStateChange(pos, world.getBlockState(pos).getBlock(), true);
            }
        }, cancelled);
        if (!capturedEntities && transaction.phase() == BlockMoveTransaction.Phase.CLEAR) {
            capturedEntities = true;
            for (Entity entity : world.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(selection.minX, selection.minY, selection.minZ,
                selection.maxX + 1.0, selection.maxY + 1.0, selection.maxZ + 1.0))) {
                if (!(entity instanceof EntityPlayer) && contains(entity)) entities.add(entity);
            }
        }
        if (done && transaction.successful()) {
            blockCount = transaction.affected();
            for (Entity entity : entities) if (!entity.isDead && entity.world == world && contains(entity)) {
                if (entity instanceof EntityHanging) {
                    BlockPos hanging = ((EntityHanging) entity).getHangingPosition();
                    entity.setPosition(hanging.getX() + dx, hanging.getY() + dy, hanging.getZ() + dz);
                } else entity.setPositionAndRotation(entity.posX + dx, entity.posY + dy, entity.posZ + dz, entity.rotationYaw, entity.rotationPitch);
                entityCount++;
            }
        }
        return done;
    }
    private boolean contains(Entity entity) {
        int x = (int) Math.floor(entity.posX) - selection.minX, y = (int) Math.floor(entity.posY) - selection.minY, z = (int) Math.floor(entity.posZ) - selection.minZ;
        int width = selection.maxX - selection.minX + 1, length = selection.maxZ - selection.minZ + 1;
        return x >= 0 && x < width && y >= 0 && y <= selection.maxY - selection.minY && z >= 0 && z < length && mask.get(x + width * (z + length * y));
    }
    @Override public void flushBlockChanges(WorldServer world) { writer.flush(world); }
    @Override public TaskRegistry.Kind taskKind() { return TaskRegistry.Kind.MOVE; }
    @Override public boolean needsRollback() { return transaction.mutated() && transaction.phase() != BlockMoveTransaction.Phase.DONE; }
    @Override public RuntimeException failure() { return transaction.failure(); }
    @Override public void publishProgress(TaskRegistry.Task task) {
        task.update(transaction.phase() == BlockMoveTransaction.Phase.CAPTURE ? TaskRegistry.Stage.CAPTURE : TaskRegistry.Stage.WRITE,
            transaction.completed(), transaction.volume(), blockCount, entityCount);
    }
    private static final class Cell {
        final IBlockState state;
        final NBTTagCompound tag;
        final TileEntity original;
        Cell(IBlockState state, NBTTagCompound tag, TileEntity original) { this.state = state; this.tag = tag; this.original = original; }
    }
}
