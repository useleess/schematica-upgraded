package com.github.lunatrius.schematica.command;

import java.io.File;
import java.util.LinkedList;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;

import com.github.lunatrius.schematica.FileFilterSchematic;
import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.FileUtils;

public class CommandSchematicaList extends CommandSchematicaBase {

    private static final FileFilterSchematic FILE_FILTER_SCHEMATIC = new FileFilterSchematic(false);

    @Override
    public String getName() {
        return Names.Command.List.NAME;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return Names.Command.List.Message.USAGE;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] arguments) throws CommandException {
        if (!(sender instanceof EntityPlayer)) {
            throw new CommandException(Names.Command.Save.Message.PLAYERS_ONLY);
        }
        EntityPlayer player = (EntityPlayer) sender;

        int page = 0;
        try {
            if (arguments.length > 0) {
                page = Integer.parseInt(arguments[0]) - 1;
                if (page < 0) {
                    page = 0;
                }
            }
        } catch (NumberFormatException e) {
            throw new WrongUsageException(getUsage(sender));
        }

        int pageSize = 9; // maximum number of lines available without opening chat.
        int pageStart = page * pageSize;
        int pageEnd = pageStart + pageSize;
        int currentFile = 0;

        LinkedList<ITextComponent> componentsToSend = new LinkedList<>();

        File schematicDirectory = SchematicaPlus.proxy.getPlayerSchematicDirectory(player, true);
        if (schematicDirectory == null) {
            Reference.logger.warn("Unable to determine the schematic directory for player {}", player);
            throw new CommandException(Names.Command.Save.Message.PLAYER_SCHEMATIC_DIR_UNAVAILABLE);
        }

        if (!schematicDirectory.exists()) {
            if (!schematicDirectory.mkdirs()) {
                Reference.logger
                    .warn("Could not create player schematic directory {}", schematicDirectory.getAbsolutePath());
                throw new CommandException(Names.Command.Save.Message.PLAYER_SCHEMATIC_DIR_UNAVAILABLE);
            }
        }

        final File[] files = schematicDirectory.listFiles(FILE_FILTER_SCHEMATIC);
        if (files != null) {
            for (File path : files) {
                if (currentFile >= pageStart && currentFile < pageEnd) {
                    ITextComponent chatComponent = new TextComponentString(
                        String.format("%2d (%s): %s [", currentFile + 1, FileUtils.humanReadableByteCount(path.length()), path.getName()));
                    String removeCommand = String.format("/%s %s", Names.Command.Remove.NAME, path.getName());

                    ITextComponent removeLink = new TextComponentTranslation(Names.Command.List.Message.REMOVE)
                        .setStyle(new Style().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, removeCommand))
                            .setColor(TextFormatting.RED));
                    chatComponent.appendSibling(removeLink);
                    chatComponent.appendText("][");

                    String downloadCommand = String.format("/%s %s", Names.Command.Download.NAME, path.getName());
                    ITextComponent downloadLink = new TextComponentTranslation(Names.Command.List.Message.DOWNLOAD)
                        .setStyle(new Style().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, downloadCommand))
                            .setColor(TextFormatting.GREEN));
                    chatComponent.appendSibling(downloadLink);
                    chatComponent.appendText("]");

                    componentsToSend.add(chatComponent);
                }
                ++currentFile;
            }
        }

        if (currentFile == 0) {
            sender.sendMessage(new TextComponentTranslation(Names.Command.List.Message.NO_SCHEMATICS));
            return;
        }

        final int totalPages = (currentFile - 1) / pageSize;
        if (page > totalPages) {
            throw new CommandException(Names.Command.List.Message.NO_SUCH_PAGE);
        }

        sender.sendMessage(new TextComponentTranslation(Names.Command.List.Message.PAGE_HEADER, page + 1, totalPages + 1)
            .setStyle(new Style().setColor(TextFormatting.DARK_GREEN)));
        for (ITextComponent chatComponent : componentsToSend) {
            sender.sendMessage(chatComponent);
        }
    }
}
