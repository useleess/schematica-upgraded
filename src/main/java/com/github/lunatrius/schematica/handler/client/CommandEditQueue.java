package com.github.lunatrius.schematica.handler.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.tool.CommandPlanner;
import com.github.lunatrius.schematica.tool.WorldEditJob;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.util.MessageException;
import com.github.lunatrius.schematica.reference.Reference;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Edits on servers without the mod, as setblock/fill chat commands. Success is read from the world the client sees,
 * not from chat replies, so it works whatever the server says back: commands are sent at a steady rate without
 * waiting, blocks that did not change are retried and then skipped, and chunks the client has not loaded wait until
 * the player comes near.
 */
public final class CommandEditQueue {
    public static final CommandEditQueue INSTANCE = new CommandEditQueue();
    /** Ticks (counted while the chunk is loaded) a command may take to show in the world. */
    static final int CONFIRM_TIMEOUT = 100;
    static final int MAX_ATTEMPTS = 3;
    /** Commands that changed nothing before any command ever worked: the server is refusing them. */
    static final int PROBE_FAILURES = 2;
    static final int SPAM_PAUSE = 60, MAX_BACKOFF = 16;
    private static final int TICK_CELLS = 65536, PLANS_PER_TICK = 16, CELL_RETRY_LIMIT = 64;

    interface Client {
        boolean valid(WorldEditJob job, World world);
        CommandPlanner.View view(World world);
        /** Squared distance from the player to a chunk column, to paste nearby chunks first. */
        double distanceSq(int chunkX, int chunkZ);
        void send(String command);
        void message(ITextComponent message);
    }

    private static final class Flight {
        final CommandPlanner.Command command;
        int age, cursor;
        Flight(CommandPlanner.Command command) { this.command = command; }
    }

    private final Client client;
    private WorldEditJob job;
    private World world;
    private TaskRegistry.Task task;
    private List<int[]> chunks;
    private int[] nextPhase;
    private final ArrayDeque<CommandPlanner.Command> outbox = new ArrayDeque<>();
    private final List<Flight> flights = new ArrayList<>();
    /** Summons waiting until every block phase is done and their chunk is loaded. */
    private final List<CommandPlanner.Command> summons = new ArrayList<>();
    private int sent, failed, dropped, unconfirmed, phasesDone, delay, pause, backoff;
    private boolean confirmed, waitingNoted, spamNoted;

