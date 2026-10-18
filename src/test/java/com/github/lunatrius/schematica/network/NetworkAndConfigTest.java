package com.github.lunatrius.schematica.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.api.SchematicOrigin;
import com.github.lunatrius.schematica.api.SchematicRegion;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.network.message.MessageCapabilities;
import com.github.lunatrius.schematica.network.message.MessageDownloadChunk;
import com.github.lunatrius.schematica.network.message.MessageRangedClick;
import com.github.lunatrius.schematica.network.message.MessageRangedDig;
import com.github.lunatrius.schematica.network.transfer.DownloadGeometry;
import com.github.lunatrius.schematica.network.transfer.SchematicTransfer;
import com.github.lunatrius.schematica.proxy.CommonProxy;
import com.github.lunatrius.schematica.world.schematic.SchematicAlpha;
import com.github.lunatrius.schematica.world.storage.MultiRegionSchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

public class NetworkAndConfigTest {

    private static File dataDirectory;

    @BeforeClass
    public static void bootstrap() throws Exception {
        Bootstrap.register();
        dataDirectory = Files.createTempDirectory("schematica-test").toFile();
        SchematicaPlus.proxy = new CommonProxy() {
            @Override public File getDataDirectory() { return dataDirectory; }
            @Override public boolean loadSchematic(EntityPlayer player, File directory, String filename) { return false; }
            @Override public boolean isPlayerQuotaExceeded(EntityPlayer player) { return false; }
            @Override public File getPlayerSchematicDirectory(EntityPlayer player, boolean privateDirectory) { return dataDirectory; }
        };
    }

