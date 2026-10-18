package com.github.lunatrius.schematica.command;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

import com.github.lunatrius.schematica.SchematicaPlus;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.FileUtils;
import com.google.common.hash.Hashing;

public class CommandSchematicaRemove extends CommandSchematicaBase {

    @Override
    public String getName() {
        return Names.Command.Remove.NAME;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return Names.Command.Remove.Message.USAGE;
    }

    @SuppressWarnings("deprecation")
    private static String hash(String name) {
        return Hashing.md5().hashString(name, StandardCharsets.UTF_8).toString();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] arguments) throws CommandException {
        if (arguments.length < 1) {
            throw new WrongUsageException(getUsage(sender));
        }

        if (!(sender instanceof EntityPlayer)) {
            throw new CommandException(Names.Command.Remove.Message.PLAYERS_ONLY);
        }
        EntityPlayer player = (EntityPlayer) sender;

        boolean delete = false;
        String name = String.join(" ", arguments);

        if (arguments.length > 1) {
            // check if the last parameter is a hash, which constitutes a confirmation.
            String potentialNameHash = arguments[arguments.length - 1];
            if (potentialNameHash.length() == 32) {
                // We probably have a match; the name then is everything except the last element.
                String[] a = Arrays.copyOfRange(arguments, 0, arguments.length - 1);
                name = String.join(" ", a);

                if (potentialNameHash.equals(hash(name))) {
                    delete = true;
                }
            }
        }

        File schematicDirectory = SchematicaPlus.proxy.getPlayerSchematicDirectory(player, true);
        final File file;
        try {
            file = FileUtils.findSchematicFile(schematicDirectory, name, false);
        } catch (java.io.IOException e) {
            Reference.logger.warn("Rejected schematic removal filename", e);
            throw new CommandException(Names.Command.Remove.Message.SCHEMATIC_NOT_FOUND);
        }
        name = file.getName();

        if (file.isFile()) {
            if (delete) {
                if (file.delete()) {
                    sender.sendMessage(new TextComponentTranslation(Names.Command.Remove.Message.SCHEMATIC_REMOVED, name));
                } else {
                    throw new CommandException(Names.Command.Remove.Message.SCHEMATIC_NOT_FOUND);
                }
            } else {
                String confirmCommand = String.format("/%s %s %s", Names.Command.Remove.NAME, name, hash(name));
                final ITextComponent chatComponent = new TextComponentTranslation(
                    Names.Command.Remove.Message.ARE_YOU_SURE_START, name).appendSibling(new TextComponentString(" ["))
                        .appendSibling(
                            // Confirmation link
                            new TextComponentTranslation(Names.Command.Remove.Message.YES).setStyle(
                                new Style().setColor(TextFormatting.RED)
                                    .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, confirmCommand))))
                        .appendSibling(new TextComponentString("]"));

                sender.sendMessage(chatComponent);
            }
        } else {
            throw new CommandException(Names.Command.Remove.Message.SCHEMATIC_NOT_FOUND);
        }
    }
}
