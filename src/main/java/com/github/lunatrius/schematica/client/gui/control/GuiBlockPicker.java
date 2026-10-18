package com.github.lunatrius.schematica.client.gui.control;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiScreen;

import com.github.lunatrius.schematica.client.gui.framework.UiBounds;
import com.github.lunatrius.schematica.client.gui.framework.UiButton;
import com.github.lunatrius.schematica.client.gui.framework.UiDraw;
import com.github.lunatrius.schematica.client.gui.framework.UiLabel;
import com.github.lunatrius.schematica.client.gui.framework.UiListModel;
import com.github.lunatrius.schematica.client.gui.framework.UiPanel;
import com.github.lunatrius.schematica.client.gui.framework.UiRowList;
import com.github.lunatrius.schematica.client.gui.framework.UiScreen;
import com.github.lunatrius.schematica.client.gui.framework.UiTextField;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.gui.material.BlockChoices;
import com.github.lunatrius.schematica.client.gui.material.BlockChoices.Choice;
import com.github.lunatrius.schematica.reference.Reference;

/** Picks the block the material list's Replace puts in place of a material; clicking a row picks it and closes. */
public final class GuiBlockPicker extends UiScreen {
    private final Consumer<Choice> picked;
    /** Only blocks of this kind are listed while similarOnly is on (null: all blocks, no toggle). */
    private final Class<?> kind;
    private final UiListModel<Choice> model = new UiListModel<>(22, Choice::label);
    private List<Choice> choices;
    private boolean similarOnly = true;
    private UiTextField search;
    private UiLabel hint;
    private UiButton similar;
    private UiRowList<Choice> rows;

    public GuiBlockPicker(GuiScreen parent, String title, Consumer<Choice> picked) {
        this(parent, title, null, picked);
    }

    /** A picker that lists the blocks of kind (BlockChoices.kind) first, with a button to list all blocks. */
    public GuiBlockPicker(GuiScreen parent, String title, Class<?> kind, Consumer<Choice> picked) {
        super(parent, title);
        this.kind = kind;
        this.picked = picked;
    }

    @Override
    protected void createWidgets() {
        choices = BlockChoices.all(mc.player, UiTranslations.format("schematica.ui.material.replace.air"));
        hint = root.add(new UiLabel(() -> UiTranslations.format("schematica.ui.material.replace.pick"), 0xFFC0C0C0));
        search = root.add(new UiTextField(fontRenderer, 256, text -> { model.setQuery(text); if (rows != null) rows.sync(); }));
        similar = root.add(new UiButton(() -> (similarOnly ? "\u00a7a\u2714 " : "") + UiTranslations.format("schematica.ui.material.replace.similar"),
            button -> { if (button == 0) { similarOnly = !similarOnly; filter(); } }));
        similar.setTooltip(UiTranslations.format("schematica.ui.material.replace.similar.hover"));
        similar.setVisible(kind != null);
        rows = root.add(new UiRowList<>(model, ChoiceRow::new, 10));
        filter();
    }

    private void filter() {
        List<Choice> shown = new ArrayList<>();
        for (Choice choice : choices) if (!similarOnly || BlockChoices.similar(choice, kind)) shown.add(choice);
        model.setEntries(shown);
        if (rows != null) rows.sync();
    }

    @Override
    protected void opened() { input.focus(search); }

    @Override
    protected void layoutWidgets() {
        hint.setBounds(12, 24, Math.max(0, width - 24), 12);
        int toggle = kind == null ? 0 : fontRenderer.getStringWidth("\u2714 " + UiTranslations.format("schematica.ui.material.replace.similar")) + 16;
        search.setBounds(13, 38, Math.max(0, width - 26 - (toggle == 0 ? 0 : toggle + 4)), 14);
        if (kind != null) similar.setBounds(width - 13 - toggle, 35, toggle, 20);
        rows.setBounds(12, 58, Math.max(0, width - 24), Math.max(0, height - 68));
    }

    @Override
    protected boolean handleKey(char character, int code) {
        if (input.focused() != search && character > 32 && character != 127 && !isCtrlKeyDown()) {
            input.focus(search);
            search.setText(search.text() + character);
            return true;
        }
        return false;
    }

    private final class ChoiceRow extends UiPanel {
        private final Choice choice;
        private final int index;
        private boolean failedIcon;

        ChoiceRow(Choice choice, int index) { this.choice = choice; this.index = index; }

        @Override public boolean mouseDown(int x, int y, int button) {
            if (button != 0) return false;
            closeScreen();
            picked.accept(choice);
            return true;
        }

        @Override public void draw(UiDraw draw, int mouseX, int mouseY) {
            draw.fill(bounds(), containsVisible(mouseX, mouseY) ? 0xA0707070 : index % 2 == 0 ? 0xA0303030 : 0xA0101010);
            int x = bounds().x + 4, y = bounds().y + 3;
            draw.fill(new UiBounds(x, y, 16, 16), 0x20FFFFFF);
            if (!choice.icon.isEmpty() && !failedIcon) {
                try { draw.item(choice.icon, x, y); }
                catch (Exception e) { failedIcon = true; Reference.logger.debug("Could not render block icon {}", choice.registryName, e); }
            }
            int width = bounds().width - 30;
            String registry = "§7" + choice.registryName;
            int registryWidth = Math.min(width / 2, draw.textWidth(registry));
            draw.text(draw.trim(choice.name, Math.max(0, width - registryWidth - 8)), x + 22, bounds().y + 7, 0xFFFFFFFF);
            draw.text(draw.trim(registry, registryWidth), bounds().right() - registryWidth - 6, bounds().y + 7, 0xFFFFFFFF);
        }
    }
}
