// Rotation the printer reports to the server before a placement, so facing blocks get the schematic orientation, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.util.math.MathHelper;

/** A player rotation; the candidates are the four horizontal directions and straight up and down. */
public final class PrinterLook {
    public final float yaw, pitch;

    public PrinterLook(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }

    /** The candidates in a stable order: the current yaw first keeps the view still when it already fits. */
    public static PrinterLook[] candidates(float currentYaw) {
        float snapped = (MathHelper.floor(currentYaw / 90.0 + 0.5) & 3) * 90f;
        return new PrinterLook[] {
            new PrinterLook(snapped, 0), new PrinterLook(snapped + 90, 0), new PrinterLook(snapped + 180, 0),
            new PrinterLook(snapped + 270, 0), new PrinterLook(snapped, 90), new PrinterLook(snapped, -90)};
    }
}
