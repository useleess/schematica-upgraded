package com.github.lunatrius.schematica.command;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;

import com.github.lunatrius.schematica.tool.ToolManager;
import com.github.lunatrius.schematica.tool.ToolMode;
import com.github.lunatrius.schematica.world.schematic.BlockStates;

/**
 * Client-side command to set primary or target (secondary) block on the current tool mode.
 * Usage: /sblock <primary|target> <block_name> [meta|state]
 */
public class CommandSchematicaSetBlock extends CommandBase {

    @Override
    public String getName() {
        return "sblock";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "schematica.command.setblock.usage";
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
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(getUsage(sender));
        }

        String type = args[0].toLowerCase();
        boolean isPrimary;
        if ("primary".equals(type) || "p".equals(type)) {
            isPrimary = true;
        } else if ("target".equals(type) || "secondary".equals(type) || "t".equals(type) || "s".equals(type)) {
            isPrimary = false;
        } else {
            throw new WrongUsageException(getUsage(sender));
        }

        Block block = getBlockByText(sender, args[1]);
        IBlockState state = args.length >= 3 ? convertArgToBlockState(block, args[2]) : block.getDefaultState();
        String name = BlockStates.toString(state);

        ToolMode mode = ToolManager.getCurrentMode();
        if (isPrimary) {
            mode.setPrimaryState(state);
            sender.sendMessage(new TextComponentTranslation("schematica.message.tool.primary_set", name, block.getMetaFromState(state), mode.getDisplayName()));
        } else {
            mode.setSecondaryState(state);
            sender.sendMessage(new TextComponentTranslation("schematica.message.tool.target_set", name, block.getMetaFromState(state), mode.getDisplayName()));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "primary", "target");
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, Block.REGISTRY.getKeys());
        }
        return Collections.emptyList();
    }
}