    private static Schematic sample() {
        Schematic schematic = new Schematic(new ItemStack(Blocks.STONE), 20, 18, 17);
        schematic.setBlockState(1, 2, 3, Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.NORTH));
        schematic.setBlockState(17, 16, 15, Blocks.GOLD_BLOCK.getDefaultState());
        schematic.setBlockState(2, 2, 2, Blocks.CHEST.getDefaultState());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "minecraft:chest");
        tag.setInteger("x", 2);
        tag.setInteger("y", 2);
        tag.setInteger("z", 2);
        NBTTagList items = new NBTTagList();
        NBTTagCompound stack = new NBTTagCompound();
        new ItemStack(Items.EMERALD, 3).writeToNBT(stack);
        stack.setByte("Slot", (byte) 0);
        items.appendTag(stack);
        tag.setTag("Items", items);
        TileEntity tile = NBTHelper.readTileEntityFromCompound(tag);
        schematic.setTileEntity(tile.getPos(), tile);
        return schematic;
    }

    @Test
    public void downloadChunkRoundTrips() {
        Schematic schematic = sample();
        MessageDownloadChunk original = new MessageDownloadChunk(schematic, 0, 0, 0);
        ByteBuf buffer = Unpooled.buffer();
        original.toBytes(buffer);
        MessageDownloadChunk decoded = new MessageDownloadChunk();
        decoded.fromBytes(buffer);
        assertEquals(0, buffer.readableBytes());

        Schematic target = new Schematic(null, 20, 18, 17);
        // copyToSchematic is private; the public effect is observable through a second encode of the decoded message
        ByteBuf again = Unpooled.buffer();
        decoded.toBytes(again);
        MessageDownloadChunk twice = new MessageDownloadChunk();
        twice.fromBytes(again);
        for (int i = 0; i < decoded.blocks.length; i++) assertEquals(original.blocks[i], twice.blocks[i]);
        assertEquals(1, decoded.tileEntities.size());
        assertTrue(decoded.tileEntities.get(0) instanceof TileEntityChest);
        assertEquals(Items.EMERALD, ((TileEntityChest) decoded.tileEntities.get(0)).getStackInSlot(0).getItem());
        // the stairs sit at (1,2,3) inside the first 16x16x16 piece: x major, then y, then z
        IBlockState stairs = decoded.blocks[1 * 256 + 2 * 16 + 3];
        assertSame(Blocks.OAK_STAIRS, stairs.getBlock());
        assertEquals(EnumFacing.NORTH, stairs.getValue(BlockStairs.FACING));
        assertNotNull(target);
    }

    @Test
    public void downloadGeometryRoundTrips() {
        List<SchematicRegion> regions = Arrays.asList(new SchematicRegion("a", 0, 0, 0, 3, 3, 3), new SchematicRegion("b", 5, 5, 5, 8, 8, 8));
        DownloadGeometry geometry = new DownloadGeometry(new SchematicOrigin(1, 2, 3), regions);
        ByteBuf buffer = Unpooled.buffer();
        geometry.write(buffer);
        DownloadGeometry read = DownloadGeometry.read(buffer);
        assertEquals(2, read.regions.size());
        assertEquals("b", read.regions.get(1).name);
        assertEquals(2, read.origin.y);
        read.validate(10, 10, 10);
    }

    @Test
    public void transferWalksEveryChunkThenEnds() {
        Schematic schematic = new Schematic(null, 20, 18, 17);
        SchematicTransfer transfer = new SchematicTransfer(schematic, "x.litematic");
        transfer.setState(SchematicTransfer.State.CHUNK_WAIT);
        int chunks = 0;
        while (transfer.state != SchematicTransfer.State.END_WAIT) {
            transfer.setState(SchematicTransfer.State.CHUNK);
            chunks++;
            transfer.confirmChunk(transfer.baseX, transfer.baseY, transfer.baseZ);
            assertTrue(chunks < 1000);
        }
        assertEquals(2 * 2 * 2, chunks);
    }

    @Test
    public void independentRegionsSurviveTheDownloadPayload() throws Exception {
        MultiRegionSchematic multi = new MultiRegionSchematic(null, 6, 1, 1);
        multi.setRegions(Arrays.asList(new SchematicRegion("left", 0, 0, 0, 2, 0, 0), new SchematicRegion("right", 3, 0, 0, 5, 0, 0)));
        Schematic left = new Schematic(null, 3, 1, 1);
        left.setBlockState(1, 0, 0, Blocks.STONE.getDefaultState());
        Schematic right = new Schematic(null, 3, 1, 1);
        right.setBlockState(2, 0, 0, Blocks.GOLD_BLOCK.getDefaultState());
        multi.addRegion(multi.getRegions().get(0), left);
        multi.addRegion(multi.getRegions().get(1), right);

        byte[] payload = SchematicAlpha.regionPayload(multi);
        assertNotNull(payload);
        // the flat schematic a client holds before the regions arrive
        Schematic flat = new Schematic(null, 6, 1, 1);
        flat.setRegions(multi.getRegions());
        ISchematic restored = SchematicAlpha.withRegionPayload(flat, payload);
        assertEquals(2, restored.getRegions().size());
        assertNotNull(restored.getRegionSchematic("left"));
        assertSame(Blocks.GOLD_BLOCK, restored.getRegionSchematic("right").getBlockState(2, 0, 0).getBlock());
    }

    @Test
    public void capabilitiesRoundTrip() {
        MessageCapabilities caps = new MessageCapabilities(true, false, true);
        caps.supportsRemoteEdit = true;
        ByteBuf buffer = Unpooled.buffer();
        caps.toBytes(buffer);
        MessageCapabilities read = new MessageCapabilities();
        read.fromBytes(buffer);
        assertTrue(read.isPrinterEnabled);
        assertTrue(!read.isSaveEnabled);
        assertTrue(read.isLoadEnabled);
        assertTrue(read.supportsRemoteEdit);
        assertTrue(!read.supportsAccuratePlacement);
        assertEquals(0, read.printerRange, 0);

        caps.printerRange = 24;
        buffer = Unpooled.buffer();
        caps.toBytes(buffer);
        read = new MessageCapabilities();
        read.fromBytes(buffer);
        assertEquals(24, read.printerRange, 0);

        // an older server sends no range: the vanilla reach is kept
        buffer = Unpooled.buffer();
        for (int i = 0; i < 7; i++) buffer.writeBoolean(true);
        read = new MessageCapabilities();
        read.fromBytes(buffer);
        assertTrue(read.supportsAirPlacement);
        assertEquals(0, read.printerRange, 0);
    }

    @Test
    public void rangedClickRoundTrip() {
        MessageRangedClick click = new MessageRangedClick(new BlockPos(-12, 70, 300), EnumFacing.WEST, 0f, 0.25f, 1f);
        ByteBuf buffer = Unpooled.buffer();
        click.toBytes(buffer);
        MessageRangedClick read = new MessageRangedClick();
        read.fromBytes(buffer);
        assertEquals(-12, read.x);
        assertEquals(70, read.y);
        assertEquals(300, read.z);
        assertEquals(EnumFacing.WEST.getIndex(), read.side);
        assertEquals(0.25f, read.hitY, 0);
        assertEquals(1f, read.hitZ, 0);
    }

    @Test
    public void rangedDigRoundTrip() {
        MessageRangedDig dig = new MessageRangedDig(MessageRangedDig.STOP, new BlockPos(5, 3, -900), EnumFacing.UP);
        ByteBuf buffer = Unpooled.buffer();
        dig.toBytes(buffer);
        MessageRangedDig read = new MessageRangedDig();
        read.fromBytes(buffer);
        assertEquals(MessageRangedDig.STOP, read.action);
        assertEquals(5, read.x);
        assertEquals(3, read.y);
        assertEquals(-900, read.z);
        assertEquals(EnumFacing.UP.getIndex(), read.side);

        ByteBuf bad = Unpooled.buffer();
        bad.writeByte(7);
        bad.writeInt(0); bad.writeInt(0); bad.writeInt(0);
        bad.writeByte(0);
        try {
            new MessageRangedDig().fromBytes(bad);
            fail("invalid action accepted");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void toolItemParsing() {
        // Forge's Configuration needs the running mod loader, so only the pure parts are checked here
        ConfigurationHandler.parseToolItem("minecraft:dye@4");
        assertEquals(Items.DYE, ConfigurationHandler.toolItemType);
        assertEquals(4, ConfigurationHandler.toolItemMeta);
        ConfigurationHandler.parseToolItem("minecraft:stick");
        assertEquals(Items.STICK, ConfigurationHandler.toolItemType);
        assertEquals(-1, ConfigurationHandler.toolItemMeta);
        ConfigurationHandler.parseToolItem("nosuchmod:thing");
        assertEquals(Items.STICK, ConfigurationHandler.toolItemType);
        assertTrue(ConfigurationHandler.SCHEMATIC_DIRECTORY_DEFAULT.getAbsolutePath().endsWith("schematics"));
        assertEquals(0.5, ConfigurationHandler.ALPHA_DEFAULT, 0.0001);
    }
}