    public CommandEditQueue() {
        this(new Client() {
            public boolean valid(WorldEditJob job, World world) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.player != null && mc.world == world && job.player.equals(mc.player.getUniqueID())
                    && job.dimension == mc.player.dimension && mc.player.capabilities.isCreativeMode;
            }
            public CommandPlanner.View view(World world) {
                return new CommandPlanner.View() {
                    public boolean loaded(int chunkX, int chunkZ) { return world.isBlockLoaded(new BlockPos(chunkX << 4, 0, chunkZ << 4), false); }
                    public IBlockState get(int x, int y, int z) { return world.getBlockState(new BlockPos(x, y, z)); }
                };
            }
            public double distanceSq(int chunkX, int chunkZ) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.player == null) return 0;
                double dx = (chunkX << 4) + 8 - mc.player.posX, dz = (chunkZ << 4) + 8 - mc.player.posZ;
                return dx * dx + dz * dz;
            }
            public void send(String command) { Minecraft.getMinecraft().player.sendChatMessage(command); }
            public void message(ITextComponent message) {
                if (message != null && Minecraft.getMinecraft().player != null) {
                    Minecraft.getMinecraft().player.sendMessage(message);
                }
            }
        });
    }

    CommandEditQueue(Client client) { this.client = client; }

    public synchronized boolean cancel() { return end(false); }

    public synchronized boolean busy() { return job != null; }

    private boolean end(boolean success) {
        if (job == null) return false;
        WorldEditJob finished = job;
        task.finish();
        task = null;
        job = null;
        world = null;
        chunks = null;
        outbox.clear();
        flights.clear();
        summons.clear();
        finished.completion.accept(success);
        return true;
    }

    public synchronized void submit(WorldEditJob next, World targetWorld) {
        if (job != null) throw new MessageException("schematica.message.edit.busy");
        if (next.kind == WorldEditJob.Kind.PASTE && ConfigurationHandler.pasteIgnoreBlockEntitiesEntirely) next.dropTiles();
        next.validateCommandFallback(); // Preflight before sending the first command.
        if (next.pasteWithoutUpdates) {
            client.message(colored(new TextComponentTranslation("schematica.message.edit.updates_on_server"), TextFormatting.GOLD));
        }
        task = TaskRegistry.INSTANCE.start(next.player, next.dimension, next.taskKind(), TaskRegistry.Backend.COMMANDS,
            next.x + ", " + next.y + ", " + next.z);
        job = next; world = targetWorld;
        chunks = CommandPlanner.chunks(next);
        nextPhase = new int[chunks.size()];
        summons.addAll(CommandPlanner.summons(next));
        sent = failed = dropped = unconfirmed = phasesDone = delay = pause = 0;
        backoff = 1;
        confirmed = waitingNoted = spamNoted = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public synchronized void onChat(ClientChatReceivedEvent event) {
        if (job == null || event.getMessage() == null) return;
        ITextComponent message = event.getMessage();
        String key = message instanceof TextComponentTranslation ? ((TextComponentTranslation) message).getKey() : null;
        if (key != null && key.startsWith("chat.type.")) return; // player chat and admin broadcasts
        if (key != null) {
            if (key.equals("commands.setblock.success") || key.equals("commands.fill.success")
                || key.equals("commands.setblock.noChange") || key.equals("commands.fill.failed")
                || key.equals("commands.setblock.outOfWorld") || key.equals("commands.fill.outOfWorld")
                || key.equals("commands.kill.successful") || key.equals("commands.generic.selector.notFound")
                || key.equals("commands.summon.success")) {
                if (sent > 0 && event.isCancelable()) event.setCanceled(true); // the world check covers these; keep chat readable
            } else if (key.equals("commands.generic.permission") || key.equals("commands.generic.notFound")) {
                abort();
            }
            return;
        }
        String text = message.getUnformattedText().toLowerCase(Locale.ROOT);
        if (text.contains("unknown command") || text.contains("do not have permission") || text.contains("don't have permission")
            || text.contains("no permission")) {
            abort();
        } else if (text.contains("spam") || text.contains("too fast") || text.contains("slow down") || text.contains("too many commands")) {
            backoff = Math.min(MAX_BACKOFF, backoff * 2);
            pause = SPAM_PAUSE;
            if (!spamNoted) {
                spamNoted = true;
                client.message(colored(new TextComponentTranslation("schematica.message.edit.commands_slowed"), TextFormatting.GOLD));
            }
        }
    }

    private void abort() {
        client.message(colored(new TextComponentTranslation("schematica.message.edit.commands_denied"), TextFormatting.RED));
        cancel();
    }

    private static ITextComponent colored(ITextComponent message, TextFormatting color) {
        message.getStyle().setColor(color);
        return message;
    }

    @SubscribeEvent
    public synchronized void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || job == null) return;
        if (task.progress().cancelling) { cancel(); return; }
        if (!client.valid(job, world)) { cancel(); return; }
        try {
            CommandPlanner.View view = client.view(world);
            verify(view);
            if (job == null) return;
            if (pause > 0) pause--;
            else if (delay-- <= 0) {
                send(view);
                delay = ConfigurationHandler.commandTaskInterval * backoff - 1;
            }
            task.update(TaskRegistry.Stage.COMMANDS, phasesDone, (long) chunks.size() * CommandPlanner.PHASES, job.blockCount, 0);
            if (outbox.isEmpty() && flights.isEmpty()) {
                if (phasesDone == chunks.size() * CommandPlanner.PHASES && summons.isEmpty()) finish();
                else if (!waitingNoted && !hasLoadedWork(view)) {
                    waitingNoted = true;
                    client.message(colored(new TextComponentTranslation("schematica.message.edit.commands_waiting_chunks",
                        remainingChunks()), TextFormatting.GOLD));
                }
            }
        } catch (RuntimeException e) {
            Reference.logger.error("Command edit stopped after partial completion", e);
            if (job != null) client.message(job.finishedMessage(false));
            cancel();
        }
    }

    private void finish() {
        boolean success = failed == 0 || job.blockCount > 0;
        if (sent == 0) client.message(job.finishedMessage(true));
        else client.message(new TextComponentTranslation("schematica.message.edit.commands_done", job.blockCount, sent));
        if (failed > 0) {
            client.message(colored(new TextComponentTranslation("schematica.message.edit.commands_failed_blocks", failed), TextFormatting.GOLD));
        }
        if (dropped > 0) {
            client.message(colored(new TextComponentTranslation("schematica.message.edit.commands_data_dropped", dropped), TextFormatting.GOLD));
        }
        end(success);
    }

    /** Settles commands whose blocks now show in the world, and retries or gives up on those that timed out. */
    private void verify(CommandPlanner.View view) {
        for (Iterator<Flight> it = flights.iterator(); it.hasNext(); ) {
            Flight flight = it.next();
            CommandPlanner.Command command = flight.command;
            if (!view.loaded(command.x1 >> 4, command.z1 >> 4)) continue;
            flight.age++;
            if (settled(flight, view)) {
                it.remove();
                job.blockCount += command.changes;
                confirmed = true;
                continue;
            }
            if (flight.age < CONFIRM_TIMEOUT) continue;
            it.remove();
            List<int[]> wrong = mismatched(command, view);
            if (wrong.size() < command.changes) confirmed = true; // part of it worked: the server runs the commands
            if (!confirmed && ++unconfirmed >= PROBE_FAILURES) {
                abort();
                return;
            }
            if (command.attempts >= MAX_ATTEMPTS) {
                job.blockCount += Math.max(0, command.changes - wrong.size());
                failed += wrong.size();
            } else if (wrong.size() >= command.changes || wrong.size() > CELL_RETRY_LIMIT) {
                command.attempts++;
                outbox.addFirst(command);
            } else {
                job.blockCount += Math.max(0, command.changes - wrong.size());
                for (int i = wrong.size() - 1; i >= 0; i--) {
                    int[] cell = wrong.get(i);
                    CommandPlanner.Command retry = CommandPlanner.setblock(job, cell[0], cell[1], cell[2], command.state, true);
                    retry.attempts = command.attempts + 1;
                    outbox.addFirst(retry);
                }
            }
        }
    }

    /** Whether every cell of the box holds the command's block; resumes where the last check stopped. */
    private static boolean settled(Flight flight, CommandPlanner.View view) {
        CommandPlanner.Command c = flight.command;
        int w = c.x2 - c.x1 + 1, l = c.z2 - c.z1 + 1, volume = c.volume();
        while (flight.cursor < volume) {
            int i = flight.cursor;
            if (view.get(c.x1 + i % w, c.y1 + i / w / l, c.z1 + i / w % l).getBlock() != c.state.getBlock()) return false;
            flight.cursor++;
        }
        return true;
    }

    private static List<int[]> mismatched(CommandPlanner.Command c, CommandPlanner.View view) {
        List<int[]> wrong = new ArrayList<>();
        for (int y = c.y1; y <= c.y2; y++) for (int z = c.z1; z <= c.z2; z++) for (int x = c.x1; x <= c.x2; x++) {
            if (view.get(x, y, z).getBlock() != c.state.getBlock()) wrong.add(new int[] {x, y, z});
        }
        return wrong;
    }

    private void send(CommandPlanner.View view) {
        int limit = ConfigurationHandler.commandLimitPerTick, window = Math.max(16, limit * 20);
        int issued = 0, cells = 0;
        while (issued < limit && cells < TICK_CELLS && flights.size() < window) {
            // Until one command is seen working, only one is out at a time: a refusing server gets no burst
            if (!confirmed && !flights.isEmpty()) break;
            CommandPlanner.Command command = next(view);
            if (command == null) break;
            if (command.dataDropped && command.attempts == 1) dropped++;
            if (command.text == null) continue; // an entity whose data does not fit in chat
            client.send(command.text);
            sent++; issued++; cells += command.volume();
            if (command.verify) flights.add(new Flight(command));
            else job.blockCount += command.changes;
            if (command.entity) job.entityCount++;
        }
    }

    /** The next command: retries first, then the next phase of the nearest loaded chunk with the lowest phase left. */
    private CommandPlanner.Command next(CommandPlanner.View view) {
        for (int plans = 0; outbox.isEmpty() && plans < PLANS_PER_TICK; plans++) {
            int best = -1;
            double bestDistance = 0;
            for (int i = 0; i < chunks.size(); i++) {
                if (nextPhase[i] >= CommandPlanner.PHASES) continue;
                if (best >= 0 && nextPhase[i] > nextPhase[best]) continue;
                int[] chunk = chunks.get(i);
                if (!view.loaded(chunk[0], chunk[1])) continue;
                double distance = client.distanceSq(chunk[0], chunk[1]);
                if (best < 0 || nextPhase[i] < nextPhase[best] || distance < bestDistance) { best = i; bestDistance = distance; }
            }
            if (best < 0) {
                if (phasesDone == chunks.size() * CommandPlanner.PHASES) releaseSummons(view);
                break;
            }
            int[] chunk = chunks.get(best);
            outbox.addAll(CommandPlanner.plan(job, view, chunk[0], chunk[1], nextPhase[best]++, ConfigurationHandler.commandFillMaxVolume));
            phasesDone++;
            waitingNoted = false;
        }
        return outbox.poll();
    }

    /** Entities go in once all blocks are placed, so nothing they stand on or hang from is missing. */
    private void releaseSummons(CommandPlanner.View view) {
        for (Iterator<CommandPlanner.Command> it = summons.iterator(); it.hasNext(); ) {
            CommandPlanner.Command summon = it.next();
            if (!view.loaded(summon.x1 >> 4, summon.z1 >> 4)) continue;
            it.remove();
            outbox.add(summon);
            waitingNoted = false;
        }
    }

    private boolean hasLoadedWork(CommandPlanner.View view) {
        for (int i = 0; i < chunks.size(); i++) {
            if (nextPhase[i] < CommandPlanner.PHASES && view.loaded(chunks.get(i)[0], chunks.get(i)[1])) return true;
        }
        if (phasesDone == chunks.size() * CommandPlanner.PHASES) {
            for (CommandPlanner.Command summon : summons) if (view.loaded(summon.x1 >> 4, summon.z1 >> 4)) return true;
        }
        return false;
    }

    private int remainingChunks() {
        java.util.Set<Long> remaining = new java.util.HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (nextPhase[i] < CommandPlanner.PHASES) remaining.add((long) chunks.get(i)[0] << 32 ^ chunks.get(i)[1] & 0xffffffffL);
        }
        for (CommandPlanner.Command summon : summons) remaining.add((long) (summon.x1 >> 4) << 32 ^ (summon.z1 >> 4) & 0xffffffffL);
        return remaining.size();
    }
}
