// SPDX-License-Identifier: LGPL-3.0-only
// Litematica Block Info Lines and Block Info Overlay, adapted by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.renderer.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

import com.github.lunatrius.schematica.client.gui.VerifierBlockInfo;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.input.Hotkeys;
import com.github.lunatrius.schematica.client.verifier.VerificationScan;
import com.github.lunatrius.schematica.client.verifier.VerificationScan.Pair;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.BlockInfoHudSettings;
import com.github.lunatrius.schematica.handler.VisualSettings;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.VoxelRayTrace;

public final class BlockInfoHud {
    public static final BlockInfoHud INSTANCE = new BlockInfoHud();
    private final List<String> lines = new ArrayList<>();
    private BlockInfoTarget target;
    private VerifierBlockInfo panel;
    private World world;
    private net.minecraft.entity.Entity camera;
    private long nextUpdate;
    private boolean errorLogged;

    private BlockInfoHud() {}

    public void clear() {
        lines.clear();
        target = null;
        panel = null;
        nextUpdate = 0;
        world = null;
        camera = null;
        errorLogged = false;
    }

    /** Renders the block info lines and, while the info overlay key is held, the block info overlay panel. */
    public void render(Minecraft mc, float partialTicks, boolean overlayAllowed) {
        boolean showLines = BlockInfoHudSettings.enabled && BlockInfoHudSettings.scale >= 0.0125;
        boolean showOverlay = overlayAllowed && VisualSettings.blockInfoOverlay && Hotkeys.held("renderInfoOverlay");
        if (mc.world == null || mc.player == null || mc.getRenderViewEntity() == null || mc.currentScreen != null
            || mc.gameSettings.hideGUI || !showLines && !showOverlay) {
            clear();
            return;
        }
        long now = System.nanoTime();
        if (world != mc.world || camera != mc.getRenderViewEntity()) {
            world = mc.world;
            camera = mc.getRenderViewEntity();
            nextUpdate = 0;
            errorLogged = false;
        }
        if (nextUpdate == 0 || now - nextUpdate >= 0) {
            nextUpdate = now + 50_000_000L;
            lines.clear();
            panel = null;
            try { update(partialTicks); }
            catch (RuntimeException error) {
                lines.clear();
                target = null;
                if (!errorLogged) Reference.logger.warn("Could not read the targeted block for the info HUD", error);
                errorLogged = true;
            }
        }
        if (showLines && !lines.isEmpty()) draw(mc);
        if (showOverlay && target != null) {
            if (panel == null) panel = panel(target);
            ScaledResolution screen = new ScaledResolution(mc);
            World layer = target.schematicLayer == null ? null : target.schematicLayer.world();
            int inventories = InventoryPreview.render(mc, layer instanceof SchematicWorld ? (SchematicWorld) layer : null, target.x, target.y, target.z,
                "center".equals(com.github.lunatrius.schematica.handler.VerifierOverlaySettings.alignment),
                com.github.lunatrius.schematica.handler.VerifierOverlaySettings.offsetY, screen.getScaledWidth(), screen.getScaledHeight());
            if (panel != null) VerifierHud.drawPanel(mc, panel, inventories);
        }
    }

    private static VerifierBlockInfo panel(BlockInfoTarget target) {
        if (target.showComparison()) return new VerifierBlockInfo(new Pair(state(target.schematic), state(target.client)));
        if (!target.schematicHit && target.client != null) return new VerifierBlockInfo(state(target.client), "litematica.gui.label.block_info.state_client");
        if (target.schematicHit && target.schematic != null) return new VerifierBlockInfo(state(target.schematic), "litematica.gui.label.block_info.state_schematic");
        return null;
    }

    private static VerificationScan.State state(BlockInfoTarget.State state) { return new VerificationScan.State(state.registryName, state.metadata); }

