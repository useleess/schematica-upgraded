package com.github.lunatrius.schematica.client.gui.control;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import com.github.lunatrius.schematica.client.gui.framework.UiBounds;
import com.github.lunatrius.schematica.client.gui.framework.UiButton;
import com.github.lunatrius.schematica.client.gui.framework.UiDraw;
import com.github.lunatrius.schematica.client.gui.framework.UiLabel;
import com.github.lunatrius.schematica.client.gui.framework.UiListModel;
import com.github.lunatrius.schematica.client.gui.framework.UiPanel;
import com.github.lunatrius.schematica.client.gui.framework.UiRowList;
import com.github.lunatrius.schematica.client.gui.framework.UiScreen;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.gui.material.BlockChoices;
import com.github.lunatrius.schematica.client.world.SchematicLibrary;
import com.github.lunatrius.schematica.client.world.SchematicSourceData;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.schematic.BlockStates;
import com.github.lunatrius.schematica.world.schematic.MissingBlockGuesses;
import com.github.lunatrius.schematica.world.schematic.MissingBlocks;

/**
 * The blocks of a loaded file that this game does not have, one row per block, with the block used in their place.
 * Picks are saved for every file (MissingBlocks); when they change, the file is read again with them.
 */
public final class GuiMissingBlocks extends UiScreen {
    private static final String PREFIX = "schematica.ui.missing.";

    private final SchematicLibrary.Source<SchematicSourceData> source;
    private final UiListModel<Row> model = new UiListModel<>(22, row -> row.label);
    private final List<Row> entries = new ArrayList<>();
    private UiLabel hint;
    private UiRowList<Row> rows;
    private UiButton apply, air, guesses;

    private static final class Row {
        final String name, label;
        final long count;
        /** The block the file was read with, and the one picked here. */
        final IBlockState loaded;
        IBlockState picked;
        ItemStack icon;
        String pickedName;

        Row(String name, long count) {
            this.name = name;
            this.count = count;
            label = MissingBlockGuesses.displayName(name) + " " + name;
            loaded = MissingBlocks.replacement(name);
        }
    }

    public GuiMissingBlocks(GuiScreen parent, SchematicLibrary.Source<SchematicSourceData> source) {
        super(parent, UiTranslations.format(PREFIX + "title", source.file().getName()));
        this.source = source;
        for (Map.Entry<String, Long> entry : source.data().missingBlocks().entrySet()) entries.add(new Row(entry.getKey(), entry.getValue()));
        entries.sort((a, b) -> Long.compare(b.count, a.count));
    }

    /**
     * Whether to ask about a file: it has blocks this game does not have, and the player has not gone through them
     * ("Use these") since the game read the file. Reading it again (a new game, a replaced file, Reload) asks again,
     * as the file itself still has those blocks; loading the read file again does not.
     */
    public static boolean needed(SchematicLibrary.Source<SchematicSourceData> source) {
        return !source.data().missingBlocks().isEmpty() && !source.data().missingBlocksAnswered();
    }

    @Override
    protected void createWidgets() {
        for (Row row : entries) pick(row, row.loaded);
        model.setEntries(entries);
        hint = root.add(new UiLabel(() -> UiTranslations.format(PREFIX + "hint"), 0xFFC0C0C0));
        rows = root.add(new UiRowList<>(model, BlockRow::new, 10));
        apply = addButton(PREFIX + "apply", this::apply);
        apply.setTooltip(UiTranslations.format(PREFIX + "apply.hover").split("\\n"));
        air = addButton(PREFIX + "air", () -> { for (Row row : entries) pick(row, Blocks.AIR.getDefaultState()); });
        guesses = addButton(PREFIX + "guesses", () -> { for (Row row : entries) pick(row, MissingBlocks.guessed(row.name)); });
        guesses.setTooltip(UiTranslations.format(PREFIX + "guesses.hover"));
    }

    @Override
    protected void layoutWidgets() {
        hint.setBounds(12, 24, Math.max(0, width - 24), 12);
        rows.setBounds(12, 40, Math.max(0, width - 24), Math.max(0, height - 72));
        int x = 12;
        for (UiButton button : new UiButton[] {apply, air, guesses}) {
            int w = fontRenderer.getStringWidth(button.label()) + 16;
            button.setBounds(x, height - 28, w, 20);
            x += w + 4;
        }
    }

    private void pick(Row row, IBlockState state) {
        row.picked = state;
        if (state.getBlock() == Blocks.AIR) {
            row.icon = ItemStack.EMPTY;
            row.pickedName = UiTranslations.format("schematica.ui.material.replace.air");
            return;
        }
        ItemStack icon = BlockChoices.icon(state, mc.player);
        row.icon = icon == null ? ItemStack.EMPTY : icon;
        String name;
        try { name = icon == null ? BlockStates.toString(state) : icon.getDisplayName(); }
        catch (RuntimeException e) { name = BlockStates.toString(state); }
        row.pickedName = name;
    }

