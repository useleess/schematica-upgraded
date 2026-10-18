// SPDX-License-Identifier: LGPL-3.0-only
// Litematica sign text paste (MixinAbstractSignEditScreen), adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import java.lang.reflect.Field;

import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;

/** Fills a newly opened sign editor with the text of the schematic sign at the same position. */
public final class SignTextPaste {
    public static final SignTextPaste INSTANCE = new SignTextPaste();
    private static final Field TILE_SIGN = field();

    private SignTextPaste() {}

    /** GuiEditSign.tileSign by its development or its obfuscated (SRG) name. */
    private static Field field() {
        for (String name : new String[] {"tileSign", "field_146848_f"}) {
            try {
                Field field = GuiEditSign.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        Reference.logger.warn("GuiEditSign.tileSign not found; sign text paste is off");
        return null;
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!ConfigurationHandler.signTextPaste || TILE_SIGN == null || !(event.getGui() instanceof GuiEditSign)) return;
        try {
            TileEntitySign sign = (TileEntitySign) TILE_SIGN.get(event.getGui());
            if (sign == null) return;
            ITextComponent[] text = schematicText(sign.getPos());
            if (text == null) return;
            for (int i = 0; i < sign.signText.length && i < text.length; i++) {
                String line = text[i] == null ? "" : text[i].getUnformattedText();
                sign.signText[i] = new TextComponentString(line.length() > 15 ? line.substring(0, 15) : line);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            Reference.logger.warn("Could not paste the schematic sign text", error);
        }
    }

    private static ITextComponent[] schematicText(BlockPos pos) {
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            if (!world.isEnabled()) continue;
            BlockPos local = new BlockPos(pos.getX() - world.position.x, pos.getY() - world.position.y, pos.getZ() - world.position.z);
            if (!world.getSchematic().containsBlock(local)) continue;
            TileEntity tile = world.getTileEntity(local);
            if (tile instanceof TileEntitySign) return ((TileEntitySign) tile).signText;
        }
        return null;
    }
}
