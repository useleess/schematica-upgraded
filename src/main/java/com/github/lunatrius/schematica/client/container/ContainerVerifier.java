// Container contents overlay and autofill after Tech Utils' inventory verifier (public domain) and QuickCraft's container tools, by HackerRouter, 2026.
package com.github.lunatrius.schematica.client.container;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.lwjgl.opengl.GL11;

import com.github.lunatrius.schematica.client.gui.framework.MinecraftUiDraw;
import com.github.lunatrius.schematica.client.gui.framework.UiBounds;
import com.github.lunatrius.schematica.client.gui.framework.UiTranslations;
import com.github.lunatrius.schematica.client.renderer.hud.InventoryPreview;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.handler.RenderColors;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Reference;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * When a container that a visible placement also has is opened, its slots show what the schematic holds there:
 * missing items as faded icons, extra, wrong and different (count/NBT) items outlined in the overlay colors, the
 * schematic's stack in a tooltip; the Fill button moves the needed items in from the player inventory.
 */
public final class ContainerVerifier {
    public static final ContainerVerifier INSTANCE = new ContainerVerifier();
    private static final int BUTTON_ID = 0x5C4E;

    private int[] clicked;
    private long clickedAt;
    private SchematicWorld placement;
    private int localX, localY, localZ;

    private ContainerVerifier() {}

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getWorld().isRemote) {
            clicked = new int[] {event.getPos().getX(), event.getPos().getY(), event.getPos().getZ()};
            clickedAt = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onOpen(GuiOpenEvent event) {
        placement = null;
        if (!(event.getGui() instanceof GuiContainer) || event.getGui() instanceof GuiInventory || event.getGui() instanceof GuiContainerCreative) return;
        if (clicked == null || System.currentTimeMillis() - clickedAt > 3000) return;
        int x = clicked[0], y = clicked[1], z = clicked[2];
        clicked = null;
        for (SchematicWorld world : ClientProxy.visiblePlacements()) {
            int lx = x - world.position.x, ly = y - world.position.y, lz = z - world.position.z;
            if (!world.isRenderingEnabled() || !world.isBlockRendered(lx, ly, lz)) continue;
            if (InventoryPreview.inventory(world, lx, ly, lz) == null) continue;
            placement = world;
            localX = lx; localY = ly; localZ = lz;
            return;
        }
    }

    private IInventory expected() {
        if (placement == null || !ClientProxy.visiblePlacements().contains(placement)) return null;
        try {
            return InventoryPreview.inventory(placement, localX, localY, localZ);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int[] geometry(GuiContainer gui) {
        return new int[] {gui.getGuiLeft(), gui.getGuiTop(), gui.getXSize()};
    }

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!ConfigurationHandler.containerAutofill || !(event.getGui() instanceof GuiContainer) || expected() == null) return;
        int[] g = geometry((GuiContainer) event.getGui());
        if (g == null) return;
        String label = UiTranslations.format("schematica.container.autofill");
        int width = Minecraft.getMinecraft().fontRenderer.getStringWidth(label) + 10;
        int x = g[0] + g[2] + 2 + width <= event.getGui().width ? g[0] + g[2] + 2 : Math.max(0, g[0] - width - 2);
        event.getButtonList().add(new GuiButton(BUTTON_ID, x, g[1], width, 20, label));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getButton().id != BUTTON_ID || !(event.getGui() instanceof GuiContainer)) return;
        event.setCanceled(true);
        autofill(Minecraft.getMinecraft(), (GuiContainer) event.getGui());
    }

    private void autofill(Minecraft mc, GuiContainer gui) {
        IInventory expected = expected();
        if (expected == null || mc.player == null) return;
        if (!mc.player.inventory.getItemStack().isEmpty()) {
            message(mc, UiTranslations.format("schematica.container.autofill.cursor"));
            return;
        }
        List<ContainerComparison.Target> targets = new ArrayList<>();
        List<ContainerComparison.Source> main = new ArrayList<>(), hotbar = new ArrayList<>();
        for (Object object : gui.inventorySlots.inventorySlots) {
            Slot slot = (Slot) object;
            ItemStack stack = slot.getStack();
            if (slot.inventory instanceof InventoryPlayer) {
                if (!stack.isEmpty()) (slot.getSlotIndex() < 9 ? hotbar : main).add(new ContainerComparison.Source(slot.slotNumber, stack));
                continue;
            }
            ItemStack wanted = wanted(expected, slot);
            if (wanted != null && slot.isItemValid(wanted)) targets.add(new ContainerComparison.Target(slot.slotNumber, wanted, stack));
        }
        main.addAll(hotbar);
        List<int[]> clicks = ContainerComparison.autofill(targets, main);
        try {
            for (int[] click : clicks) mc.playerController.windowClick(gui.inventorySlots.windowId, click[0], click[1], net.minecraft.inventory.ClickType.PICKUP, mc.player);
        } catch (RuntimeException error) {
            Reference.logger.warn("Container autofill stopped", error);
        }
        int filled = 0;
        for (ContainerComparison.Target target : targets) {
            Slot slot = gui.inventorySlots.getSlot(target.slot);
            if (ContainerComparison.compare(target.expected, slot.getStack()) == ContainerComparison.Status.MATCH) filled++;
        }
        message(mc, UiTranslations.format("schematica.container.autofill.done", filled, targets.size()));
    }

    /** Keeps what the opened container holds for the in-world labels (ContainerLabels). */
    private void remember(GuiContainer gui) {
        List<ItemStack> contents = new ArrayList<>();
        for (Object object : gui.inventorySlots.inventorySlots) {
            Slot slot = (Slot) object;
            if (slot.inventory instanceof InventoryPlayer) continue;
            ItemStack stack = slot.getStack();
            contents.add(stack.isEmpty() ? null : stack.copy());
        }
        ContainerLabels.seen(Minecraft.getMinecraft().world, placement.position.x + localX, placement.position.y + localY,
            placement.position.z + localZ, contents);
    }

    private static ItemStack wanted(IInventory expected, Slot slot) {
        int index = slot.getSlotIndex();
        ItemStack stack = index >= 0 && index < expected.getSizeInventory() ? expected.getStackInSlot(index) : ItemStack.EMPTY;
        return stack.isEmpty() ? null : stack;
    }

    private static void message(Minecraft mc, String text) {
        if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage(text, false);
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.getGui() instanceof GuiContainer)) return;
        IInventory expected = expected();
        if (expected == null) return;
        GuiContainer gui = (GuiContainer) event.getGui();
        remember(gui);
        if (!ConfigurationHandler.containerVerifier) return;
        int[] g = geometry(gui);
        if (g == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        List<String> hover = null;
        try (MinecraftUiDraw draw = new MinecraftUiDraw(mc)) {
            net.minecraft.client.renderer.GlStateManager.translate(0, 0, 300);
            for (Object object : gui.inventorySlots.inventorySlots) {
                Slot slot = (Slot) object;
                if (slot.inventory instanceof InventoryPlayer) continue;
                ItemStack wanted = wanted(expected, slot);
                ContainerComparison.Status status = ContainerComparison.compare(wanted, slot.getStack().isEmpty() ? null : slot.getStack());
                if (status == ContainerComparison.Status.MATCH) continue;
                UiBounds box = new UiBounds(g[0] + slot.xPos, g[1] + slot.yPos, 16, 16);
                int color = color(status);
                if (status == ContainerComparison.Status.MISSING) {
                    draw.item(wanted, box.x, box.y);
                    net.minecraft.client.renderer.GlStateManager.translate(0, 0, 50);
                    // the slot background over the icon makes it a faded "ghost" item
                    draw.fill(box, 0x908B8B8B);
                    net.minecraft.client.renderer.GlStateManager.translate(0, 0, -50);
                } else {
                    draw.fill(box.inset(1), color & 0x60FFFFFF);
                }
                draw.border(box, color | 0xFF000000);
                if (box.contains(event.getMouseX(), event.getMouseY())) {
                    hover = new ArrayList<>();
                    hover.add(wanted == null ? UiTranslations.format("schematica.container.expected_empty")
                        : UiTranslations.format("schematica.container.expected", wanted.getCount(), wanted.getDisplayName()));
                }
            }
            if (hover != null) {
                int width = 0;
                for (String line : hover) width = Math.max(width, draw.textWidth(line));
                int x = Math.min(event.getMouseX() + 12, gui.width - width - 8), y = Math.max(4, event.getMouseY() - 24);
                UiBounds box = new UiBounds(x - 3, y - 3, width + 6, hover.size() * 10 + 4);
                draw.fill(box, 0xF0100010);
                draw.border(box, 0xFF5000FF);
                for (String line : hover) { draw.text(line, x, y, 0xFFFFFFFF); y += 10; }
            }
        }
    }

    private static int color(ContainerComparison.Status status) {
        switch (status) {
            case MISSING: return RenderColors.MISSING.color();
            case EXTRA: return RenderColors.EXTRA.color();
            case WRONG_ITEM: return RenderColors.WRONG_BLOCK.color();
            default: return RenderColors.WRONG_STATE.color();
        }
    }
}