    /** Whether a pick differs from the block the file was read with. */
    private boolean changed;

    /**
     * Saves the picks and converts the file: it is read with them and written over the original (no backup, the
     * player's choice), so it has no missing blocks any more and is not asked about again.
     */
    private void apply() {
        Map<String, String> picks = new LinkedHashMap<>();
        changed = false;
        for (Row row : entries) {
            picks.put(row.name, BlockStates.toString(row.picked));
            changed |= row.picked != row.loaded;
        }
        MissingBlocks.choose(picks);
        source.data().markMissingBlocksAnswered();
        if (!ClientProxy.SCHEMATICS.sources().contains(source) || source.memory() || !source.file().isFile()) { closeScreen(); return; }
        if (changed && source.data().unsaved()) {
            // reading the file with the new picks drops edits that were not saved
            confirm(UiTranslations.format("schematica.ui.source.unsaved_title"),
                UiTranslations.format("schematica.ui.source.unsaved", source.name()), this::convert);
        } else {
            convert();
        }
    }

    private void convert() {
        closeScreen();
        String name = source.file().getName();
        try {
            if (changed) ClientProxy.reloadSourceWithPicks(source);
            java.io.File written = ClientProxy.saveConverted(source);
            if (mc.player != null) mc.player.sendMessage(new TextComponentString(TextFormatting.GREEN
                + UiTranslations.format(PREFIX + "saved", name, written.getName())));
        } catch (IOException | RuntimeException e) {
            Reference.logger.warn("Could not save the converted {}", source.file(), e);
            if (mc.player != null) {
                mc.player.sendMessage(new TextComponentString(TextFormatting.RED + UiTranslations.format(PREFIX + "save_failed", name)));
                mc.player.sendMessage(new TextComponentString(TextFormatting.RED
                    + UiTranslations.format("schematica.message.edit.failed_reason", com.github.lunatrius.schematica.util.MessageException.reason(e))));
            }
        }
    }

    private final class BlockRow extends UiPanel {
        private final Row row;
        private final int index;
        private final UiButton change;
        private boolean failedIcon;

        BlockRow(Row row, int index) {
            this.row = row;
            this.index = index;
            change = add(new UiButton(() -> UiTranslations.format(PREFIX + "change"), button -> {
                if (button != 0) return;
                // the guess tells which kind of block it is (a slab, a door...), so the list starts with those
                mc.displayGuiScreen(new GuiBlockPicker(GuiMissingBlocks.this,
                    UiTranslations.format(PREFIX + "pick", MissingBlockGuesses.displayName(row.name)),
                    BlockChoices.kind(MissingBlocks.guessed(row.name)), choice -> pick(row, choice.state)));
            }));
        }

        @Override public void layout(UiBounds screen) {
            int w = fontRenderer.getStringWidth(change.label()) + 10;
            change.setBounds(bounds().right() - w, bounds().y + 1, w, 20);
        }

        @Override public void draw(UiDraw draw, int mouseX, int mouseY) {
            draw.fill(bounds(), containsVisible(mouseX, mouseY) ? 0xA0707070 : index % 2 == 0 ? 0xA0303030 : 0xA0101010);
            int y = bounds().y + 7, half = (change.bounds().x - bounds().x) / 2;
            String count = " §7×" + String.format("%,d", row.count);
            draw.text(draw.trim(MissingBlockGuesses.displayName(row.name) + count, half - 8), bounds().x + 4, y, 0xFFFFFFFF);
            int x = bounds().x + half;
            draw.text("→", x, y, 0xFFC0C0C0);
            x += 10;
            draw.fill(new UiBounds(x, bounds().y + 3, 16, 16), 0x20FFFFFF);
            if (!row.icon.isEmpty() && !failedIcon) {
                try { draw.item(row.icon, x, bounds().y + 3); }
                catch (Exception e) { failedIcon = true; Reference.logger.debug("Could not render block icon {}", row.pickedName, e); }
            }
            int color = row.picked != row.loaded ? 0xFFFFFF55 : 0xFFFFFFFF;
            draw.text(draw.trim(row.pickedName, Math.max(0, change.bounds().x - x - 26)), x + 20, y, color);
            super.draw(draw, mouseX, mouseY);
        }

        @Override public void tick() {
            setTooltip(row.name, BlockStates.toString(row.picked));
            super.tick();
        }
    }
}
