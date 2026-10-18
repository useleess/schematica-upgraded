package com.github.lunatrius.schematica.client.printer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import org.junit.Test;

import static org.junit.Assert.*;

public class PrinterBuildOrderTest {
    private static PrinterBuildOrder.Candidate at(int x, int y, int z, int tier) { return new PrinterBuildOrder.Candidate(x, y, z, tier, null); }

    private static String order(boolean downward, PrinterBuildOrder.Candidate... candidates) {
        List<PrinterBuildOrder.Candidate> list = new ArrayList<>(Arrays.asList(candidates));
        list.sort(PrinterBuildOrder.order(0.5, 65.62, 0.5, 64.0, downward));
        StringBuilder result = new StringBuilder();
        for (PrinterBuildOrder.Candidate c : list) result.append(c.x).append(',').append(c.y).append(',').append(c.z).append(' ');
        return result.toString().trim();
    }

    @Test public void lowerLayersThenTiersThenDistanceAndTheBodyLast() {
        assertEquals("2,63,0 4,63,0 3,63,0 2,64,0 0,64,1",
            order(false, at(0, 64, 1, 0), at(2, 64, 0, 0), at(3, 63, 0, 2), at(4, 63, 0, 0), at(2, 63, 0, 0)));
        assertEquals("2,64,0 2,63,0 0,64,1", order(true, at(2, 63, 0, 0), at(0, 64, 1, 0), at(2, 64, 0, 0)));
    }

    @Test public void tiers() {
        net.minecraft.init.Bootstrap.register();
        assertEquals(PrinterBuildOrder.STRUCTURE, PrinterBuildOrder.tier(net.minecraft.init.Blocks.STONE.getDefaultState()));
        assertEquals(PrinterBuildOrder.OTHER, PrinterBuildOrder.tier(net.minecraft.init.Blocks.GLASS.getDefaultState()));
        assertEquals(PrinterBuildOrder.OTHER, PrinterBuildOrder.tier(net.minecraft.init.Blocks.STONE_SLAB.getDefaultState()));
        assertEquals(PrinterBuildOrder.ATTACHED, PrinterBuildOrder.tier(net.minecraft.init.Blocks.TORCH.getDefaultState()));
        assertEquals(PrinterBuildOrder.ATTACHED, PrinterBuildOrder.tier(net.minecraft.init.Blocks.RAIL.getDefaultState()));
        assertEquals(PrinterBuildOrder.ATTACHED, PrinterBuildOrder.tier(net.minecraft.init.Blocks.WHEAT.getDefaultState()));
        assertEquals(PrinterBuildOrder.ATTACHED, PrinterBuildOrder.tier(net.minecraft.init.Blocks.UNPOWERED_REPEATER.getDefaultState()));
    }
}
