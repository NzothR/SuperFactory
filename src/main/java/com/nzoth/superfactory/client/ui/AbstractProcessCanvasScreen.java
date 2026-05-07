package com.nzoth.superfactory.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public abstract class AbstractProcessCanvasScreen extends GuiContainer {

    protected static final int GRID_SIZE = 16;

    protected final List<CanvasButton> canvasButtons = new ArrayList<>();
    protected int canvasLeft;
    protected int canvasTop;
    protected int canvasWidth;
    protected int canvasHeight;
    protected int toolbarLeft;
    protected int toolbarTop;
    protected boolean dialogOpen;
    protected String dialogTitle = "";
    protected String dialogBody = "";
    protected Runnable dialogConfirm;
    protected List<String> pendingTooltip = Collections.emptyList();
    protected int pendingTooltipX;
    protected int pendingTooltipY;

    protected AbstractProcessCanvasScreen() {
        super(new EmptyContainer());
    }

    @Override
    public void initGui() {
        super.initGui();
        canvasButtons.clear();
        int leftNeiMargin = Math.max(128, width / 8);
        int rightNeiMargin = Math.max(360, width / 3);
        int availableWidth = Math.max(240, width - leftNeiMargin - rightNeiMargin);
        canvasWidth = Math.max(240, Math.min(availableWidth, (int) (width * 0.52D)));
        canvasHeight = Math.max(150, Math.min(height - 104, 700));
        canvasLeft = Math.max(leftNeiMargin, (width - canvasWidth) / 2);
        canvasTop = Math.max(32, (height - canvasHeight) / 2);
        toolbarLeft = canvasLeft + canvasWidth - 28;
        toolbarTop = canvasTop + 4;
        guiLeft = canvasLeft;
        guiTop = canvasTop;
        xSize = canvasWidth;
        ySize = canvasHeight;
        addCanvasButtons();
    }

    protected abstract void addCanvasButtons();

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCanvasForeground(mouseX, mouseY, partialTicks);
    }

    public void drawCanvasForeground(int mouseX, int mouseY, float partialTicks) {
        drawCanvasBackground();
        drawCanvasAfterNei(mouseX, mouseY, partialTicks);
    }

    public void drawCanvasAfterNei(int mouseX, int mouseY, float partialTicks) {
        pendingTooltip = Collections.emptyList();
        beforeCanvasDraw(mouseX, mouseY, partialTicks);
        enableCanvasScissor();
        drawCanvasBackground();
        drawCanvasContent(mouseX, mouseY, partialTicks);
        for (CanvasButton button : canvasButtons) {
            button.draw(mouseX, mouseY);
        }
        boolean overlayConsumesTooltips = drawCanvasOverlay(mouseX, mouseY, partialTicks);
        if (!dialogOpen && !overlayConsumesTooltips) {
            for (CanvasButton button : canvasButtons) {
                button.queueTooltip(mouseX, mouseY);
            }
        }
        if (dialogOpen) {
            drawDialog(mouseX, mouseY);
        }
        disableCanvasScissor();
        drawPendingTooltip();
    }

    private void drawCanvasBackground() {
        drawRect(canvasLeft - 1, canvasTop - 1, canvasLeft + canvasWidth + 1, canvasTop + canvasHeight + 1, 0xFF607086);
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0xFFD5DCE4);
        drawGrid();
    }

    protected abstract void drawCanvasContent(int mouseX, int mouseY, float partialTicks);

    protected void beforeCanvasDraw(int mouseX, int mouseY, float partialTicks) {}

    protected boolean drawCanvasOverlay(int mouseX, int mouseY, float partialTicks) {
        return false;
    }

    protected void queueTooltip(String tooltip, int mouseX, int mouseY) {
        if (tooltip != null && !tooltip.isEmpty()) {
            pendingTooltip = Collections.singletonList(tooltip);
            pendingTooltipX = mouseX + 8;
            pendingTooltipY = mouseY + 8;
        }
    }

    protected void drawPendingTooltip() {
        if (!pendingTooltip.isEmpty()) {
            drawHoveringText(pendingTooltip, pendingTooltipX, pendingTooltipY, fontRendererObj);
            pendingTooltip = Collections.emptyList();
        }
    }

    protected void enableCanvasScissor() {
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = resolution.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            canvasLeft * scale,
            mc.displayHeight - (canvasTop + canvasHeight) * scale,
            canvasWidth * scale,
            canvasHeight * scale);
    }

    protected void disableCanvasScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {}

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (dialogOpen) {
            handleDialogClick(mouseX, mouseY, mouseButton);
            return;
        }
        if (isInsideCanvas(mouseX, mouseY) && shouldHandleCanvasClickBeforeButtons(mouseX, mouseY, mouseButton)) {
            onCanvasMouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        for (CanvasButton button : canvasButtons) {
            if (button.contains(mouseX, mouseY)) {
                button.action.run();
                return;
            }
        }
        if (isInsideCanvas(mouseX, mouseY)) {
            onCanvasMouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (!dialogOpen) {
            onCanvasMouseReleased(mouseX, mouseY, state);
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (dialogOpen && keyCode == 1) {
            closeDialog();
            return;
        }
        if (shouldBlockGlobalKey(keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    protected boolean shouldBlockGlobalKey(int keyCode) {
        return false;
    }

    @Override
    public void onGuiClosed() {
        disableCanvasScissor();
        super.onGuiClosed();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !dialogOpen) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (isInsideCanvas(mouseX, mouseY) && shouldHandleCanvasWheel(mouseX, mouseY)) {
                onCanvasMouseWheel(mouseX, mouseY, wheel > 0 ? 1 : -1);
            }
        }
    }

    protected void onCanvasMouseClicked(int mouseX, int mouseY, int mouseButton) {}

    protected void onCanvasMouseReleased(int mouseX, int mouseY, int state) {}

    protected void onCanvasMouseWheel(int mouseX, int mouseY, int direction) {}

    protected boolean shouldHandleCanvasWheel(int mouseX, int mouseY) {
        return true;
    }

    protected boolean shouldHandleCanvasClickBeforeButtons(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    protected boolean isInsideCanvas(int mouseX, int mouseY) {
        return mouseX >= canvasLeft && mouseX < canvasLeft + canvasWidth
            && mouseY >= canvasTop
            && mouseY < canvasTop + canvasHeight;
    }

    public boolean intersectsCanvas(int x, int y, int w, int h) {
        return x < canvasLeft + canvasWidth && x + w > canvasLeft && y < canvasTop + canvasHeight && y + h > canvasTop;
    }

    protected CanvasButton addCanvasButton(int x, int y, int width, int height, String label, Runnable action) {
        return addCanvasButton(x, y, width, height, label, "", action);
    }

    protected CanvasButton addCanvasButton(int x, int y, int width, int height, String label, String tooltip,
        Runnable action) {
        CanvasButton button = new CanvasButton(x, y, width, height, label, tooltip, action);
        canvasButtons.add(button);
        return button;
    }

    protected void openConfirmDialog(String title, String body, Runnable confirm) {
        dialogOpen = true;
        dialogTitle = title;
        dialogBody = body;
        dialogConfirm = confirm;
    }

    protected void closeDialog() {
        dialogOpen = false;
        dialogConfirm = null;
    }

    protected void drawGrid() {
        int minorColor = 0xFFDDE5EE;
        int majorColor = 0xFFBAC8D8;
        int step = Math.max(1, getGridStep());
        int xStart = firstGridLine(canvasLeft, getGridOriginX(), step);
        int yStart = firstGridLine(canvasTop, getGridOriginY(), step);
        for (int x = xStart; x <= canvasLeft + canvasWidth; x += step) {
            boolean major = Math.floorDiv(x - getGridOriginX(), step) % 4 == 0;
            drawRect(x, canvasTop, x + (major ? 2 : 1), canvasTop + canvasHeight, major ? majorColor : minorColor);
        }
        for (int y = yStart; y <= canvasTop + canvasHeight; y += step) {
            boolean major = Math.floorDiv(y - getGridOriginY(), step) % 4 == 0;
            drawRect(canvasLeft, y, canvasLeft + canvasWidth, y + (major ? 2 : 1), major ? majorColor : minorColor);
        }
    }

    protected int getGridStep() {
        return GRID_SIZE;
    }

    protected int getGridOriginX() {
        return canvasLeft;
    }

    protected int getGridOriginY() {
        return canvasTop;
    }

    private int firstGridLine(int min, int origin, int step) {
        int offset = Math.floorMod(min - origin, step);
        return offset == 0 ? min : min + step - offset;
    }

    private void drawDialog(int mouseX, int mouseY) {
        int w = 180;
        int h = 74;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x55000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 14, 0xFF33465C);
        fontRendererObj.drawString(dialogTitle, x + 6, y + 4, 0xFFFFFFFF);
        fontRendererObj.drawString(dialogBody, x + 8, y + 24, 0xFFE8EEF5);
        drawDialogButton(x + 42, y + 50, 36, 16, "OK", mouseX, mouseY);
        drawDialogButton(x + 102, y + 50, 48, 16, "Cancel", mouseX, mouseY);
    }

    private void drawDialogButton(int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawRect(x, y, x + w, y + h, hover ? 0xFF607A96 : 0xFF45576C);
        drawCenteredString(fontRendererObj, text, x + w / 2, y + 4, 0xFFFFFFFF);
    }

    private void handleDialogClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int x = (width - 180) / 2;
        int y = (height - 74) / 2;
        if (mouseX >= x + 42 && mouseX < x + 78 && mouseY >= y + 50 && mouseY < y + 66) {
            Runnable confirm = dialogConfirm;
            closeDialog();
            if (confirm != null) {
                confirm.run();
            }
        } else if (mouseX >= x + 102 && mouseX < x + 150 && mouseY >= y + 50 && mouseY < y + 66) {
            closeDialog();
        }
    }

    protected final class CanvasButton {

        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String label;
        private final String tooltip;
        private final Runnable action;

        private CanvasButton(int x, int y, int width, int height, String label, String tooltip, Runnable action) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.tooltip = tooltip;
            this.action = action;
        }

        private void draw(int mouseX, int mouseY) {
            boolean hover = contains(mouseX, mouseY);
            drawRect(x, y, x + width, y + height, hover ? 0xFF607A96 : 0xFF45576C);
            drawCenteredString(fontRendererObj, label, x + width / 2, y + 6, 0xFFFFFFFF);
        }

        private void queueTooltip(int mouseX, int mouseY) {
            if (tooltip != null && !tooltip.isEmpty() && contains(mouseX, mouseY)) {
                AbstractProcessCanvasScreen.this.queueTooltip(tooltip, mouseX, mouseY);
            }
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private static final class EmptyContainer extends Container {

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return true;
        }
    }
}
