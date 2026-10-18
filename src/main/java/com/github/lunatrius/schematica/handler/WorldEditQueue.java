package com.github.lunatrius.schematica.handler;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.tool.WorldEditTask;
import com.github.lunatrius.schematica.util.MessageException;

/** Integrated-server edits enter here from the client and execute exclusively on server ticks. */
public final class WorldEditQueue {
    public static final WorldEditQueue INSTANCE = new WorldEditQueue();
    private final AtomicReference<Edit> pending = new AtomicReference<>();
    private final ArrayBlockingQueue<Runnable> requests = new ArrayBlockingQueue<>(16);

    public boolean enqueue(Runnable request) { return requests.offer(request); }

    private static final class Edit {
        final MinecraftServer server;
        final WorldEditTask job;
        final TaskRegistry.Task task;

        Edit(MinecraftServer server, WorldEditTask job) {
            this.server = server;
            this.job = job;
            this.task = TaskRegistry.INSTANCE.start(job.player, job.dimension, job.taskKind(), TaskRegistry.Backend.SERVER,
                job.x + ", " + job.y + ", " + job.z);
        }
    }

    public synchronized void clear() {
        requests.clear();
        Edit edit = pending.getAndSet(null);
        if (edit != null) {
            edit.job.cancelled = true;
            edit.task.finish();
        }
    }

    public boolean cancel(UUID player) {
        return cancel(player, null);
    }

    public boolean cancel(UUID player, WorldEditTask expected) {
        Edit edit = pending.get();
        if (edit == null || !edit.job.player.equals(player) || (expected != null && edit.job != expected)) return false;
        edit.task.cancel(player);
        edit.job.cancelled = true;
        return true;
    }

    public synchronized boolean submit(MinecraftServer server, WorldEditTask job) {
        Edit edit = pending.get();
        if (edit != null && edit.server != server) clear();
        if (pending.get() != null) return false;
        pending.set(new Edit(server, job));
        return true;
    }

    private void finish(Edit edit) { finish(edit, false); }
    private void finish(Edit edit, boolean success) {
        pending.compareAndSet(edit, null); edit.task.finish(); edit.job.completion.accept(success);
    }

    public void rollbackBeforeShutdown(MinecraftServer server) {
        Edit edit = pending.get();
        if (edit == null || edit.server != server || !edit.job.needsRollback()) return;
        edit.job.cancelled = true;
        WorldServer world = server.getWorld(edit.job.dimension);
        try { while (!edit.job.step(world)) {} }
        finally { edit.job.flushBlockChanges(world); finish(edit); }
    }

    /** "Reason: ..." in red, after a failed edit's message. */
    private static ITextComponent reason(Throwable error) {
        ITextComponent line = new TextComponentTranslation("schematica.message.edit.failed_reason", MessageException.reason(error));
        line.getStyle().setColor(net.minecraft.util.text.TextFormatting.RED);
        return line;
    }

    /** The running server, or null (also when FML has not started, as in unit tests). */
    private static MinecraftServer server() {
        try {
            return FMLCommonHandler.instance().getMinecraftServerInstance();
        } catch (NullPointerException notStarted) {
            return null;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable request = requests.poll();
        if (request != null) request.run();
        Edit edit = pending.get();
        if (edit == null) return;
        WorldEditTask job = edit.job;
        MinecraftServer server = server();
        if (server != edit.server || server == null) { finish(edit); return; }
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(job.player);
        boolean authorized = player != null && player.dimension == job.dimension && player.capabilities.isCreativeMode
            && player.canUseCommand(2, "setblock");
        if (!authorized) {
            if (player != null && !job.cancelled) player.sendMessage(new TextComponentTranslation("schematica.message.edit.permissions"));
            job.cancelled = true;
            if (!job.needsRollback()) { finish(edit); return; }
        }
        WorldServer world = server.getWorld(job.dimension);
        try {
            long deadline = System.nanoTime() + 8_000_000L;
            boolean done = false;
            try {
                for (int i = 0; i < 2048 && System.nanoTime() < deadline; i++) {
                    if (edit.task.progress().cancelling) job.cancelled = true;
                    if (job.step(world)) { done = true; break; }
                }
            } finally {
                job.flushBlockChanges(world);
            }
            job.publishProgress(edit.task);
            if (job.progress != null) job.progress.accept(edit.task.progress());
            if (done) {
                boolean success = !job.cancelled && job.failure() == null;
                if (job.failure() != null) Reference.logger.error("World move restored after failure", job.failure());
                ITextComponent message = job.finishedMessage(success);
                if (player != null && message != null) player.sendMessage(message);
                if (player != null && job.failure() != null) player.sendMessage(reason(job.failure()));
                finish(edit, success);
            }
        } catch (Exception e) {
            Reference.logger.error("World edit stopped after partial completion", e);
            ITextComponent message = job.finishedMessage(false);
            if (player != null && message != null) player.sendMessage(message);
            if (player != null && e instanceof MessageException) {
                player.sendMessage(new TextComponentTranslation(((MessageException) e).key(), ((MessageException) e).arguments()));
            } else if (player != null) {
                player.sendMessage(reason(e));
            }
            finish(edit);
        }
    }
}
