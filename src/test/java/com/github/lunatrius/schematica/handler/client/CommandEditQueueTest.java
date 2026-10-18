package com.github.lunatrius.schematica.handler.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.task.TaskRegistry;
import com.github.lunatrius.schematica.tool.CommandPlanner;
import com.github.lunatrius.schematica.tool.ReplaceBehavior;
import com.github.lunatrius.schematica.tool.WorldEditJob;
import com.github.lunatrius.schematica.util.MessageException;
import com.github.lunatrius.schematica.world.storage.Schematic;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class CommandEditQueueTest {
    static { net.minecraft.init.Bootstrap.register(); }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private com.github.lunatrius.schematica.proxy.CommonProxy previousProxy;
    private CommandEditQueue queue = new CommandEditQueue();
    private final UUID owner = UUID.randomUUID();
    private FakeServer server;

    @Before public void proxy() {
        previousProxy = com.github.lunatrius.schematica.SchematicaPlus.proxy;
        if (previousProxy == null) com.github.lunatrius.schematica.SchematicaPlus.proxy = new com.github.lunatrius.schematica.proxy.ServerProxy() {
            @Override public java.io.File getDataDirectory() { return temporary.getRoot(); }
        };
        ConfigurationHandler.commandLimitPerTick = 8;
        ConfigurationHandler.commandTaskInterval = 1;
        ConfigurationHandler.commandFillMaxVolume = 32768;
    }

    @After public void clear() {
        queue.cancel();
        com.github.lunatrius.schematica.SchematicaPlus.proxy = previousProxy;
    }

    private WorldEditJob fill(int width, int height, int length) {
        return new WorldEditJob(owner, 0, WorldEditJob.Kind.FILL, 0, 64, 0, width, height, length, Blocks.STONE.getDefaultState(), null);
    }

    private FakeServer start(WorldEditJob job) {
        queue.cancel();
        server = new FakeServer();
        queue = new CommandEditQueue(server);
        job.completion = server.completed::add;
        queue.submit(job, null);
        return server;
    }

    private void run(int ticks) { for (int i = 0; i < ticks && queue.busy(); i++) tick(); }

    @Test public void newCommandEditDoesNotOverwriteARunningTask() {
        start(fill(3, 1, 1));
        TaskRegistry.Task first = TaskRegistry.INSTANCE.tasks(owner, 0).get(0);
        MessageException error = assertThrows(MessageException.class, () -> queue.submit(fill(3, 1, 1), null));
        assertEquals("schematica.message.edit.busy", error.key());
        assertSame(first, TaskRegistry.INSTANCE.tasks(owner, 0).get(0));
        assertEquals(1, TaskRegistry.INSTANCE.tasks(owner, 0).size());
        assertEquals(TaskRegistry.Backend.COMMANDS, first.backend);
    }

    @Test public void removingAQueuedTaskStopsBeforeSendingACommand() {
        FakeServer client = start(fill(3, 1, 1));
        TaskRegistry.Task first = TaskRegistry.INSTANCE.tasks(owner, 0).get(0);
        assertTrue(first.cancel(owner));
        tick();
        assertTrue(first.progress().finished);
        assertTrue(client.commands.isEmpty());
        assertEquals(java.util.Collections.singletonList(false), client.completed);
        assertFalse(queue.cancel());
    }

    @Test public void pasteWithoutUpdatesRunsOnServersWithANote() {
        Schematic schematic = new Schematic(null, 2, 1, 1);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        WorldEditJob silent = new WorldEditJob(owner, 0, WorldEditJob.Kind.PASTE, 0, 64, 0, 2, 1, 1, null, null, true, ReplaceBehavior.ALL);
        silent.capture(schematic, false, false);
        FakeServer client = start(silent);
        run(50);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(2, client.count(Blocks.STONE));
        assertEquals("schematica.message.edit.updates_on_server", client.keys().get(0));
    }

    @Test public void deletesKillEntitiesFirstAndPastesSummonEntitiesLast() throws Exception {
        WorldEditJob delete = fill(3, 1, 1);
        delete.removeEntitiesIn(java.util.Collections.singletonList(new com.github.lunatrius.schematica.api.SchematicRegion("box", 0, 64, 0, 2, 64, 0)));
        FakeServer client = start(delete);
        run(50);
        assertEquals("/kill @e[x=0,y=64,z=0,dx=2,dy=0,dz=0,type=!player]", client.commands.get(0));
        assertEquals(java.util.Collections.singletonList(true), client.completed);

        Schematic schematic = new Schematic(null, 1, 1, 1);
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        WorldEditJob paste = paste(schematic, ReplaceBehavior.ALL);
        net.minecraft.nbt.NBTTagCompound stand = new net.minecraft.nbt.NBTTagCompound();
        stand.setString("id", "minecraft:armor_stand");
        net.minecraft.nbt.NBTTagList pos = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 3; i++) pos.appendTag(new net.minecraft.nbt.NBTTagDouble(0.5));
        stand.setTag("Pos", pos);
        java.lang.reflect.Field field = WorldEditJob.class.getDeclaredField("entities");
        field.setAccessible(true);
        ((List<net.minecraft.nbt.NBTTagCompound>) field.get(paste)).add(stand);
        client = start(paste);
        run(50);
        assertEquals(java.util.Arrays.asList("/setblock 0 64 0 minecraft:stone 0 replace", "/summon minecraft:armor_stand 0.5 64.5 0.5"), client.commands);
        assertEquals(1, paste.entityCount);
    }

    @Test public void aBoxOfOneBlockBecomesOneFillCommand() {
        FakeServer client = start(fill(5, 3, 4));
        run(50);
        assertEquals(java.util.Collections.singletonList("/fill 0 64 0 4 66 3 minecraft:stone 0 replace"), client.commands);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(60, client.count(Blocks.STONE));
        assertEquals("schematica.message.edit.commands_done", client.lastKey());
    }

    @Test public void fillBoxesStayWithinTheConfiguredVolumeAndChunks() {
        ConfigurationHandler.commandFillMaxVolume = 10;
        FakeServer client = start(new WorldEditJob(owner, 0, WorldEditJob.Kind.FILL, 10, 64, 0, 12, 2, 3, Blocks.STONE.getDefaultState(), null));
        run(200);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(72, client.count(Blocks.STONE));
        for (String command : client.commands) {
            String[] parts = command.split(" ");
            if (!parts[0].equals("/fill")) continue;
            int[] box = new int[6];
            for (int i = 0; i < 6; i++) box[i] = Integer.parseInt(parts[i + 1]);
            assertTrue(command, (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1) <= 10);
            assertEquals(command, box[0] >> 4, box[3] >> 4);
        }
    }

    @Test public void commandsAreSentWithoutWaitingForReplies() {
        Schematic schematic = new Schematic(null, 8, 1, 1);
        for (int x = 0; x < 8; x++) schematic.setBlockState(x, 0, 0, x % 2 == 0 ? Blocks.STONE.getDefaultState() : Blocks.DIRT.getDefaultState());
        FakeServer client = start(paste(schematic, ReplaceBehavior.ALL));
        client.delay = 5; // the server shows changes 5 ticks later and never replies in chat
        tick();
        assertEquals("only one probe before anything is seen working", 1, client.commands.size());
        run(5);
        tick();
        assertEquals(8, client.commands.size());
        run(50);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(8, client.commands.size());
    }

    @Test public void supportsComeBeforeAttachedBlocksAndFallingBlocksAndFluidsComeLast() {
        Schematic schematic = new Schematic(null, 4, 2, 1);
        schematic.setBlockState(0, 1, 0, Blocks.TORCH.getDefaultState());
        schematic.setBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        schematic.setBlockState(1, 0, 0, Blocks.SAND.getDefaultState());
        schematic.setBlockState(2, 0, 0, Blocks.WATER.getDefaultState());
        schematic.setBlockState(3, 0, 0, Blocks.PLANKS.getDefaultState());
        FakeServer client = start(paste(schematic, ReplaceBehavior.ALL));
        run(100);
        List<String> names = new ArrayList<>();
        for (String command : client.commands) names.add(command.split(" ")[command.startsWith("/fill") ? 7 : 4]);
        assertEquals(java.util.Arrays.asList("minecraft:stone", "minecraft:planks", "minecraft:torch", "minecraft:sand", "minecraft:water"), names);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
    }

    @Test public void matchingBlocksAreSkippedButMayFillGapsInABox() {
        FakeServer client = new FakeServer();
        client.set(1, 64, 0, Blocks.STONE.getDefaultState());
        queue = new CommandEditQueue(client);
        server = client;
        WorldEditJob job = fill(3, 1, 1);
        job.completion = client.completed::add;
        queue.submit(job, null);
        run(50);
        assertEquals(java.util.Collections.singletonList("/fill 0 64 0 2 64 0 minecraft:stone 0 replace"), client.commands);
        assertEquals(2, job.blockCount);
    }

    @Test public void aBlockThatNeverChangesIsRetriedThenSkippedWithoutStoppingThePaste() {
        FakeServer client = start(fill(3, 1, 1));
        client.refused.add(new BlockPos(1, 64, 0));
        run(2000);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(2, client.count(Blocks.STONE));
        int retries = 0;
        for (String command : client.commands) if (command.startsWith("/setblock 1 64 0 ")) retries++;
        assertEquals(CommandEditQueue.MAX_ATTEMPTS - 1, retries);
        assertTrue(client.keys().contains("schematica.message.edit.commands_failed_blocks"));
    }

    @Test public void aServerThatChangesNothingStopsTheEditAfterTheProbe() {
        FakeServer client = start(fill(40, 1, 1));
        client.ignoreAll = true;
        run(2000);
        assertEquals(java.util.Collections.singletonList(false), client.completed);
        assertEquals(CommandEditQueue.PROBE_FAILURES, client.commands.size());
        assertTrue(client.keys().contains("schematica.message.edit.commands_denied"));
    }

    @Test public void permissionRepliesStopTheEditAtOnce() {
        for (ITextComponent reply : new ITextComponent[] {new TextComponentTranslation("commands.generic.permission"),
            new TextComponentString("I'm sorry, but you do not have permission to perform this command.")}) {
            FakeServer client = start(fill(3, 1, 1));
            client.ignoreAll = true;
            tick();
            chat(reply);
            assertEquals(java.util.Collections.singletonList(false), client.completed);
            assertFalse(queue.busy());
        }
    }

    @Test public void otherPlayersChatDoesNotStopOrSlowTheEdit() {
        FakeServer client = start(fill(3, 1, 1));
        chat(new TextComponentTranslation("chat.type.text", "Bob", "you do not have permission to spam"));
        chat(new TextComponentTranslation("chat.type.admin", "Op", new TextComponentTranslation("commands.generic.permission")));
        run(50);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
    }

    @Test public void commandFeedbackIsHiddenWhileTheQueueRuns() {
        start(fill(3, 1, 1));
        tick();
        ClientChatReceivedEvent event = new ClientChatReceivedEvent(ChatType.SYSTEM, new TextComponentTranslation("commands.fill.success", 3)) {
            @Override public boolean isCancelable() { return true; } // Forge adds this to the real event at load time
        };
        queue.onChat(event);
        assertTrue(event.isCanceled());
    }

    @Test public void spamWarningsPauseAndSlowDown() {
        ConfigurationHandler.commandLimitPerTick = 1;
        FakeServer client = start(fill(1, 1, 40));
        ConfigurationHandler.commandFillMaxVolume = 1;
        run(3);
        int before = client.commands.size();
        chat(new TextComponentString("Please don't spam."));
        run(CommandEditQueue.SPAM_PAUSE);
        assertEquals(before, client.commands.size());
        run(20);
        int after = client.commands.size() - before;
        assertTrue("sent " + after, after > 0 && after <= 11);
        assertTrue(client.keys().contains("schematica.message.edit.commands_slowed"));
    }

    @Test public void unloadedChunksWaitUntilTheyLoad() {
        FakeServer client = new FakeServer();
        client.unloaded.add(1L);
        queue = new CommandEditQueue(client);
        server = client;
        WorldEditJob job = new WorldEditJob(owner, 0, WorldEditJob.Kind.FILL, 14, 64, 0, 4, 1, 1, Blocks.STONE.getDefaultState(), null);
        job.completion = client.completed::add;
        queue.submit(job, null);
        run(300);
        assertTrue(queue.busy());
        assertEquals(2, client.count(Blocks.STONE));
        assertTrue(client.keys().contains("schematica.message.edit.commands_waiting_chunks"));
        client.unloaded.clear();
        run(300);
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals(4, client.count(Blocks.STONE));
    }

    @Test public void lostContextStopsTheEdit() {
        FakeServer client = start(fill(3, 1, 1));
        client.valid = false;
        tick();
        assertEquals(java.util.Collections.singletonList(false), client.completed);
    }

    @Test public void nothingToChangeReportsNoChanges() {
        FakeServer client = new FakeServer();
        for (int x = 0; x < 3; x++) client.set(x, 64, 0, Blocks.STONE.getDefaultState());
        queue = new CommandEditQueue(client);
        server = client;
        WorldEditJob job = fill(3, 1, 1);
        job.completion = client.completed::add;
        queue.submit(job, null);
        run(20);
        assertTrue(client.commands.isEmpty());
        assertEquals(java.util.Collections.singletonList(true), client.completed);
        assertEquals("schematica.message.edit.no_changes", client.lastKey());
    }

    @Test public void keepModeOnlySendsCellsThatAreAir() {
        Schematic schematic = new Schematic(null, 3, 1, 1);
        for (int x = 0; x < 3; x++) schematic.setBlockState(x, 0, 0, Blocks.STONE.getDefaultState());
        FakeServer client = new FakeServer();
        client.set(1, 64, 0, Blocks.DIRT.getDefaultState());
        queue = new CommandEditQueue(client);
        server = client;
        WorldEditJob job = paste(schematic, ReplaceBehavior.NONE);
        job.completion = client.completed::add;
        queue.submit(job, null);
        run(50);
        assertEquals(java.util.Arrays.asList("/setblock 0 64 0 minecraft:stone 0 keep", "/setblock 2 64 0 minecraft:stone 0 keep"), client.commands);
        assertSame(Blocks.DIRT, client.get(1, 64, 0).getBlock());
    }

    private WorldEditJob paste(Schematic schematic, ReplaceBehavior replace) {
        WorldEditJob job = new WorldEditJob(owner, 0, WorldEditJob.Kind.PASTE, 0, 64, 0,
            schematic.getWidth(), schematic.getHeight(), schematic.getLength(), null, null, false, replace);
        job.capture(schematic, false, false);
        return job;
    }

    private void tick() { queue.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END)); }

    private ClientChatReceivedEvent chat(ITextComponent message) {
        ClientChatReceivedEvent event = new ClientChatReceivedEvent(ChatType.SYSTEM, message);
        queue.onChat(event);
        return event;
    }

    /** A server that runs setblock/fill commands against a block map, showing the result after a delay. */
    private static final class FakeServer implements CommandEditQueue.Client, CommandPlanner.View {
        boolean valid = true, ignoreAll;
        int delay = 1, now;
        final Set<BlockPos> refused = new HashSet<>();
        final Set<Long> unloaded = new HashSet<>();
        final Map<BlockPos, IBlockState> blocks = new HashMap<>();
        final List<Object[]> pending = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<Boolean> completed = new ArrayList<>();
        final List<ITextComponent> messages = new ArrayList<>();

        void set(int x, int y, int z, IBlockState state) { blocks.put(new BlockPos(x, y, z), state); }

        public IBlockState get(int x, int y, int z) {
            IBlockState state = blocks.get(new BlockPos(x, y, z));
            return state == null ? Blocks.AIR.getDefaultState() : state;
        }

        public boolean loaded(int chunkX, int chunkZ) { return !unloaded.contains((long) chunkX); }

        int count(Block block) {
            int count = 0;
            for (IBlockState state : blocks.values()) if (state.getBlock() == block) count++;
            return count;
        }

        List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (ITextComponent message : messages) if (message instanceof TextComponentTranslation) keys.add(((TextComponentTranslation) message).getKey());
            return keys;
        }

        String lastKey() { List<String> keys = keys(); return keys.get(keys.size() - 1); }

        public boolean valid(WorldEditJob job, World world) {
            // Runs once per client tick: apply what the server did by now
            now++;
            for (java.util.Iterator<Object[]> it = pending.iterator(); it.hasNext(); ) {
                Object[] entry = it.next();
                if ((int) entry[0] > now) continue;
                it.remove();
                apply((String) entry[1]);
            }
            return valid;
        }

        public CommandPlanner.View view(World world) { return this; }
        public double distanceSq(int chunkX, int chunkZ) { return 0; }

        public void send(String command) {
            commands.add(command);
            if (!ignoreAll) pending.add(new Object[] {now + delay, command});
        }

        private void apply(String command) {
            String[] parts = command.split(" ");
            if (!parts[0].equals("/fill") && !parts[0].equals("/setblock")) return;
            boolean fill = parts[0].equals("/fill");
            int[] box = new int[6];
            for (int i = 0; i < 3; i++) box[i] = box[i + 3] = Integer.parseInt(parts[i + 1]);
            if (fill) for (int i = 0; i < 3; i++) box[i + 3] = Integer.parseInt(parts[i + 4]);
            int at = fill ? 7 : 4;
            IBlockState state = Block.getBlockFromName(parts[at]).getStateFromMeta(Integer.parseInt(parts[at + 1]));
            boolean keep = parts[at + 2].equals("keep");
            for (int x = box[0]; x <= box[3]; x++) for (int y = box[1]; y <= box[4]; y++) for (int z = box[2]; z <= box[5]; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (refused.contains(pos) || unloaded.contains((long) (x >> 4))) continue;
                if (keep && get(x, y, z).getBlock() != Blocks.AIR) continue;
                if (state.getBlock() == Blocks.AIR) blocks.remove(pos); else blocks.put(pos, state);
            }
        }

        public void message(ITextComponent message) { messages.add(message); }
    }
}