    private void update(float partialTicks) {
        target = null;
        List<WorldLayer> placements = new ArrayList<>();
        if (VisualSettings.schematicVisible()) {
            if (ClientProxy.schematic != null && ClientProxy.schematic.isRenderingEnabled()) placements.add(new WorldLayer(ClientProxy.schematic));
            for (SchematicWorld placement : ClientProxy.visiblePlacements()) {
                if (placement != ClientProxy.schematic && placement.isRenderingEnabled()) placements.add(new WorldLayer(placement));
            }
        }
        if (placements.isEmpty() && !(VisualSettings.blockInfoOverlay && Hotkeys.held("renderInfoOverlay"))) return;
        Vec3d start = camera.getPositionEyes(partialTicks);
        Vec3d direction = camera.getLook(partialTicks);
        Vec3d end = start.addVector(direction.x * 10, direction.y * 10, direction.z * 10);
        target = BlockInfoTarget.trace(new WorldLayer(world), placements, start, end, BlockInfoHudSettings.targetFluids);
        if (target == null || !target.showSchematic()) return;
        add("litematica.gui.label.block_info.state_schematic", target.schematic);
        if (target.showComparison()) {
            lines.add("");
            add("litematica.gui.label.block_info.state_client", target.client);
        }
    }

    private void add(String title, BlockInfoTarget.State state) {
        lines.add("§n" + UiTranslations.format(title));
        lines.add(state.registryName);
        lines.add(VerifierBlockInfo.metadata(state.metadata, ": "));
    }

    private void draw(Minecraft mc) {
        ScaledResolution screen = new ScaledResolution(mc);
        int lineHeight = mc.fontRenderer.FONT_HEIGHT + 2;
        double scale = BlockInfoHudSettings.scale;
        int y = BlockInfoHudSettings.alignment.y(screen.getScaledHeight(), lines.size() * lineHeight - 2, scale, BlockInfoHudSettings.offsetY);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.scale(scale, scale, 1);
            for (String line : lines) {
                if (!line.isEmpty()) {
                    int width = mc.fontRenderer.getStringWidth(line);
                    int x = BlockInfoHudSettings.alignment.x(screen.getScaledWidth(), width, scale, BlockInfoHudSettings.offsetX);
                    Gui.drawRect(x - 2, y - 2, x + width + 2, y + mc.fontRenderer.FONT_HEIGHT + 2, 0xA0505050);
                    mc.fontRenderer.drawString(line, x, y, 0xFFFFFFFF);
                }
                y += lineHeight;
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.enableDepth();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    private static final class WorldLayer extends BlockInfoTarget.Layer {
        private final World world;
        private final SchematicWorld schematic;

        WorldLayer(World world) {
            super(world instanceof SchematicWorld ? ((SchematicWorld) world).position.x : 0,
                world instanceof SchematicWorld ? ((SchematicWorld) world).position.y : 0,
                world instanceof SchematicWorld ? ((SchematicWorld) world).position.z : 0);
            this.world = world;
            schematic = world instanceof SchematicWorld ? (SchematicWorld) world : null;
        }

        private boolean available(int x, int y, int z) {
            return schematic != null ? schematic.isRenderingEnabled() && schematic.isBlockRendered(x, y, z)
                : world.isBlockLoaded(new net.minecraft.util.math.BlockPos(x, y, z));
        }

        @Override public BlockInfoTarget.State state(int x, int y, int z) {
            if (!available(x, y, z)) return null;
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (block.isAir(state, world, pos)) return null;
            Object name = net.minecraft.block.Block.REGISTRY.getNameForObject(block);
            return new BlockInfoTarget.State(name == null ? "#" + Block.getIdFromBlock(block) : name.toString(), block.getMetaFromState(state));
        }

        @Override public World world() { return world; }

        @Override public RayTraceResult trace(Vec3d start, Vec3d end, boolean fluids) {
            return VoxelRayTrace.trace(start, end, (x, y, z) -> {
                if (!available(x, y, z)) return null;
                net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
                net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
                Block block = state.getBlock();
                if (block.isAir(state, world, pos)) return null;
                boolean liquid = state.getMaterial().isLiquid() || block instanceof IFluidBlock;
                if (liquid ? !fluids : !block.canCollideCheck(state, false)) return null;
                return state.collisionRayTrace(world, pos, start, end);
            });
        }
    }
}
