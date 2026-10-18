// SPDX-License-Identifier: LGPL-3.0-only
// Litematica tool modes, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.tool;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;

import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;

import com.github.lunatrius.schematica.world.schematic.BlockStates;

public enum ToolMode {

    AREA_SELECTION("litematica.tool_mode.name.area_selection", false, false, false, false),
    SCHEMATIC_PLACEMENT("litematica.tool_mode.name.schematic_placement", false, true, false, false),
    FILL("litematica.tool_mode.name.fill", true, false, true, false),
    REPLACE_BLOCK("litematica.tool_mode.name.replace_block", true, false, true, true),
    PASTE_SCHEMATIC("litematica.tool_mode.name.paste_schematic", true, true, false, false),
    GRID_PASTE("litematica.tool_mode.name.grid_paste", true, true, false, false),
    MOVE("litematica.tool_mode.name.move", true, false, false, false),
    DELETE("litematica.tool_mode.name.delete", true, false, false, false),
    REBUILD("litematica.tool_mode.name.rebuild", false, true, true, false);

    private final String translationKey;
    private final boolean creativeOnly;
    private final boolean usesSchematic;
    private final boolean usesBlockPrimary;
    private final boolean usesBlockSecondary;

    private IBlockState primaryState = null;
    private IBlockState secondaryState = null;

    private static final ToolMode[] VALUES = values();

    ToolMode(String translationKey, boolean creativeOnly, boolean usesSchematic,
             boolean usesBlockPrimary, boolean usesBlockSecondary) {
        this.translationKey = translationKey;
        this.creativeOnly = creativeOnly;
        this.usesSchematic = usesSchematic;
        this.usesBlockPrimary = usesBlockPrimary;
        this.usesBlockSecondary = usesBlockSecondary;
    }

    public boolean isCreativeOnly() {
        return this.creativeOnly;
    }

    /** ToolModeData.DELETE usePlacement: Delete targets the selected placement instead of the area selection. */
    public static boolean deleteUsesPlacement;

    public boolean getUsesSchematic() {
        return this == DELETE && deleteUsesPlacement || this.usesSchematic;
    }

    public boolean getUsesAreaSelection() {
        return !getUsesSchematic() || com.github.lunatrius.schematica.client.projects.SchematicProjects.hasProjectOpen();
    }

    public boolean getUsesBlockPrimary() {
        return this.usesBlockPrimary;
    }

    public boolean getUsesBlockSecondary() {
        return this.usesBlockSecondary;
    }

    /** The block a fill (or replacement) uses, or null when none was picked. */
    public IBlockState getPrimaryState() {
        return this.primaryState;
    }

    public void setPrimaryState(IBlockState state) {
        this.primaryState = state;
    }

    /** The block a replace looks for, or null when none was picked. */
    public IBlockState getSecondaryState() {
        return this.secondaryState;
    }

    public void setSecondaryState(IBlockState state) {
        this.secondaryState = state;
    }

    public String getPrimaryBlockName() {
        return this.primaryState == null ? null : BlockStates.toString(this.primaryState);
    }

    public String getSecondaryBlockName() {
        return this.secondaryState == null ? null : BlockStates.toString(this.secondaryState);
    }

    public String getDisplayName() {
        return UiTranslations.format(this.translationKey);
    }

    public ToolMode cycle(boolean forward) {
        boolean isCreative = Minecraft.getMinecraft().player != null
            && Minecraft.getMinecraft().player.capabilities.isCreativeMode;
        int numModes = VALUES.length;
        int inc = forward ? 1 : -1;
        int nextId = this.ordinal() + inc;

        for (int i = 0; i < numModes; i++) {
            if (nextId < 0) {
                nextId = numModes - 1;
            } else if (nextId >= numModes) {
                nextId = 0;
            }

            ToolMode mode = VALUES[nextId];
            if (isCreative || !mode.creativeOnly) {
                return mode;
            }
            nextId += inc;
        }

        return this;
    }

    public static ToolMode fromString(String name) {
        try {
            return ToolMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return AREA_SELECTION;
        }
    }
}
