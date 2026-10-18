package com.github.lunatrius.schematica.client.gui.material;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

import com.github.lunatrius.schematica.api.ISchematic;
import com.github.lunatrius.schematica.world.storage.Schematic;

/**
 * Walks a schematic's blocks 16x16x16 section by section, so a scan can stop between sections and empty sections of a
 * sparse {@link Schematic} are skipped without visiting their cells.
 */
final class SchematicSections {
    private static final int SECTION = 16;

    private final ISchematic schematic;
    private final int sectionsX, sectionsZ, count;

    SchematicSections(ISchematic schematic) {
        this.schematic = schematic;
        sectionsX = (schematic.getWidth() + SECTION - 1) / SECTION;
        sectionsZ = (schematic.getLength() + SECTION - 1) / SECTION;
        count = schematic instanceof Schematic ? ((Schematic) schematic).sectionCount()
            : sectionsX * sectionsZ * ((schematic.getHeight() + SECTION - 1) / SECTION);
    }

    int count() { return count; }

    /** Visits the non-air blocks of a section inside box ({minX, minY, minZ, maxX, maxY, maxZ}, exclusive; null = all). */
    void visit(int index, int[] box, Schematic.BlockVisitor visitor) {
        visit(index, box, (Schematic.OriginVisitor) (x, y, z, state, origin) -> visitor.visit(x, y, z, state));
    }

    /** visit with the missing block each block stands in for (null for blocks of the file). */
    void visit(int index, int[] box, Schematic.OriginVisitor visitor) {
        if (schematic instanceof Schematic) {
            ((Schematic) schematic).forEachBlockInSection(index, box, visitor);
            return;
        }
        int baseX = index % sectionsX * SECTION, baseZ = index / sectionsX % sectionsZ * SECTION;
        int baseY = index / (sectionsX * sectionsZ) * SECTION;
        int minX = Math.max(baseX, box == null ? 0 : box[0]), maxX = Math.min(Math.min(baseX + SECTION, schematic.getWidth()), box == null ? Integer.MAX_VALUE : box[3]);
        int minY = Math.max(baseY, box == null ? 0 : box[1]), maxY = Math.min(Math.min(baseY + SECTION, schematic.getHeight()), box == null ? Integer.MAX_VALUE : box[4]);
        int minZ = Math.max(baseZ, box == null ? 0 : box[2]), maxZ = Math.min(Math.min(baseZ + SECTION, schematic.getLength()), box == null ? Integer.MAX_VALUE : box[5]);
        for (int y = minY; y < maxY; y++) for (int z = minZ; z < maxZ; z++) for (int x = minX; x < maxX; x++) {
            if (!schematic.containsBlock(x, y, z)) continue;
            IBlockState state = schematic.getBlockState(x, y, z);
            if (state != null && state.getBlock() != Blocks.AIR) visitor.visit(x, y, z, state, null);
        }
    }
}
