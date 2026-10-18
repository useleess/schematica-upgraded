// Snow layers built up one click at a time, after the behavior of sakura-ryoko's litematica-printer2, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.printer;

import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A snow layer item places one layer; using it on the top of a snow block adds one more (ItemSnow), up to eight. The
 * printer places the first layer like any block and then clicks the top of the snow until it has the schematic's layers.
 */
public final class SnowLayers {
    static final String PROPERTY = "layers";

    private SnowLayers() {}

    /** The layers to add to the world's snow for the schematic's: 0 when it is no snow, or has as many or more. */
    public static int missing(IBlockState real, IBlockState target) {
        if (!(target.getBlock() instanceof BlockSnow) || real.getBlock() != target.getBlock()) return 0;
        return Math.max(0, target.getValue(BlockSnow.LAYERS) - real.getValue(BlockSnow.LAYERS));
    }

    /** The click that adds a layer: the top face of the snow block itself. */
    static SchematicPrinter.Click click(BlockPos pos, IBlockState real) {
        double top = real.getBlock() instanceof BlockSnow ? real.getValue(BlockSnow.LAYERS) / 8.0 : 1;
        return new SchematicPrinter.Click(pos, EnumFacing.UP, new Vec3d(pos.getX() + 0.5, pos.getY() + top, pos.getZ() + 0.5), false);
    }
}
