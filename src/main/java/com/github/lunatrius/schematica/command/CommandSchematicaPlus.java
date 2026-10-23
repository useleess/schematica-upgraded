package com.github.lunatrius.schematica.command;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.github.lunatrius.schematica.FileFilterSchematic;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.util.FileUtils;

/**
 * A client command to load and place schematics while the full interface is not ported yet:
 * /splus load|unload|list|here|move|rotate|mirror|layer|show|entities|alpha|select.
 */
@SideOnly(Side.CLIENT)
public class CommandSchematicaPlus extends CommandBase {

    private static final FileFilterSchematic FILES = new FileFilterSchematic(false);
    private static final List<String> SUBCOMMANDS = Arrays.asList("load", "unload", "list", "here", "move", "rotate", "mirror", "layer",
        "show", "entities", "alpha", "select", "info", "paste");

    @Override
    public String getName() {
        return "splus";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/splus <load <file>|unload|list|here|move <x> <y> <z>|rotate <x|y|z>|mirror <x|y|z>|layer <on|off|n>|show|entities|alpha <0-1>|select|info|paste>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2 && args[0].equals("load")) {
            File[] files = ConfigurationHandler.schematicDirectory.listFiles(FILES);
            List<String> names = new ArrayList<>();
            if (files != null) for (File file : files) names.add(file.getName());
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (args.length == 2 && (args[0].equals("rotate") || args[0].equals("mirror"))) {
            return getListOfStringsMatchingLastWord(args, "x", "y", "z");
        }
        return Collections.emptyList();
    }

    private static void say(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    private static SchematicWorld current() throws CommandException {
        SchematicWorld world = ClientProxy.schematic;
        if (world == null) throw new CommandException("No schematic is loaded. Use /splus load <file>");
        return world;
    }

    private static EnumFacing.Axis axis(String name) throws CommandException {
        switch (name.toLowerCase()) {
            case "x": return EnumFacing.Axis.X;
            case "y": return EnumFacing.Axis.Y;
            case "z": return EnumFacing.Axis.Z;
            default: throw new CommandException("The axis must be x, y or z");
        }
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));
        switch (args[0]) {
            case "load": {
                if (args.length < 2) throw new WrongUsageException(getUsage(sender));
                File file;
                try {
                    file = FileUtils.findSchematicFile(ConfigurationHandler.schematicDirectory, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), false);
                } catch (java.io.IOException e) {
                    throw new CommandException("Invalid schematic name");
                }
                if (!file.isFile()) throw new CommandException("No such schematic: " + file.getName());
                say(sender, "Loading " + file.getName() + " ...");
                try {
                    boolean started = ClientProxy.loadSourceAsync(file, source -> {
                        try {
                            SchematicWorld world = ClientProxy.createPlacement(source);
                            ClientProxy.moveSchematicToPlayer(world);
                            ClientProxy.selectSchematic(world);
                            say(sender, "Loaded " + world.name + " (" + world.getDebugDimensions() + ") at "
                                + world.position.x + " " + world.position.y + " " + world.position.z);
                        } catch (java.io.IOException | RuntimeException e) {
                            say(sender, "Could not place " + file.getName() + ": " + e);
                        }
                    }, error -> say(sender, "Could not read " + file.getName() + ": " + error));
                    if (!started) say(sender, file.getName() + " is already being read");
                } catch (java.io.IOException e) {
                    throw new CommandException("Could not load " + file.getName() + ": " + e);
                }
                break;
            }
            case "unload":
                ClientProxy.removePlacement(current());
                say(sender, "Unloaded");
                break;
            case "list": {
                File[] files = ConfigurationHandler.schematicDirectory.listFiles(FILES);
                if (files == null || files.length == 0) {
                    say(sender, "No schematics in " + ConfigurationHandler.schematicDirectory);
                    break;
                }
                Arrays.sort(files);
                for (File file : files) say(sender, file.getName() + " (" + FileUtils.humanReadableByteCount(file.length()) + ")");
                break;
            }
            case "here":
                ClientProxy.moveSchematicToPlayer(current());
                say(sender, "Moved to you");
                break;
            case "move": {
                if (args.length < 4) throw new WrongUsageException(getUsage(sender));
                BlockPos origin = sender.getPosition();
                int x = (int) parseCoordinate(origin.getX(), args[1], false).getResult();
                int y = (int) parseCoordinate(origin.getY(), args[2], false).getResult();
                int z = (int) parseCoordinate(origin.getZ(), args[3], false).getResult();
                ClientProxy.moveSchematic(current(), x, y, z);
                say(sender, "Moved to " + x + " " + y + " " + z);
                break;
            }
            case "rotate":
                if (args.length < 2) throw new WrongUsageException(getUsage(sender));
                current().rotate(axis(args[1]));
                break;
            case "mirror":
                if (args.length < 2) throw new WrongUsageException(getUsage(sender));
                current().flip(axis(args[1]));
                break;
            case "layer": {
                SchematicWorld world = current();
                if (args.length < 2) throw new WrongUsageException(getUsage(sender));
                if (args[1].equals("off")) world.isRenderingLayer = false;
                else if (args[1].equals("on")) world.isRenderingLayer = true;
                else {
                    world.renderingLayer = Math.max(0, Math.min(world.getHeight() - 1, parseInt(args[1])));
                    world.isRenderingLayer = true;
                }
                say(sender, "Layer mode " + (world.isRenderingLayer ? "on, layer " + world.renderingLayer : "off"));
                break;
            }
            case "show": {
                SchematicWorld world = current();
                say(sender, "Rendering " + (world.toggleRendering() ? "on" : "off"));
                break;
            }
            case "entities": {
                SchematicWorld world = current();
                world.isRenderingEntities = !world.isRenderingEntities;
                say(sender, "Entities " + (world.isRenderingEntities ? "shown" : "hidden"));
                break;
            }
            case "alpha": {
                if (args.length < 2) {
                    say(sender, "alpha is " + ConfigurationHandler.alpha + (ConfigurationHandler.enableAlpha ? " (on)" : " (off)"));
                    break;
                }
                ConfigurationHandler.alpha = (float) Math.max(0, Math.min(1, parseDouble(args[1])));
                ConfigurationHandler.enableAlpha = ConfigurationHandler.alpha < 1;
                say(sender, "alpha is " + ConfigurationHandler.alpha);
                break;
            }
            case "select":
                ClientProxy.cycleSchematic();
                say(sender, ClientProxy.schematic == null ? "Nothing loaded" : "Selected " + ClientProxy.schematic.name);
                break;
            case "info": {
                say(sender, "Rendering " + (VisualSettings.rendering ? "enabled" : "disabled") + ", " + ClientProxy.loadedSchematics.size() + " loaded");
                for (SchematicWorld world : ClientProxy.loadedSchematics) {
                    say(sender, (world == ClientProxy.schematic ? "> " : "  ") + world.name + " " + world.getDebugDimensions() + " at "
                        + world.position.x + " " + world.position.y + " " + world.position.z + (world.isRendering ? "" : " (hidden)"));
                }
                break;
            }
            case "paste":
                if (!com.github.lunatrius.schematica.tool.ToolHandler.paste(net.minecraft.client.Minecraft.getMinecraft().player, current(), null)) {
                    throw new CommandException("Paste refused");
                }
                say(sender, "Pasting " + current().name);
                break;
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }
}
