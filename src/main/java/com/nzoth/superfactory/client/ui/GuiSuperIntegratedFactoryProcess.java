package com.nzoth.superfactory.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizons.modularui.api.forge.ItemStackHandler;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;
import com.nzoth.superfactory.common.network.MessageUpdateProcessGraph;
import com.nzoth.superfactory.common.network.NetworkLoader;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;

import codechicken.nei.ItemPanels;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.Recipe;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.nei.GTNEIDefaultHandler;

public final class GuiSuperIntegratedFactoryProcess extends AbstractProcessCanvasScreen {

    private static final int EDITOR_WIDTH = 428;
    private static final int EDITOR_HEIGHT = 238;
    private static final int EDITOR_BUTTON_Y_OFFSET = 212;
    private static final int CANDIDATE_LIST_WIDTH = 420;
    private static final int CANDIDATE_LIST_HEIGHT = 232;
    private static final int CANDIDATE_ROW_HEIGHT = 26;

    private final MTESuperIntegratedFactory factory;
    private final ProcessGraph graph;
    private ProcessNode draggingNode;
    private int dragOffsetX;
    private int dragOffsetY;
    private boolean panning;
    private int lastPanMouseX;
    private int lastPanMouseY;
    private long lastClickTime;
    private int lastClickNodeId;
    private boolean editorOpen;
    private ProcessNode editingNode;
    private GuiTextField nameField;
    private GuiTextField durationField;
    private GuiTextField euField;
    private GuiTextField overclockField;
    private GuiTextField parallelField;
    private boolean amountEditorOpen;
    private ItemStackHandler amountEditHandler;
    private int amountEditSlot = -1;
    private GuiTextField amountField;
    private boolean candidateSelectorOpen;
    private List<RecipeMatchCandidate> recipeCandidates = new ArrayList<>();
    private int candidateScroll;
    private boolean closeCandidateSelectorAfterApply;
    private boolean oreVariantSelectorOpen;
    private int oreVariantSlot = -1;
    private boolean oreVariantReadOnly;
    private int oreVariantScroll;
    private List<ItemStack> oreVariantStacks = new ArrayList<>();
    private int lastMouseX;
    private int lastMouseY;
    private boolean draggingEdge;
    private int edgeDragFromNodeId;
    private int edgeDragStartX;
    private int edgeDragStartY;

    public GuiSuperIntegratedFactoryProcess(MTESuperIntegratedFactory factory) {
        this.factory = factory;
        this.graph = factory.getProcessGraph();
    }

    @Override
    public void initGui() {
        MTESuperIntegratedFactory.setClientEditingFactory(factory);
        super.initGui();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void addCanvasButtons() {
        addCanvasButton(
            toolbarLeft,
            toolbarTop,
            24,
            20,
            "+",
            tr("superfactory.machine.super_integrated_factory.process.add_node"),
            this::addNode);
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 24,
            24,
            20,
            "0",
            tr("superfactory.machine.super_integrated_factory.process.reset_view"),
            this::resetView);
        addCanvasButton(toolbarLeft, toolbarTop + 48, 24, 20, "X", "Close", this::closeGui);
    }

    @Override
    protected void drawCanvasContent(int mouseX, int mouseY, float partialTicks) {
        drawEdges();
        drawNodes();
        drawHeader();
    }

    @Override
    protected void beforeCanvasDraw(int mouseX, int mouseY, float partialTicks) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        updatePanning(mouseX, mouseY);
    }

    @Override
    protected boolean drawCanvasOverlay(int mouseX, int mouseY, float partialTicks) {
        if (editorOpen && editingNode != null) {
            drawNodeEditor(mouseX, mouseY);
            if (candidateSelectorOpen) {
                drawCandidateSelector(mouseX, mouseY);
            }
            if (oreVariantSelectorOpen) {
                drawOreVariantSelector(mouseX, mouseY);
            }
            if (amountEditorOpen) {
                drawAmountEditor(mouseX, mouseY);
            }
            return true;
        }
        return false;
    }

    @Override
    public void drawCanvasAfterNei(int mouseX, int mouseY, float partialTicks) {
        super.drawCanvasAfterNei(mouseX, mouseY, partialTicks);
        drawNeiDraggedStackAboveCanvas(mouseX, mouseY);
    }

    @Override
    protected boolean shouldHandleCanvasWheel(int mouseX, int mouseY) {
        return !editorOpen && !candidateSelectorOpen && !oreVariantSelectorOpen;
    }

    @Override
    protected boolean shouldHandleCanvasClickBeforeButtons(int mouseX, int mouseY, int mouseButton) {
        return editorOpen || candidateSelectorOpen || amountEditorOpen || oreVariantSelectorOpen;
    }

    @Override
    protected boolean shouldBlockGlobalKey(int keyCode) {
        return keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || keyCode == mc.gameSettings.keyBindDrop.getKeyCode();
    }

    @Override
    protected void drawGrid() {
        int minorColor = 0xFFB7C7D8;
        int majorColor = 0xFF7D95AF;
        int axisColor = 0xFF4D6680;
        int minWorldX = screenToWorldX(canvasLeft) - GRID_SIZE;
        int maxWorldX = screenToWorldX(canvasLeft + canvasWidth) + GRID_SIZE;
        int minWorldY = screenToWorldY(canvasTop) - GRID_SIZE;
        int maxWorldY = screenToWorldY(canvasTop + canvasHeight) + GRID_SIZE;
        int startWorldX = floorToGrid(minWorldX);
        int startWorldY = floorToGrid(minWorldY);
        for (int worldX = startWorldX; worldX <= maxWorldX; worldX += GRID_SIZE) {
            int x = worldToScreenX(worldX);
            if (x < canvasLeft || x > canvasLeft + canvasWidth) {
                continue;
            }
            int color = worldX == 0 ? axisColor : Math.floorDiv(worldX, GRID_SIZE) % 4 == 0 ? majorColor : minorColor;
            int thickness = worldX == 0 ? 3 : Math.floorDiv(worldX, GRID_SIZE) % 4 == 0 ? 2 : 1;
            drawRect(x, canvasTop, x + thickness, canvasTop + canvasHeight, color);
        }
        for (int worldY = startWorldY; worldY <= maxWorldY; worldY += GRID_SIZE) {
            int y = worldToScreenY(worldY);
            if (y < canvasTop || y > canvasTop + canvasHeight) {
                continue;
            }
            int color = worldY == 0 ? axisColor : Math.floorDiv(worldY, GRID_SIZE) % 4 == 0 ? majorColor : minorColor;
            int thickness = worldY == 0 ? 3 : Math.floorDiv(worldY, GRID_SIZE) % 4 == 0 ? 2 : 1;
            drawRect(canvasLeft, y, canvasLeft + canvasWidth, y + thickness, color);
        }
    }

    @Override
    protected void onCanvasMouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (editorOpen) {
            handleEditorClick(mouseX, mouseY, mouseButton);
            return;
        }
        ProcessNode node = findNodeAt(mouseX, mouseY);
        if (mouseButton == 2) {
            panning = true;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
        } else if (mouseButton == 0 && tryStartEdgeDrag(mouseX, mouseY)) {
            return;
        } else if (mouseButton == 0 && node != null) {
            graph.selectedNodeId = node.id;
            long now = System.currentTimeMillis();
            if (lastClickNodeId == node.id && now - lastClickTime < 350L) {
                openNodeEditor(node);
            } else {
                draggingNode = node;
                dragOffsetX = mouseX - worldToScreenX(node.x);
                dragOffsetY = mouseY - worldToScreenY(node.y);
            }
            lastClickNodeId = node.id;
            lastClickTime = now;
            syncGraph();
        } else if (mouseButton == 1 && node != null) {
            graph.selectedNodeId = node.id;
            if (GuiScreen.isShiftKeyDown()) {
                deleteNode(node.id);
            } else if (node.locked) {
                unlockNode(node);
                syncGraph();
            } else {
                openConfirmDialog("Delete Node", "Delete " + safeNodeName(node) + "?", () -> deleteNode(node.id));
            }
        }
    }

    @Override
    protected void onCanvasMouseReleased(int mouseX, int mouseY, int state) {
        if (draggingEdge) {
            finishEdgeDrag(mouseX, mouseY);
            return;
        }
        if (draggingNode != null) {
            draggingNode.x = Math.round((float) draggingNode.x / GRID_SIZE) * GRID_SIZE;
            draggingNode.y = Math.round((float) draggingNode.y / GRID_SIZE) * GRID_SIZE;
            draggingNode = null;
            syncGraph();
        }
        if (panning) {
            panning = false;
            syncGraph();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int mouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, mouseButton, timeSinceLastClick);
        if (draggingNode != null && !editorOpen) {
            draggingNode.x = snap(screenToWorldX(mouseX - dragOffsetX));
            draggingNode.y = snap(screenToWorldY(mouseY - dragOffsetY));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (editorOpen) {
            if (oreVariantSelectorOpen) {
                if (keyCode == 1) {
                    closeOreVariantSelector();
                }
                handleOreDictionaryKeys(typedChar, keyCode);
                return;
            }
            handleOreDictionaryKeys(typedChar, keyCode);
            if (handleEditorKey(typedChar, keyCode)) {
                return;
            }
            if (keyCode == 1) {
                closeNodeEditorWithDefaultName();
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void onCanvasMouseWheel(int mouseX, int mouseY, int direction) {
        double oldZoom = graph.zoom;
        graph.zoom = Math.max(0.35D, Math.min(3.0D, graph.zoom + direction * 0.1D));
        if (oldZoom != graph.zoom) {
            double worldX = (mouseX - canvasLeft - graph.viewportX) / oldZoom;
            double worldY = (mouseY - canvasTop - graph.viewportY) / oldZoom;
            graph.viewportX = (int) Math.round(mouseX - canvasLeft - worldX * graph.zoom);
            graph.viewportY = (int) Math.round(mouseY - canvasTop - worldY * graph.zoom);
            syncGraph();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if ((candidateSelectorOpen || oreVariantSelectorOpen) && wheel != 0) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (candidateSelectorOpen && isInsideCandidateSelector(mouseX, mouseY)) {
                onCandidateSelectorWheel(wheel > 0 ? 1 : -1);
            }
            if (oreVariantSelectorOpen && isInsideOreVariantSelector(mouseX, mouseY)) {
                onOreVariantSelectorWheel(wheel > 0 ? 1 : -1);
            }
        }
        int eventButton = Mouse.getEventButton();
        if (eventButton != 2) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (Mouse.getEventButtonState() && !editorOpen && !dialogOpen && isInsideCanvas(mouseX, mouseY)) {
            panning = true;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
        } else if (panning) {
            panning = false;
            syncGraph();
        }
    }

    private void drawHeader() {
        String title = tr("superfactory.machine.super_integrated_factory.process.title");
        fontRendererObj.drawString(title, canvasLeft, 8, 0xFFFFFFFF);
        fontRendererObj.drawString("Nodes: " + graph.nodes.size(), canvasLeft + 140, 8, 0xFFD8E8F6);
        fontRendererObj
            .drawString("Zoom: " + (int) Math.round(graph.zoom * 100) + "%", canvasLeft + 220, 8, 0xFFD8E8F6);
    }

    private void drawEdges() {
        for (ProcessEdge edge : graph.edges) {
            ProcessNode from = graph.findNode(edge.fromNodeId);
            ProcessNode to = graph.findNode(edge.toNodeId);
            if (from != null && to != null) {
                drawLine(
                    worldToScreenX(from.x + ProcessNode.WIDTH),
                    worldToScreenY(from.y + ProcessNode.HEIGHT / 2),
                    worldToScreenX(to.x),
                    worldToScreenY(to.y + ProcessNode.HEIGHT / 2),
                    0xFF71C7EC);
            }
        }
        if (draggingEdge) {
            drawLine(edgeDragStartX, edgeDragStartY, lastMouseX, lastMouseY, 0xFFFFFFFF);
        }
    }

    private void drawNodes() {
        for (ProcessNode node : graph.nodes) {
            int x = worldToScreenX(node.x);
            int y = worldToScreenY(node.y);
            int w = scale(ProcessNode.WIDTH);
            int h = scale(ProcessNode.HEIGHT);
            if (!intersectsCanvas(x, y, w, h)) {
                continue;
            }
            int header = scale(12);
            int border = node.id == graph.selectedNodeId ? 0xFFFFFFFF
                : node.endNode ? 0xFFE6C15C : node.locked ? 0xFF75D17C : 0xFF7D8794;
            drawRect(x + 2, y + 2, x + w + 2, y + h + 2, 0x33000000);
            drawRect(x, y, x + w, y + h, 0xFF243040);
            drawRect(x, y, x + w, y + header, node.endNode ? 0xFF6B5028 : 0xFF33465C);
            drawOutline(x, y, w, h, border);
            fontRendererObj.drawString(trimToWidth(safeNodeName(node), w - 8), x + 4, y + 3, 0xFFFFFFFF);
            String state = node.endNode ? "END" : node.locked ? "LOCKED" : "DRAFT";
            fontRendererObj.drawString(state, x + 4, y + Math.max(18, scale(22)), border);
            if (node.locked) {
                drawNodeConnector(x - 4, y + h / 2 - 4);
                drawNodeConnector(x + w - 4, y + h / 2 - 4);
            }
        }
    }

    private void drawNodeEditor(int mouseX, int mouseY) {
        int w = getEditorWidth();
        int h = getEditorHeight();
        int x = getEditorX();
        int y = getEditorY();
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, editingNode.endNode ? 0xFF6B5028 : 0xFF33465C);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.node_editor.title"),
            x + 6,
            y + 5,
            0xFFFFFFFF);
        drawOutline(x, y, w, h, editingNode.locked ? 0xFF75D17C : 0xFF607086);

        drawRect(x + 8, y + 24, x + 160, y + 204, 0xFF273445);
        drawRect(x + 166, y + 24, x + w - 8, y + 204, 0xFF273445);
        fontRendererObj.drawString("Name", x + 10, y + 28, 0xFFE8EEF5);
        nameField.drawTextBox();
        boolean allowEditorTooltips = !amountEditorOpen;
        drawHoverLabel("t", x + 10, y + 52, mouseX, mouseY, allowEditorTooltips ? "Recipe duration in ticks" : "");
        durationField.drawTextBox();
        drawHoverLabel(
            "EU/t",
            x + 84,
            y + 52,
            mouseX,
            mouseY,
            allowEditorTooltips ? "Recipe energy usage per tick" : "");
        euField.drawTextBox();
        drawHoverLabel("OC", x + 10, y + 76, mouseX, mouseY, allowEditorTooltips ? "Manual overclock count" : "");
        overclockField.drawTextBox();
        drawHoverLabel(
            "P",
            x + 84,
            y + 76,
            mouseX,
            mouseY,
            allowEditorTooltips ? "Usable parallel count for this recipe" : "");
        parallelField.drawTextBox();
        fontRendererObj.drawString("State: " + (editingNode.locked ? "Locked" : "Draft"), x + 10, y + 101, 0xFFE8EEF5);
        fontRendererObj
            .drawString("Recipe: " + trimToWidth(editingNode.recipeHandlerName, 92), x + 10, y + 115, 0xFFBFD0E2);
        fontRendererObj.drawString(
            "Check: " + (editingNode.lastRecipeCheckPassed ? "Passed" : "Unchecked"),
            x + 10,
            y + 129,
            editingNode.lastRecipeCheckPassed ? 0xFF75D17C : 0xFFFF7777);

        int rightX = x + 174;
        drawPatternPreview(rightX, y + 38, "Inputs", editingNode.inputHandler, 9, 18, mouseX, mouseY, true);
        drawPatternPreview(rightX, y + 94, "Outputs", editingNode.outputHandler, 9, 18, mouseX, mouseY);
        drawPatternPreview(rightX, y + 150, "NC", editingNode.nonConsumableHandler, 9, 9, mouseX, mouseY);

        drawEditorButton(
            x + 10,
            y + EDITOR_BUTTON_Y_OFFSET,
            48,
            16,
            editingNode.endNode ? "End*" : "End",
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.end_node") : "");
        drawEditorButton(
            x + 68,
            y + EDITOR_BUTTON_Y_OFFSET,
            52,
            16,
            editingNode.locked ? "Unlock" : "Check",
            mouseX,
            mouseY,
            allowEditorTooltips
                ? editingNode.locked ? tr("superfactory.machine.super_integrated_factory.node_editor.unlock")
                    : tr("superfactory.machine.super_integrated_factory.node_editor.check_recipe")
                : "");
        drawEditorButton(
            x + 132,
            y + EDITOR_BUTTON_Y_OFFSET,
            42,
            16,
            "OK",
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.confirm") : "");
        drawEditorButton(
            x + 184,
            y + EDITOR_BUTTON_Y_OFFSET,
            44,
            16,
            "Close",
            mouseX,
            mouseY,
            allowEditorTooltips ? "Close editor" : "");
    }

    private void handleEditorClick(int mouseX, int mouseY, int mouseButton) {
        if (editingNode == null) {
            return;
        }
        if (candidateSelectorOpen) {
            handleCandidateSelectorClick(mouseX, mouseY, mouseButton);
            return;
        }
        if (oreVariantSelectorOpen) {
            handleOreVariantSelectorClick(mouseX, mouseY, mouseButton);
            return;
        }
        if (amountEditorOpen) {
            handleAmountEditorClick(mouseX, mouseY, mouseButton);
            return;
        }
        if (handleNeiDraggedStackDrop(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (handleEditorTextFieldMouseClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (handlePatternSlotClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (mouseButton != 0) {
            return;
        }
        int x = getEditorX();
        int y = getEditorY();
        if (inRect(mouseX, mouseY, x + 10, y + EDITOR_BUTTON_Y_OFFSET, 48, 16)) {
            editingNode.endNode = !editingNode.endNode && !graph.hasOutgoingEdges(editingNode.id);
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 68, y + EDITOR_BUTTON_Y_OFFSET, 52, 16)) {
            if (editingNode.locked) {
                unlockNode(editingNode);
                setEditorFieldsEnabled(true);
            } else {
                applyEditorFields();
                checkCurrentRecipe();
            }
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 132, y + EDITOR_BUTTON_Y_OFFSET, 42, 16)) {
            applyEditorFields();
            checkCurrentRecipe();
            if (editingNode.lastRecipeCheckPassed) {
                editingNode.locked = true;
                closeNodeEditorWithoutChangingName();
            }
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 184, y + EDITOR_BUTTON_Y_OFFSET, 44, 16)) {
            closeNodeEditorWithDefaultName();
        }
    }

    private boolean handleEditorTextFieldMouseClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1) {
            return false;
        }
        if (editingNode != null && editingNode.locked) {
            return false;
        }
        boolean insideAnyField = isInsideAnyEditorTextField(mouseX, mouseY);
        if (mouseButton == 0) {
            if (!insideAnyField) {
                commitAndClearEditorFocus();
                return false;
            }
            setEditorTextFieldFocus(null);
        }
        if (handleTextFieldMouseClick(mouseX, mouseY, mouseButton, nameField, false)) {
            return true;
        }
        if (handleTextFieldMouseClick(mouseX, mouseY, mouseButton, durationField, true)) {
            return true;
        }
        if (handleTextFieldMouseClick(mouseX, mouseY, mouseButton, euField, true)) {
            return true;
        }
        if (handleTextFieldMouseClick(mouseX, mouseY, mouseButton, overclockField, true)) {
            return true;
        }
        return handleTextFieldMouseClick(mouseX, mouseY, mouseButton, parallelField, true, 1);
    }

    private boolean handleTextFieldMouseClick(int mouseX, int mouseY, int mouseButton, GuiTextField field,
        boolean numeric) {
        return handleTextFieldMouseClick(mouseX, mouseY, mouseButton, field, numeric, 0);
    }

    private boolean handleTextFieldMouseClick(int mouseX, int mouseY, int mouseButton, GuiTextField field,
        boolean numeric, int emptyNumericValue) {
        if (!fieldIsHovered(field, mouseX, mouseY)) {
            return false;
        }
        setEditorTextFieldFocus(field);
        if (mouseButton == 1) {
            field.setText(numeric ? String.valueOf(emptyNumericValue) : "");
            applyEditorFields();
            syncGraph();
            return true;
        }
        field.mouseClicked(mouseX, mouseY, mouseButton);
        return true;
    }

    private void commitAndClearEditorFocus() {
        if (editingNode != null) {
            normalizeFocusedNumericFields();
            applyEditorFields();
        }
        setEditorTextFieldFocus(null);
    }

    private boolean isInsideAnyEditorTextField(int mouseX, int mouseY) {
        return fieldIsHovered(nameField, mouseX, mouseY) || fieldIsHovered(durationField, mouseX, mouseY)
            || fieldIsHovered(euField, mouseX, mouseY)
            || fieldIsHovered(overclockField, mouseX, mouseY)
            || fieldIsHovered(parallelField, mouseX, mouseY);
    }

    private void setEditorTextFieldFocus(GuiTextField focusedField) {
        setTextFieldFocus(nameField, focusedField);
        setTextFieldFocus(durationField, focusedField);
        setTextFieldFocus(euField, focusedField);
        setTextFieldFocus(overclockField, focusedField);
        setTextFieldFocus(parallelField, focusedField);
    }

    private void setTextFieldFocus(GuiTextField field, GuiTextField focusedField) {
        if (field != null) {
            field.setFocused(field == focusedField);
        }
    }

    private void drawEditorButton(int x, int y, int w, int h, String text, int mouseX, int mouseY, String tooltip) {
        boolean hover = inRect(mouseX, mouseY, x, y, w, h);
        drawRect(x, y, x + w, y + h, hover ? 0xFF607A96 : 0xFF45576C);
        drawCenteredString(fontRendererObj, text, x + w / 2, y + 4, 0xFFFFFFFF);
        if (hover) {
            queueTooltip(tooltip, mouseX, mouseY);
        }
    }

    private void drawHoverLabel(String text, int x, int y, int mouseX, int mouseY, String tooltip) {
        fontRendererObj.drawString(text, x, y, 0xFFE8EEF5);
        if (inRect(mouseX, mouseY, x, y, fontRendererObj.getStringWidth(text), 8)) {
            queueTooltip(tooltip, mouseX, mouseY);
        }
    }

    private void drawPatternPreview(int x, int y, String label, ItemStackHandler handler, int columns, int visibleSlots,
        int mouseX, int mouseY) {
        drawPatternPreview(x, y, label, handler, columns, visibleSlots, mouseX, mouseY, false);
    }

    private void drawPatternPreview(int x, int y, String label, ItemStackHandler handler, int columns, int visibleSlots,
        int mouseX, int mouseY, boolean inputs) {
        fontRendererObj.drawString(label, x, y - 10, 0xFFE8EEF5);
        for (int i = 0; i < Math.min(visibleSlots, handler.getSlots()); i++) {
            int sx = x + i % columns * 18;
            int sy = y + i / columns * 18;
            drawRect(sx, sy, sx + 16, sy + 16, 0xFF324052);
            drawRect(sx + 1, sy + 1, sx + 15, sy + 15, 0xFF1A2430);
            ItemStack stack = inputs ? getRenderedInputStack(i) : handler.getStackInSlot(i);
            drawStack(stack, sx, sy);
            if (inputs && editingNode.hasInputVariants(i)
                && editingNode.getInputVariants(i)
                    .size() > 1) {
                drawRect(sx + 11, sy + 1, sx + 15, sy + 5, 0xFFE6C15C);
            }
            if (!amountEditorOpen && stack != null && inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                queueTooltip(stack.getDisplayName(), mouseX, mouseY);
            }
        }
    }

    private ItemStack getRenderedInputStack(int slot) {
        if (editingNode == null || !editingNode.hasInputVariants(slot)) {
            return editingNode == null ? null : editingNode.inputHandler.getStackInSlot(slot);
        }
        List<ItemStack> variants = editingNode.getInputVariants(slot);
        if (variants.isEmpty()) {
            return editingNode.inputHandler.getStackInSlot(slot);
        }
        int selected = editingNode.getInputVariantSelectedIndex(slot);
        int displayIndex = GuiScreen.isShiftKeyDown() ? selected
            : (int) ((System.currentTimeMillis() / 1000L + slot) % variants.size());
        return withDisplayAmount(
            variants.get(displayIndex),
            getEditableAmount(editingNode.inputHandler.getStackInSlot(slot)));
    }

    public boolean acceptDraggedStack(int mouseX, int mouseY, ItemStack draggedStack) {
        if (!editorOpen || editingNode == null || editingNode.locked || draggedStack == null) {
            return false;
        }
        SlotHit hit = findPatternSlot(mouseX, mouseY);
        if (hit == null) {
            return false;
        }
        ItemStack placed = draggedStack.copy();
        if (hit.handler == editingNode.nonConsumableHandler && GTUtility.getFluidFromDisplayStack(placed) != null) {
            return false;
        }
        if (containsEquivalentStack(hit.handler, placed)) {
            return false;
        }
        hit.handler.setStackInSlot(hit.slot, placed);
        invalidateNodeCheck();
        applyEditorFields();
        syncGraph();
        return true;
    }

    private boolean handleNeiDraggedStackDrop(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1) {
            return false;
        }
        if (ItemPanels.itemPanel.draggedStack != null
            && acceptDraggedStack(mouseX, mouseY, ItemPanels.itemPanel.draggedStack)) {
            ItemPanels.itemPanel.draggedStack = null;
            return true;
        }
        if (ItemPanels.bookmarkPanel.draggedStack != null
            && acceptDraggedStack(mouseX, mouseY, ItemPanels.bookmarkPanel.draggedStack)) {
            ItemPanels.bookmarkPanel.draggedStack = null;
            return true;
        }
        return false;
    }

    private boolean containsEquivalentStack(ItemStackHandler handler, ItemStack placed) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack existing = handler.getStackInSlot(i);
            if (existing == null) {
                continue;
            }
            if (GTUtility.getFluidFromDisplayStack(existing) != null
                || GTUtility.getFluidFromDisplayStack(placed) != null) {
                FluidStack existingFluid = GTUtility.getFluidFromDisplayStack(existing);
                FluidStack placedFluid = GTUtility.getFluidFromDisplayStack(placed);
                if (existingFluid != null && placedFluid != null && existingFluid.isFluidEqual(placedFluid)) {
                    return true;
                }
            } else if (GTOreDictUnificator.isInputStackEqual(existing, placed)
                || GTOreDictUnificator.isInputStackEqual(placed, existing)
                || GTUtility.areStacksEqual(existing, placed, false)) {
                    return true;
                }
        }
        return false;
    }

    private void drawStack(ItemStack stack, int x, int y) {
        if (stack == null) {
            return;
        }
        RenderHelper.enableGUIStandardItemLighting();
        itemRender.zLevel = 0.0F;
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.renderEngine, stack, x, y);
        itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.renderEngine, stack, x, y, null);
        itemRender.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    private void drawNeiDraggedStackAboveCanvas(int mouseX, int mouseY) {
        ItemStack dragged = ItemPanels.itemPanel.draggedStack != null ? ItemPanels.itemPanel.draggedStack
            : ItemPanels.bookmarkPanel.draggedStack;
        if (dragged == null) {
            return;
        }
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem renderItem = itemRender;
        float previousZ = renderItem.zLevel;
        renderItem.zLevel = 350.0F;
        renderItem.renderItemAndEffectIntoGUI(fontRendererObj, mc.renderEngine, dragged, mouseX - 8, mouseY - 8);
        renderItem.renderItemOverlayIntoGUI(fontRendererObj, mc.renderEngine, dragged, mouseX - 8, mouseY - 8, null);
        renderItem.zLevel = previousZ;
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

    private boolean handlePatternSlotClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1 && mouseButton != 2) {
            return false;
        }
        SlotHit hit = findPatternSlot(mouseX, mouseY);
        if (hit == null || hit.handler.getStackInSlot(hit.slot) == null) {
            return hit != null;
        }
        if (mouseButton == 0 && hit.handler == editingNode.inputHandler && editingNode.hasInputVariants(hit.slot)) {
            openOreVariantSelector(hit.slot);
            return true;
        }
        if (editingNode.locked) {
            return true;
        }
        if (mouseButton == 1) {
            hit.handler.setStackInSlot(hit.slot, null);
            if (hit.handler == editingNode.inputHandler) {
                editingNode.clearInputVariants(hit.slot);
            }
            invalidateNodeCheck();
            syncGraph();
        } else if (mouseButton == 2) {
            openAmountEditor(hit.handler, hit.slot);
        }
        return true;
    }

    private SlotHit findPatternSlot(int mouseX, int mouseY) {
        if (editingNode == null) {
            return null;
        }
        int x = getEditorX();
        int y = getEditorY();
        int rightX = x + 174;
        SlotHit hit = findPatternSlot(mouseX, mouseY, rightX, y + 38, editingNode.inputHandler, 9, 18);
        if (hit != null) {
            return hit;
        }
        hit = findPatternSlot(mouseX, mouseY, rightX, y + 94, editingNode.outputHandler, 9, 18);
        if (hit != null) {
            return hit;
        }
        return findPatternSlot(mouseX, mouseY, rightX, y + 150, editingNode.nonConsumableHandler, 9, 9);
    }

    private SlotHit findPatternSlot(int mouseX, int mouseY, int x, int y, ItemStackHandler handler, int columns,
        int visibleSlots) {
        for (int i = 0; i < Math.min(visibleSlots, handler.getSlots()); i++) {
            int sx = x + i % columns * 18;
            int sy = y + i / columns * 18;
            if (inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                return new SlotHit(handler, i);
            }
        }
        return null;
    }

    private void openAmountEditor(ItemStackHandler handler, int slot) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack == null) {
            return;
        }
        amountEditorOpen = true;
        amountEditHandler = handler;
        amountEditSlot = slot;
        int w = 160;
        int h = 70;
        int x = canvasLeft + (canvasWidth - w) / 2;
        int y = canvasTop + (canvasHeight - h) / 2;
        amountField = createTextField(x + 48, y + 28, 86, String.valueOf(getEditableAmount(stack)));
        amountField.setFocused(true);
    }

    private void drawAmountEditor(int mouseX, int mouseY) {
        int w = 160;
        int h = 70;
        int x = canvasLeft + (canvasWidth - w) / 2;
        int y = canvasTop + (canvasHeight - h) / 2;
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj.drawString("Set Amount", x + 6, y + 5, 0xFFFFFFFF);
        fontRendererObj.drawString("Amount", x + 10, y + 32, 0xFFE8EEF5);
        amountField.drawTextBox();
        drawEditorButton(x + 34, y + 50, 38, 16, "OK", mouseX, mouseY, "Confirm amount");
        drawEditorButton(x + 88, y + 50, 48, 16, "Cancel", mouseX, mouseY, "Cancel");
    }

    private void drawCandidateSelector(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj.drawString("Select Recipe", x + 6, y + 5, 0xFFFFFFFF);
        int rowY = y + 24;
        if (recipeCandidates.isEmpty()) {
            fontRendererObj.drawString("No matching recipes", x + 10, rowY, 0xFFFFB8B8);
        } else {
            int startIndex = Math.min(candidateScroll, Math.max(0, recipeCandidates.size() - getCandidateRows()));
            int endIndex = Math.min(recipeCandidates.size(), startIndex + getCandidateRows());
            for (int i = startIndex; i < endIndex; i++) {
                RecipeMatchCandidate candidate = recipeCandidates.get(i);
                int displayIndex = i - startIndex;
                int itemY = rowY + displayIndex * CANDIDATE_ROW_HEIGHT;
                int detailX = x + w - 34;
                boolean hover = inRect(mouseX, mouseY, x + 8, itemY - 2, w - 50, CANDIDATE_ROW_HEIGHT - 3);
                drawRect(
                    x + 8,
                    itemY - 2,
                    x + w - 8,
                    itemY + CANDIDATE_ROW_HEIGHT - 4,
                    hover ? 0xFF405872 : 0xFF273445);
                drawCandidateIconFormula(candidate, x + 12, itemY, w - 88, mouseX, mouseY);
                fontRendererObj.drawString(
                    candidate.recipe.mDuration + "t " + candidate.recipe.mEUt + "EU/t",
                    x + 12,
                    itemY + 16,
                    candidate.ratioMatches ? 0xFFBFD0E2 : 0xFFFFC7A0);
                drawEditorButton(detailX, itemY + 2, 22, 16, "...", mouseX, mouseY, "Open in NEI");
            }
            drawScrollBar(x + w - 10, y + 24, h - 56, startIndex, endIndex, recipeCandidates.size());
        }
        drawEditorButton(x + w - 64, y + h - 24, 48, 16, "Back", mouseX, mouseY, "Back");
    }

    private void handleOreDictionaryKeys(char typedChar, int keyCode) {
        if (editingNode == null || editingNode.locked) {
            return;
        }
        ItemStack stack = getHoveredPatternOrVariantStack(lastMouseX, lastMouseY);
        if (stack == null) {
            return;
        }
        if (typedChar == 'r' || typedChar == 'R') {
            GuiCraftingRecipe.openRecipeGui("item", stack.copy());
        } else if (typedChar == 'u' || typedChar == 'U') {
            GuiUsageRecipe.openRecipeGui("item", stack.copy());
        } else if (typedChar == 'a' || typedChar == 'A') {
            ItemStack bookmarkStack = stack.copy();
            bookmarkStack.stackSize = Math.max(1, bookmarkStack.stackSize);
            ItemPanels.bookmarkPanel.addItem(bookmarkStack);
        }
    }

    private ItemStack getHoveredPatternOrVariantStack(int mouseX, int mouseY) {
        if (oreVariantSelectorOpen) {
            int variantIndex = getHoveredOreVariantIndex(mouseX, mouseY);
            if (variantIndex >= 0 && variantIndex < oreVariantStacks.size()) {
                return oreVariantStacks.get(variantIndex);
            }
        }
        SlotHit hit = findPatternSlot(mouseX, mouseY);
        return getRenderedPatternStack(hit);
    }

    private ItemStack getRenderedPatternStack(SlotHit hit) {
        if (hit == null || hit.handler == null) {
            return null;
        }
        if (hit.handler == editingNode.inputHandler) {
            return getRenderedInputStack(hit.slot);
        }
        return hit.handler.getStackInSlot(hit.slot);
    }

    private void drawOreVariantSelector(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj.drawString("Ore Dictionary Variants", x + 6, y + 5, 0xFFFFFFFF);
        int rowY = y + 24;
        if (oreVariantStacks.isEmpty()) {
            fontRendererObj.drawString("No variants", x + 10, rowY, 0xFFFFB8B8);
        } else {
            int columns = Math.max(1, (w - 28) / 18);
            int rows = Math.max(1, (h - 54) / 18);
            int startIndex = Math.min(oreVariantScroll, Math.max(0, oreVariantStacks.size() - columns * rows));
            int index = startIndex;
            for (int row = 0; row < rows && index < oreVariantStacks.size(); row++) {
                for (int col = 0; col < columns && index < oreVariantStacks.size(); col++) {
                    int sx = x + 10 + col * 18;
                    int sy = rowY + row * 18;
                    ItemStack stack = oreVariantStacks.get(index);
                    int selectedIndex = editingNode.getInputVariantSelectedIndex(oreVariantSlot);
                    drawRect(sx, sy, sx + 16, sy + 16, index == selectedIndex ? 0xFF405872 : 0xFF273445);
                    drawStack(stack, sx, sy);
                    if (inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                        queueTooltip(stack.getDisplayName(), mouseX, mouseY);
                    }
                    index++;
                }
            }
            drawVariantScrollBar(x + w - 10, y + 24, h - 56, startIndex, columns * rows, oreVariantStacks.size());
        }
        drawEditorButton(x + w - 64, y + h - 24, 48, 16, "Back", mouseX, mouseY, "Back");
    }

    private void handleAmountEditorClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1) {
            return;
        }
        int x = canvasLeft + (canvasWidth - 160) / 2;
        int y = canvasTop + (canvasHeight - 70) / 2;
        if (mouseButton == 1 && fieldIsHovered(amountField, mouseX, mouseY)) {
            amountField.setText("0");
            return;
        }
        if (mouseButton != 0) {
            return;
        }
        amountField.mouseClicked(mouseX, mouseY, mouseButton);
        if (inRect(mouseX, mouseY, x + 34, y + 50, 38, 16)) {
            confirmAmountEditor();
        } else if (inRect(mouseX, mouseY, x + 88, y + 50, 48, 16)) {
            closeAmountEditor();
        }
    }

    private void handleCandidateSelectorClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 2 && openHoveredCandidateVariantSelector(mouseX, mouseY)) {
            return;
        }
        if (mouseButton != 0) {
            return;
        }
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        if (inRect(mouseX, mouseY, x + w - 64, y + h - 24, 48, 16)) {
            closeCandidateSelector();
            return;
        }
        int rowY = y + 24;
        int startIndex = Math.min(candidateScroll, Math.max(0, recipeCandidates.size() - getCandidateRows()));
        int endIndex = Math.min(recipeCandidates.size(), startIndex + getCandidateRows());
        for (int i = startIndex; i < endIndex; i++) {
            int itemY = rowY + (i - startIndex) * CANDIDATE_ROW_HEIGHT;
            if (inRect(mouseX, mouseY, x + w - 34, itemY + 2, 22, 16)) {
                openCandidateInNei(recipeCandidates.get(i));
                return;
            }
            if (inRect(mouseX, mouseY, x + 8, itemY - 2, w - 50, CANDIDATE_ROW_HEIGHT - 3)) {
                applyRecipeCandidate(recipeCandidates.get(i));
                closeCandidateSelector();
                syncGraph();
                return;
            }
        }
    }

    private boolean openHoveredCandidateVariantSelector(int mouseX, int mouseY) {
        CandidateVariantHit hit = findCandidateVariantHit(mouseX, mouseY);
        if (hit == null) {
            return false;
        }
        oreVariantSelectorOpen = true;
        oreVariantReadOnly = true;
        oreVariantSlot = -1;
        oreVariantStacks = hit.variants;
        oreVariantScroll = Math.min(oreVariantScroll, Math.max(0, oreVariantStacks.size() - 1));
        return true;
    }

    private CandidateVariantHit findCandidateVariantHit(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int x = getCandidateListX();
        int y = getCandidateListY();
        int rowY = y + 24;
        int startIndex = Math.min(candidateScroll, Math.max(0, recipeCandidates.size() - getCandidateRows()));
        int endIndex = Math.min(recipeCandidates.size(), startIndex + getCandidateRows());
        for (int i = startIndex; i < endIndex; i++) {
            RecipeMatchCandidate candidate = recipeCandidates.get(i);
            int itemY = rowY + (i - startIndex) * CANDIDATE_ROW_HEIGHT;
            int slot = findCandidateInputSlotAt(candidate, x + 12, itemY, w - 88, mouseX, mouseY);
            if (slot >= 0 && slot < candidate.inputVariants.size()) {
                List<ItemStack> variants = candidate.inputVariants.get(slot);
                if (variants.size() > 1) {
                    return new CandidateVariantHit(variants);
                }
            }
        }
        return null;
    }

    private int findCandidateInputSlotAt(RecipeMatchCandidate candidate, int x, int y, int maxWidth, int mouseX,
        int mouseY) {
        int cursor = x;
        int maxRight = x + maxWidth;
        ItemStack[] inputs = recipeConsumableInputs(candidate.recipe);
        for (int i = 0; i < inputs.length; i++) {
            if (i > 0) {
                cursor += 10;
            }
            if (cursor + 16 > maxRight) {
                return -1;
            }
            if (inRect(mouseX, mouseY, cursor, y, 16, 16)) {
                return i;
            }
            cursor += 18;
        }
        return -1;
    }

    private void handleOreVariantSelectorClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        if (inRect(mouseX, mouseY, x + w - 64, y + h - 24, 48, 16)) {
            closeOreVariantSelector();
            return;
        }
        int hoveredVariant = getHoveredOreVariantIndex(mouseX, mouseY);
        if (hoveredVariant >= 0) {
            selectOreVariant(hoveredVariant);
        }
    }

    private int getHoveredOreVariantIndex(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        int columns = Math.max(1, (w - 28) / 18);
        int rows = Math.max(1, (h - 54) / 18);
        int startIndex = Math.min(oreVariantScroll, Math.max(0, oreVariantStacks.size() - columns * rows));
        int index = startIndex;
        for (int row = 0; row < rows && index < oreVariantStacks.size(); row++) {
            for (int col = 0; col < columns && index < oreVariantStacks.size(); col++) {
                int sx = x + 10 + col * 18;
                int sy = y + 24 + row * 18;
                if (inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                    return index;
                }
                index++;
            }
        }
        return -1;
    }

    private void drawScrollBar(int x, int y, int h, int startIndex, int endIndex, int total) {
        if (total <= getCandidateRows()) {
            return;
        }
        drawRect(x, y, x + 4, y + h, 0xFF253142);
        int trackHeight = Math.max(12, (int) Math.round((double) h * getCandidateRows() / total));
        int maxOffset = Math.max(1, total - getCandidateRows());
        int offset = Math.min(candidateScroll, maxOffset);
        int knobY = y + (int) Math.round((double) (h - trackHeight) * offset / maxOffset);
        drawRect(x, knobY, x + 4, knobY + trackHeight, 0xFF6E88A4);
    }

    private void drawVariantScrollBar(int x, int y, int h, int startIndex, int visible, int total) {
        if (total <= visible) {
            return;
        }
        drawRect(x, y, x + 4, y + h, 0xFF253142);
        int trackHeight = Math.max(12, (int) Math.round((double) h * visible / total));
        int maxOffset = Math.max(1, total - visible);
        int offset = Math.min(startIndex, maxOffset);
        int knobY = y + (int) Math.round((double) (h - trackHeight) * offset / maxOffset);
        drawRect(x, knobY, x + 4, knobY + trackHeight, 0xFF6E88A4);
    }

    private void openOreVariantSelector(int slot) {
        if (editingNode == null || slot < 0 || slot >= ProcessNode.INPUT_SLOTS) {
            return;
        }
        oreVariantSelectorOpen = true;
        oreVariantSlot = slot;
        oreVariantStacks = editingNode.getInputVariants(slot);
        if (oreVariantStacks.isEmpty()) {
            oreVariantStacks = new ArrayList<>();
            closeOreVariantSelector();
            return;
        }
        oreVariantScroll = Math.min(oreVariantScroll, Math.max(0, oreVariantStacks.size() - 1));
    }

    private void selectOreVariant(int variantIndex) {
        if (oreVariantReadOnly || editingNode == null
            || oreVariantSlot < 0
            || oreVariantSlot >= ProcessNode.INPUT_SLOTS) {
            return;
        }
        List<ItemStack> variants = editingNode.getInputVariants(oreVariantSlot);
        if (variantIndex < 0 || variantIndex >= variants.size()) {
            return;
        }
        ItemStack selected = variants.get(variantIndex);
        editingNode.inputHandler.setStackInSlot(oreVariantSlot, selected.copy());
        editingNode.setInputVariantSelectedIndex(oreVariantSlot, variantIndex);
        invalidateNodeCheck();
        applyEditorFields();
        syncGraph();
        closeOreVariantSelector();
    }

    private void closeOreVariantSelector() {
        oreVariantSelectorOpen = false;
        oreVariantReadOnly = false;
        oreVariantSlot = -1;
        oreVariantScroll = 0;
        oreVariantStacks = new ArrayList<>();
    }

    private boolean isInsideOreVariantSelector(int mouseX, int mouseY) {
        int x = getCandidateListX();
        int y = getCandidateListY();
        return inRect(mouseX, mouseY, x, y, getCandidateListWidth(), getCandidateListHeight());
    }

    private void onOreVariantSelectorWheel(int direction) {
        int maxColumns = Math.max(1, (getCandidateListWidth() - 28) / 18);
        int maxRows = Math.max(1, (getCandidateListHeight() - 54) / 18);
        int maxScroll = Math.max(0, oreVariantStacks.size() - maxColumns * maxRows);
        if (maxScroll == 0) {
            oreVariantScroll = 0;
            return;
        }
        oreVariantScroll = Math.max(0, Math.min(maxScroll, oreVariantScroll - direction));
    }

    private void drawCandidateIconFormula(RecipeMatchCandidate candidate, int x, int y, int maxWidth, int mouseX,
        int mouseY) {
        int cursor = x;
        int maxRight = x + maxWidth;
        cursor = drawCandidateStackList(recipeIngredientStacks(candidate), cursor, y, maxRight, mouseX, mouseY);
        cursor = drawCandidateSymbol("=", cursor, y + 5, x + maxWidth);
        drawCandidateStackList(recipeOutputStacks(candidate.recipe), cursor, y, maxRight, mouseX, mouseY);
    }

    private int drawCandidateStackList(List<ItemStack> stacks, int x, int y, int maxRight, int mouseX, int mouseY) {
        int cursor = x;
        boolean first = true;
        for (ItemStack stack : stacks) {
            if (stack == null) {
                continue;
            }
            if (!first) {
                cursor = drawCandidateSymbol("+", cursor, y + 5, maxRight);
            }
            if (cursor + 16 > maxRight) {
                fontRendererObj.drawString("...", cursor, y + 5, 0xFFE8EEF5);
                return maxRight;
            }
            drawStack(stack, cursor, y);
            if (inRect(mouseX, mouseY, cursor, y, 16, 16)) {
                queueTooltip(stack.getDisplayName(), mouseX, mouseY);
            }
            cursor += 18;
            first = false;
        }
        if (first) {
            fontRendererObj.drawString("?", cursor, y + 5, 0xFFE8EEF5);
            cursor += 10;
        }
        return cursor;
    }

    private int drawCandidateSymbol(String symbol, int x, int y, int maxRight) {
        if (x + 8 > maxRight) {
            return x;
        }
        fontRendererObj.drawString(symbol, x, y, 0xFFE8EEF5);
        return x + 10;
    }

    private List<ItemStack> recipeIngredientStacks(RecipeMatchCandidate candidate) {
        List<ItemStack> stacks = new ArrayList<>();
        ItemStack[] inputs = recipeConsumableInputs(candidate.recipe);
        for (int i = 0; i < inputs.length; i++) {
            List<ItemStack> variants = i < candidate.inputVariants.size() ? candidate.inputVariants.get(i)
                : java.util.Collections.emptyList();
            ItemStack stack = variants.size() > 1 ? renderedCandidateVariant(variants, i) : inputs[i];
            addCopy(stacks, stack);
        }
        for (FluidStack fluid : safeFluids(candidate.recipe.mFluidInputs)) {
            addFluidDisplay(stacks, fluid);
        }
        for (ItemStack stack : recipeNonConsumableStacks(candidate.recipe)) {
            addCopy(stacks, stack);
        }
        return stacks;
    }

    private ItemStack renderedCandidateVariant(List<ItemStack> variants, int slot) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        int index = GuiScreen.isShiftKeyDown() ? 0
            : (int) ((System.currentTimeMillis() / 1000L + slot) % variants.size());
        return variants.get(index);
    }

    private List<ItemStack> recipeOutputStacks(GTRecipe recipe) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : safeItems(recipe.mOutputs)) {
            addCopy(stacks, stack);
        }
        for (FluidStack fluid : safeFluids(recipe.mFluidOutputs)) {
            addFluidDisplay(stacks, fluid);
        }
        return stacks;
    }

    private void openCandidateInNei(RecipeMatchCandidate candidate) {
        closeCandidateSelectorAfterApply = true;
        GTNEIDefaultHandler handler = new GTNEIDefaultHandler(candidate.recipeMap.getDefaultRecipeCategory());
        handler.loadCraftingRecipes(candidate.recipeMap.unlocalizedName);
        for (int i = 0; i < handler.arecipes.size(); i++) {
            Object cachedRecipe = handler.arecipes.get(i);
            if (cachedRecipe instanceof GTNEIDefaultHandler.CachedDefaultRecipe cached
                && cached.mRecipe == candidate.recipe) {
                Recipe recipe = Recipe.of(handler, i);
                if (recipe != null && recipe.getRecipeId() != null
                    && recipe.getRecipeId()
                        .getResult() != null) {
                    GuiCraftingRecipe.openRecipeGui(
                        "recipeId",
                        recipe.getRecipeId()
                            .getResult(),
                        recipe.getRecipeId());
                    return;
                }
            }
        }
        GuiCraftingRecipe.openRecipeGui(candidate.recipeMap.unlocalizedName);
    }

    private void confirmAmountEditor() {
        if (amountEditHandler != null && amountEditSlot >= 0) {
            ItemStack stack = amountEditHandler.getStackInSlot(amountEditSlot);
            long amount = Math.max(0L, parseLong(amountField.getText(), getEditableAmount(stack)));
            if (stack != null) {
                amountEditHandler.setStackInSlot(amountEditSlot, withDisplayAmount(stack, amount));
                invalidateNodeCheck();
                syncGraph();
            }
        }
        closeAmountEditor();
    }

    private void closeAmountEditor() {
        amountEditorOpen = false;
        amountEditHandler = null;
        amountEditSlot = -1;
        amountField = null;
    }

    private void closeCandidateSelector() {
        candidateSelectorOpen = false;
        recipeCandidates = new ArrayList<>();
        candidateScroll = 0;
        closeCandidateSelectorAfterApply = false;
    }

    private int getCandidateListWidth() {
        return Math.min(CANDIDATE_LIST_WIDTH, canvasWidth - 18);
    }

    private int getCandidateListHeight() {
        return Math.min(CANDIDATE_LIST_HEIGHT, canvasHeight - 18);
    }

    private int getCandidateListX() {
        return canvasLeft + (canvasWidth - getCandidateListWidth()) / 2;
    }

    private int getCandidateListY() {
        return canvasTop + (canvasHeight - getCandidateListHeight()) / 2;
    }

    private int getCandidateRows() {
        return Math.max(1, (getCandidateListHeight() - 54) / CANDIDATE_ROW_HEIGHT);
    }

    private void checkCurrentRecipe() {
        if (editingNode == null) {
            return;
        }
        List<RecipeMatchCandidate> candidates = findRecipeCandidates(editingNode);
        List<RecipeMatchCandidate> exactCandidates = new ArrayList<>();
        for (RecipeMatchCandidate candidate : candidates) {
            if (recipeMatchesNodeExactly(editingNode, candidate.recipe)) {
                exactCandidates.add(candidate);
            }
        }
        if (exactCandidates.size() == 1) {
            applyRecipeCandidate(exactCandidates.get(0));
            return;
        }
        if (candidates.size() == 1) {
            applyRecipeCandidate(candidates.get(0));
            return;
        }
        if (candidates.isEmpty()) {
            candidates = findRecipeCandidates(editingNode, true);
        }
        editingNode.lastRecipeCheckPassed = false;
        if (candidates.size() > 1) {
            recipeCandidates = candidates;
            candidateSelectorOpen = true;
            candidateScroll = Math.min(candidateScroll, Math.max(0, recipeCandidates.size() - getCandidateRows()));
        } else {
            closeCandidateSelector();
        }
    }

    private List<RecipeMatchCandidate> findRecipeCandidates(ProcessNode node) {
        return findRecipeCandidates(node, false);
    }

    private List<RecipeMatchCandidate> findRecipeCandidates(ProcessNode node, boolean ignoreHints) {
        Map<String, RecipeMatchCandidate> candidates = new LinkedHashMap<>();
        for (RecipeMap<?> recipeMap : RecipeMap.ALL_RECIPE_MAPS.values()) {
            if (!isRecipeMapAllowed(node, recipeMap)) {
                continue;
            }
            for (GTRecipe recipe : recipeMap.getAllRecipes()) {
                if (recipe == null || recipe.mHidden || recipe.mFakeRecipe || !recipe.mEnabled) {
                    continue;
                }
                if (!ignoreHints && !candidateMatchesHints(node, recipe)) {
                    continue;
                }
                String groupKey = recipeGroupKey(recipeMap, recipe);
                RecipeMatchCandidate existing = candidates.get(groupKey);
                if (existing == null) {
                    candidates.put(
                        groupKey,
                        new RecipeMatchCandidate(
                            recipeMap,
                            recipe,
                            StatCollector.translateToLocal(recipeMap.unlocalizedName),
                            recipeRatioMatches(node, recipe),
                            MTESuperIntegratedFactory.buildRecipeFingerprint(recipe),
                            buildRecipeInputVariantPreview(recipe, java.util.Collections.emptyList(), false)));
                } else {
                    mergeCandidateInputVariants(existing.inputVariants, recipe);
                }
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private String recipeGroupKey(RecipeMap<?> recipeMap, GTRecipe recipe) {
        return recipeMap.unlocalizedName + "|i="
            + groupedItemKey(recipeConsumableInputs(recipe))
            + "|fi="
            + groupedFluidKey(recipe.mFluidInputs)
            + "|o="
            + groupedItemKey(recipe.mOutputs)
            + "|fo="
            + groupedFluidKey(recipe.mFluidOutputs)
            + "|nc="
            + groupedItemKey(recipeNonConsumableStacks(recipe).toArray(new ItemStack[0]))
            + "|t="
            + recipe.mDuration
            + "|e="
            + recipe.mEUt;
    }

    private String groupedItemKey(ItemStack[] stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : safeItems(stacks)) {
            if (stack == null) {
                continue;
            }
            parts.add(itemGroupKey(stack) + "@" + Math.max(1, stack.stackSize));
        }
        parts.sort(String::compareTo);
        return parts.toString();
    }

    private String itemGroupKey(ItemStack stack) {
        gregtech.api.objects.ItemData association = GTOreDictUnificator.getAssociation(stack);
        if (association != null && association.mPrefix != null
            && association.mMaterial != null
            && association.mMaterial.mMaterial != null) {
            return "gt:" + association.mPrefix.name() + ":" + association.mMaterial.mMaterial.name();
        }
        int[] oreIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(stack);
        if (oreIds != null && oreIds.length > 0) {
            List<String> oreNames = new ArrayList<>();
            for (int oreId : oreIds) {
                oreNames.add(net.minecraftforge.oredict.OreDictionary.getOreName(oreId));
            }
            oreNames.sort(String::compareTo);
            return "ore:" + oreNames;
        }
        return ProcessNode.stackFingerprint(stack);
    }

    private boolean sameOreDictionaryGroup(ItemStack left, ItemStack right) {
        if (left == null || right == null) {
            return false;
        }
        if (GTOreDictUnificator.isInputStackEqual(left, right) || GTOreDictUnificator.isInputStackEqual(right, left)
            || GTUtility.areStacksEqual(left, right, true)) {
            return true;
        }
        int[] leftIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(left);
        int[] rightIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(right);
        if (leftIds == null || rightIds == null || leftIds.length == 0 || rightIds.length == 0) {
            return false;
        }
        for (int leftId : leftIds) {
            for (int rightId : rightIds) {
                if (leftId == rightId) {
                    return true;
                }
            }
        }
        return false;
    }

    private String groupedFluidKey(FluidStack[] fluids) {
        List<String> parts = new ArrayList<>();
        for (FluidStack fluid : safeFluids(fluids)) {
            if (fluid != null && fluid.getFluid() != null) {
                parts.add(
                    fluid.getFluid()
                        .getName() + "@"
                        + Math.max(1, fluid.amount));
            }
        }
        parts.sort(String::compareTo);
        return parts.toString();
    }

    private boolean isRecipeMapAllowed(ProcessNode node, RecipeMap<?> recipeMap) {
        if (node.recipeMapName != null && !node.recipeMapName.isEmpty()) {
            return node.recipeMapName.equals(recipeMap.unlocalizedName);
        }
        return node.recipeHandlerName == null || node.recipeHandlerName.isEmpty()
            || node.recipeHandlerName.equals(StatCollector.translateToLocal(recipeMap.unlocalizedName))
            || node.recipeHandlerName.equals(recipeMap.unlocalizedName);
    }

    private boolean candidateMatchesHints(ProcessNode node, GTRecipe recipe) {
        return hasAnyProvidedHints(node) && (!hasProvidedInputs(node)
            || providedItemsAreContainedInRecipe(gatherInputItemStacks(node), recipeConsumableInputs(recipe), false))
            && (!hasProvidedInputFluids(node)
                || providedFluidsAreContainedInRecipe(gatherFluidStacks(node.inputHandler), recipe.mFluidInputs, false))
            && (!hasProvidedOutputs(node)
                || providedItemsAreContainedInRecipe(gatherItemStacks(node.outputHandler), recipe.mOutputs, false))
            && (!hasProvidedOutputFluids(node) || providedFluidsAreContainedInRecipe(
                gatherFluidStacks(node.outputHandler),
                recipe.mFluidOutputs,
                false))
            && (!hasProvidedNonConsumables(node)
                || handlerContentsAreSubsetOfRecipeNonConsumables(node.nonConsumableHandler, recipe, false));
    }

    private boolean recipeRatioMatches(ProcessNode node, GTRecipe recipe) {
        QuantityRatio ratio = new QuantityRatio();
        return hasAnyProvidedHints(node)
            && (!hasProvidedInputs(node)
                || addItemRatios(ratio, gatherInputItemStacks(node), recipeConsumableInputs(recipe)))
            && (!hasProvidedInputFluids(node)
                || addFluidRatios(ratio, gatherFluidStacks(node.inputHandler), recipe.mFluidInputs))
            && (!hasProvidedOutputs(node)
                || addItemRatios(ratio, gatherItemStacks(node.outputHandler), recipe.mOutputs))
            && (!hasProvidedOutputFluids(node)
                || addFluidRatios(ratio, gatherFluidStacks(node.outputHandler), recipe.mFluidOutputs))
            && (!hasProvidedNonConsumables(node)
                || handlerContentsAreSubsetOfRecipeNonConsumables(node.nonConsumableHandler, recipe, true))
            && ratio.hasRatio();
    }

    private boolean recipeMatchesNodeExactly(ProcessNode node, GTRecipe recipe) {
        return (!hasProvidedInputs(node)
            || itemStacksMatchRecipe(gatherInputItemStacks(node), recipeConsumableInputs(recipe), true))
            && (!hasProvidedInputFluids(node) || normalizedFluidCounts(gatherFluidStacks(node.inputHandler))
                .equals(normalizedFluidCounts(recipe.mFluidInputs)))
            && (!hasProvidedOutputs(node)
                || itemStacksMatchRecipe(gatherItemStacks(node.outputHandler), recipe.mOutputs, false))
            && (!hasProvidedOutputFluids(node) || normalizedFluidCounts(gatherFluidStacks(node.outputHandler))
                .equals(normalizedFluidCounts(recipe.mFluidOutputs)))
            && (!hasProvidedNonConsumables(node) || itemStacksMatchRecipe(
                gatherItemStacks(node.nonConsumableHandler),
                recipeNonConsumableStacks(recipe).toArray(new ItemStack[0]),
                true));
    }

    private boolean hasAnyProvidedHints(ProcessNode node) {
        return hasProvidedInputs(node) || hasProvidedInputFluids(node)
            || hasProvidedOutputs(node)
            || hasProvidedOutputFluids(node)
            || hasProvidedNonConsumables(node);
    }

    private boolean hasProvidedInputs(ProcessNode node) {
        return gatherInputItemStacks(node).length > 0;
    }

    private boolean hasProvidedInputFluids(ProcessNode node) {
        return gatherFluidStacks(node.inputHandler).length > 0;
    }

    private boolean hasProvidedOutputs(ProcessNode node) {
        return gatherItemStacks(node.outputHandler).length > 0;
    }

    private boolean hasProvidedOutputFluids(ProcessNode node) {
        return gatherFluidStacks(node.outputHandler).length > 0;
    }

    private boolean hasProvidedNonConsumables(ProcessNode node) {
        return gatherItemStacks(node.nonConsumableHandler).length > 0;
    }

    private Map<String, Long> normalizedItemCounts(ItemStack[] stacks) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ItemStack stack : safeItems(stacks)) {
            if (stack == null) {
                continue;
            }
            String key = itemGroupKey(stack);
            counts.put(key, counts.getOrDefault(key, 0L) + Math.max(1L, stack.stackSize));
        }
        return counts;
    }

    private boolean itemStacksMatchRecipe(ItemStack[] provided, ItemStack[] recipeItems, boolean allowInputVariants) {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : safeItems(recipeItems)) {
            if (stack != null) {
                remaining.add(stack);
            }
        }
        int providedCount = 0;
        for (ItemStack stack : safeItems(provided)) {
            if (stack == null) {
                continue;
            }
            providedCount++;
            int matchedIndex = findMatchingRecipeItemIndex(remaining, stack, allowInputVariants, true);
            if (matchedIndex < 0) {
                return false;
            }
            remaining.remove(matchedIndex);
        }
        return providedCount == safeItems(recipeItems).length && remaining.isEmpty();
    }

    private int findMatchingRecipeItemIndex(List<ItemStack> recipeItems, ItemStack provided, boolean allowInputVariants,
        boolean exactAmount) {
        for (int i = 0; i < recipeItems.size(); i++) {
            ItemStack recipeStack = recipeItems.get(i);
            if (recipeStack == null) {
                continue;
            }
            if (exactAmount && recipeStack.stackSize != provided.stackSize) {
                continue;
            }
            if (allowInputVariants ? recipeInputAcceptsProvided(recipeStack, provided)
                : GTUtility.areStacksEqual(recipeStack, provided, true)) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Long> normalizedFluidCounts(FluidStack[] fluids) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FluidStack fluid : safeFluids(fluids)) {
            if (fluid == null || fluid.getFluid() == null) {
                continue;
            }
            String key = "fluid:" + fluid.getFluid()
                .getName();
            counts.put(key, counts.getOrDefault(key, 0L) + Math.max(1L, fluid.amount));
        }
        return counts;
    }

    private boolean handlerContentsAreSubsetOfRecipeNonConsumables(ItemStackHandler handler, GTRecipe recipe,
        boolean exactAmount) {
        List<ItemStack> specialStacks = recipeNonConsumableStacks(recipe);
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack != null && !containsItem(specialStacks.toArray(new ItemStack[0]), stack, exactAmount)) {
                return false;
            }
        }
        return true;
    }

    private List<ItemStack> recipeNonConsumableStacks(GTRecipe recipe) {
        List<ItemStack> stacks = new ArrayList<>();
        if (recipe.mSpecialItems instanceof ItemStack stack) {
            addCopy(stacks, stack);
        } else if (recipe.mSpecialItems instanceof ItemStack[]array) {
            for (ItemStack stack : array) {
                addCopy(stacks, stack);
            }
        }
        for (ItemStack stack : safeItems(recipe.mInputs)) {
            if (stack != null && stack.stackSize <= 0) {
                ItemStack copy = stack.copy();
                copy.stackSize = Math.max(1, copy.stackSize);
                addCopy(stacks, copy);
            }
        }
        return stacks;
    }

    private void applyRecipeCandidate(RecipeMatchCandidate candidate) {
        if (editingNode == null) {
            return;
        }
        applyRecipeCandidateState(candidate);
        fillHandlerFromRecipe(editingNode.outputHandler, candidate.recipe.mOutputs, candidate.recipe.mFluidOutputs);
        fillHandlerFromNonConsumables(editingNode.nonConsumableHandler, candidate.recipe);
        editingNode.recipeMapName = candidate.recipeMap.unlocalizedName;
        editingNode.recipeHandlerName = StatCollector.translateToLocal(candidate.recipeMap.unlocalizedName);
        applyRecipeTiming(candidate);
        editingNode.recipeFingerprint = editingNode.buildRecipeFingerprint();
        editingNode.lastRecipeCheckPassed = true;
        fillEmptyNameFromRecipeOutput();
        rebuildEditorFields(editingNode);
        setEditorFieldsEnabled(!editingNode.locked);
    }

    private void applyRecipeCandidateState(RecipeMatchCandidate candidate) {
        if (editingNode == null || candidate == null) {
            return;
        }
        ItemStack[] currentInputs = new ItemStack[editingNode.inputHandler.getSlots()];
        for (int i = 0; i < currentInputs.length; i++) {
            currentInputs[i] = editingNode.inputHandler.getStackInSlot(i);
        }
        List<List<ItemStack>> preferredVariantsBySlot = new ArrayList<>();
        ItemStack[] recipeInputs = recipeConsumableInputs(candidate.recipe);
        for (int slot = 0; slot < recipeInputs.length; slot++) {
            List<ItemStack> variants = editingNode.hasInputVariants(slot) ? editingNode.getInputVariants(slot)
                : slot < candidate.inputVariants.size() ? candidate.inputVariants.get(slot)
                    : java.util.Collections.emptyList();
            preferredVariantsBySlot.add(variants);
        }
        fillHandlerFromRecipe(
            editingNode.inputHandler,
            selectDisplayedInputStacks(recipeInputs, currentInputs, preferredVariantsBySlot).toArray(new ItemStack[0]),
            candidate.recipe.mFluidInputs);
        clearAllInputVariants(editingNode);
        applyRecipeInputVariants(candidate.recipe, preferredVariantsBySlot, currentInputs);
    }

    private List<ItemStack> selectDisplayedInputStacks(ItemStack[] recipeInputs, ItemStack[] currentInputs,
        List<List<ItemStack>> preferredVariantsBySlot) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < recipeInputs.length; slot++) {
            ItemStack recipeStack = recipeInputs[slot] == null ? null : recipeInputs[slot].copy();
            List<ItemStack> variants = slot < preferredVariantsBySlot.size() ? preferredVariantsBySlot.get(slot)
                : java.util.Collections.emptyList();
            ItemStack currentStack = slot < currentInputs.length ? currentInputs[slot] : null;
            if (!variants.isEmpty()) {
                int selectedIndex = selectedVariantIndex(variants, currentStack);
                ItemStack selected = variants.get(selectedIndex);
                stacks.add(withDisplayAmount(selected, getEditableAmount(recipeStack)));
            } else if (recipeStack != null) {
                stacks.add(recipeStack);
            }
        }
        return stacks;
    }

    private void markRecipeCandidateMatched(RecipeMatchCandidate candidate) {
        applyRecipeCandidate(candidate);
    }

    private void applyRecipeTiming(RecipeMatchCandidate candidate) {
        editingNode.durationTicks = Math.max(0, candidate.recipe.mDuration);
        editingNode.euPerTick = Math.max(0L, candidate.recipe.mEUt);
        editingNode.recipeMapName = candidate.recipeMap.unlocalizedName;
        editingNode.recipeHandlerName = StatCollector.translateToLocal(candidate.recipeMap.unlocalizedName);
    }

    private ItemStack[] gatherItemStacks(ItemStackHandler handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack != null && GTUtility.getFluidFromDisplayStack(stack) == null) {
                stacks.add(stack);
            }
        }
        return stacks.toArray(new ItemStack[0]);
    }

    private ItemStack[] gatherInputItemStacks(ProcessNode node) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < node.inputHandler.getSlots(); i++) {
            ItemStack stack = node.inputHandler.getStackInSlot(i);
            if (stack == null || GTUtility.getFluidFromDisplayStack(stack) != null) {
                continue;
            }
            stacks.add(stack);
        }
        return stacks.toArray(new ItemStack[0]);
    }

    private FluidStack[] gatherFluidStacks(ItemStackHandler handler) {
        List<FluidStack> stacks = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
            if (fluid != null) {
                stacks.add(fluid);
            }
        }
        return stacks.toArray(new FluidStack[0]);
    }

    private boolean providedItemsAreContainedInRecipe(ItemStack[] provided, ItemStack[] recipeItems,
        boolean exactAmount) {
        provided = provided == null ? new ItemStack[0] : provided;
        recipeItems = recipeItems == null ? new ItemStack[0] : recipeItems;
        for (ItemStack stack : provided) {
            if (stack != null && !containsItem(recipeItems, stack, exactAmount)) {
                return false;
            }
        }
        return true;
    }

    private boolean providedFluidsAreContainedInRecipe(FluidStack[] provided, FluidStack[] recipeFluids,
        boolean exactAmount) {
        provided = provided == null ? new FluidStack[0] : provided;
        recipeFluids = recipeFluids == null ? new FluidStack[0] : recipeFluids;
        for (FluidStack stack : provided) {
            if (stack != null && stack.getFluid() != null && !containsFluid(recipeFluids, stack, exactAmount)) {
                return false;
            }
        }
        return true;
    }

    private boolean addItemRatios(QuantityRatio ratio, ItemStack[] provided, ItemStack[] recipeItems) {
        provided = provided == null ? new ItemStack[0] : provided;
        recipeItems = recipeItems == null ? new ItemStack[0] : recipeItems;
        for (ItemStack stack : provided) {
            if (stack == null) {
                continue;
            }
            ItemStack matched = findMatchingItem(recipeItems, stack);
            if (matched == null || matched.stackSize <= 0 || stack.stackSize < 0) {
                return false;
            }
            if (!ratio.add(stack.stackSize, matched.stackSize)) {
                return false;
            }
        }
        return true;
    }

    private boolean addFluidRatios(QuantityRatio ratio, FluidStack[] provided, FluidStack[] recipeFluids) {
        provided = provided == null ? new FluidStack[0] : provided;
        recipeFluids = recipeFluids == null ? new FluidStack[0] : recipeFluids;
        for (FluidStack stack : provided) {
            if (stack == null || stack.getFluid() == null) {
                continue;
            }
            FluidStack matched = findMatchingFluid(recipeFluids, stack);
            if (matched == null || matched.amount <= 0 || stack.amount < 0) {
                return false;
            }
            if (!ratio.add(stack.amount, matched.amount)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsItem(ItemStack[] haystack, ItemStack needle, boolean exactAmount) {
        if (needle == null) {
            return true;
        }
        ItemStack matched = findMatchingItem(haystack, needle);
        if (matched == null) {
            return false;
        }
        return !exactAmount || matched.stackSize == needle.stackSize;
    }

    private boolean containsItem(List<ItemStack> haystack, ItemStack needle, boolean exactAmount) {
        return containsItem(haystack.toArray(new ItemStack[0]), needle, exactAmount);
    }

    private boolean containsFluid(FluidStack[] haystack, FluidStack needle, boolean exactAmount) {
        if (needle == null || needle.getFluid() == null) {
            return true;
        }
        FluidStack matched = findMatchingFluid(haystack, needle);
        if (matched == null) {
            return false;
        }
        return !exactAmount || matched.amount == needle.amount;
    }

    private boolean containsFluid(List<FluidStack> haystack, FluidStack needle, boolean exactAmount) {
        return containsFluid(haystack.toArray(new FluidStack[0]), needle, exactAmount);
    }

    private ItemStack findMatchingItem(ItemStack[] haystack, ItemStack needle) {
        haystack = haystack == null ? new ItemStack[0] : haystack;
        for (ItemStack stack : haystack) {
            if (stack != null && recipeInputAcceptsProvided(stack, needle)) {
                return stack;
            }
        }
        return null;
    }

    private FluidStack findMatchingFluid(FluidStack[] haystack, FluidStack needle) {
        haystack = haystack == null ? new FluidStack[0] : haystack;
        for (FluidStack stack : haystack) {
            if (stack != null && stack.getFluid() != null && stack.isFluidEqual(needle)) {
                return stack;
            }
        }
        return null;
    }

    private void fillHandlerFromRecipe(ItemStackHandler handler, ItemStack[] items, FluidStack[] fluids) {
        items = items == null ? new ItemStack[0] : items;
        fluids = fluids == null ? new FluidStack[0] : fluids;
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, null);
        }
        int slot = 0;
        for (ItemStack stack : items) {
            if (stack != null && slot < handler.getSlots()) {
                handler.setStackInSlot(slot++, stack.copy());
            }
        }
        for (FluidStack fluid : fluids) {
            if (fluid != null && fluid.getFluid() != null && slot < handler.getSlots()) {
                handler.setStackInSlot(slot++, GTUtility.getFluidDisplayStack(fluid, true));
            }
        }
    }

    private void clearAllInputVariants(ProcessNode node) {
        for (int i = 0; i < ProcessNode.INPUT_SLOTS; i++) {
            node.clearInputVariants(i);
        }
    }

    private void applyRecipeInputVariants(GTRecipe recipe, List<List<ItemStack>> preferredVariantsBySlot,
        ItemStack[] currentInputs) {
        if (editingNode == null || recipe == null) {
            return;
        }
        List<List<ItemStack>> variantsBySlot = buildRecipeInputVariantPreview(recipe, preferredVariantsBySlot, false);
        int slot = 0;
        for (ItemStack stack : recipeConsumableInputs(recipe)) {
            if (slot >= editingNode.inputHandler.getSlots()) {
                return;
            }
            List<ItemStack> variants = slot < variantsBySlot.size() ? variantsBySlot.get(slot)
                : java.util.Collections.emptyList();
            if (variants.size() > 1) {
                ItemStack currentStack = slot < currentInputs.length ? currentInputs[slot] : stack;
                editingNode.setInputVariants(slot, variants, selectedVariantIndex(variants, currentStack));
            }
            slot++;
        }
    }

    private List<List<ItemStack>> buildRecipeInputVariantPreview(GTRecipe recipe,
        List<List<ItemStack>> preferredVariantsBySlot, boolean expandFallbackVariants) {
        List<List<ItemStack>> variantsBySlot = new ArrayList<>();
        int slot = 0;
        for (ItemStack stack : recipeConsumableInputs(recipe)) {
            List<ItemStack> preferred = slot < preferredVariantsBySlot.size() ? preferredVariantsBySlot.get(slot)
                : java.util.Collections.emptyList();
            if (!preferred.isEmpty()) {
                variantsBySlot.add(preferred);
            } else if (expandFallbackVariants) {
                variantsBySlot.add(oreVariantsFor(stack));
            } else {
                List<ItemStack> singleton = new ArrayList<>();
                addUniqueVariant(singleton, stack);
                variantsBySlot.add(singleton);
            }
            slot++;
        }
        return variantsBySlot;
    }

    private void mergeCandidateInputVariants(List<List<ItemStack>> variantsBySlot, GTRecipe recipe) {
        ItemStack[] inputs = recipeConsumableInputs(recipe);
        for (int i = 0; i < inputs.length; i++) {
            while (variantsBySlot.size() <= i) {
                variantsBySlot.add(new ArrayList<>());
            }
            addUniqueVariant(variantsBySlot.get(i), inputs[i]);
        }
    }

    private List<ItemStack> oreVariantsFor(ItemStack stack) {
        List<ItemStack> variants = new ArrayList<>();
        if (stack == null || GTUtility.getFluidFromDisplayStack(stack) != null) {
            return variants;
        }
        addUniqueVariant(variants, withDisplayAmount(stack, Math.max(1, stack.stackSize)));
        ItemStack unificated = GTOreDictUnificator.get(false, stack);
        addUniqueVariant(variants, withDisplayAmount(unificated, Math.max(1, stack.stackSize)));
        List<ItemStack> nonUnified = GTOreDictUnificator.getNonUnifiedStacks(stack);
        for (ItemStack alt : nonUnified) {
            addUniqueVariant(variants, withDisplayAmount(alt, Math.max(1, stack.stackSize)));
        }
        if (hasValidGtAssociation(stack)) {
            return variants;
        }
        int[] oreIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(stack);
        if (oreIds != null) {
            for (int oreId : oreIds) {
                String oreName = net.minecraftforge.oredict.OreDictionary.getOreName(oreId);
                for (ItemStack oreStack : net.minecraftforge.oredict.OreDictionary.getOres(oreName)) {
                    if (oreStack != null) {
                        addUniqueVariant(variants, withDisplayAmount(oreStack, Math.max(1, stack.stackSize)));
                    }
                }
            }
        }
        return variants;
    }

    private boolean recipeInputAcceptsProvided(ItemStack recipeStack, ItemStack provided) {
        if (recipeStack == null || provided == null) {
            return false;
        }
        if (GTUtility.areStacksEqual(recipeStack, provided, true)
            || GTOreDictUnificator.isInputStackEqual(provided, recipeStack)) {
            return true;
        }
        if (hasValidGtAssociation(recipeStack)) {
            return false;
        }
        return sameOreDictionaryGroup(recipeStack, provided);
    }

    private boolean hasValidGtAssociation(ItemStack stack) {
        gregtech.api.objects.ItemData association = GTOreDictUnificator.getAssociation(stack);
        return association != null && association.mPrefix != null
            && association.mMaterial != null
            && association.mMaterial.mMaterial != null;
    }

    private void addUniqueVariant(List<ItemStack> variants, ItemStack stack) {
        if (stack == null) {
            return;
        }
        for (ItemStack existing : variants) {
            if (GTUtility.areStacksEqual(existing, stack, true)) {
                return;
            }
        }
        variants.add(stack.copy());
    }

    private int selectedVariantIndex(List<ItemStack> variants, ItemStack selected) {
        for (int i = 0; i < variants.size(); i++) {
            if (GTUtility.areStacksEqual(variants.get(i), selected, true)) {
                return i;
            }
        }
        return 0;
    }

    private void fillHandlerFromNonConsumables(ItemStackHandler handler, GTRecipe recipe) {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, null);
        }
        int slot = 0;
        for (ItemStack stack : recipeNonConsumableStacks(recipe)) {
            if (stack != null && slot < handler.getSlots()) {
                handler.setStackInSlot(slot++, stack.copy());
            }
        }
    }

    private ItemStack[] recipeConsumableInputs(GTRecipe recipe) {
        List<ItemStack> inputs = new ArrayList<>();
        for (ItemStack stack : safeItems(recipe.mInputs)) {
            if (stack.stackSize > 0) {
                inputs.add(stack);
            }
        }
        return inputs.toArray(new ItemStack[0]);
    }

    private ItemStack[] safeItems(ItemStack[] items) {
        return items == null ? new ItemStack[0] : items;
    }

    private FluidStack[] safeFluids(FluidStack[] fluids) {
        return fluids == null ? new FluidStack[0] : fluids;
    }

    private void addCopy(List<ItemStack> stacks, ItemStack stack) {
        if (stack != null) {
            stacks.add(stack.copy());
        }
    }

    private void addFluidDisplay(List<ItemStack> stacks, FluidStack fluid) {
        if (fluid != null && fluid.getFluid() != null) {
            ItemStack display = GTUtility.getFluidDisplayStack(fluid, true);
            if (display != null) {
                stacks.add(display);
            }
        }
    }

    private static final class RecipeMatchCandidate {

        private final RecipeMap<?> recipeMap;
        private final GTRecipe recipe;
        private final String displayName;
        private final boolean ratioMatches;
        private final String recipeFingerprint;
        private final List<List<ItemStack>> inputVariants;

        private RecipeMatchCandidate(RecipeMap<?> recipeMap, GTRecipe recipe, String displayName, boolean ratioMatches,
            String recipeFingerprint, List<List<ItemStack>> inputVariants) {
            this.recipeMap = recipeMap;
            this.recipe = recipe;
            this.displayName = displayName;
            this.ratioMatches = ratioMatches;
            this.recipeFingerprint = recipeFingerprint;
            this.inputVariants = inputVariants;
        }
    }

    private static final class CandidateVariantHit {

        private final List<ItemStack> variants;

        private CandidateVariantHit(List<ItemStack> variants) {
            this.variants = variants;
        }
    }

    private static final class QuantityRatio {

        private long numerator = -1L;
        private long denominator = 1L;

        private boolean add(long providedAmount, long recipeAmount) {
            if (recipeAmount <= 0L || providedAmount < 0L) {
                return false;
            }
            long gcd = gcd(providedAmount, recipeAmount);
            long nextNumerator = providedAmount / gcd;
            long nextDenominator = recipeAmount / gcd;
            if (numerator < 0L) {
                numerator = nextNumerator;
                denominator = nextDenominator;
                return true;
            }
            return numerator == nextNumerator && denominator == nextDenominator;
        }

        private boolean hasRatio() {
            return numerator >= 0L;
        }

        private static long gcd(long a, long b) {
            a = Math.abs(a);
            b = Math.abs(b);
            while (b != 0L) {
                long next = a % b;
                a = b;
                b = next;
            }
            return Math.max(1L, a);
        }
    }

    private void addNode() {
        ProcessNode node = graph.addDraftNode(screenToWorldX(canvasLeft + 36), screenToWorldY(canvasTop + 36));
        node.name = tr("superfactory.machine.super_integrated_factory.process.new_node") + " " + node.id;
        graph.selectedNodeId = node.id;
        syncGraph();
    }

    private void deleteNode(int nodeId) {
        graph.deleteNode(nodeId);
        closeDialog();
        closeNodeEditor();
        syncGraph();
    }

    private void unlockNode(ProcessNode node) {
        if (node == null) {
            return;
        }
        node.locked = false;
        node.lastRecipeCheckPassed = false;
        node.endNode = false;
        graph.edges.removeIf(edge -> edge.fromNodeId == node.id || edge.toNodeId == node.id);
    }

    private void openNodeEditor(ProcessNode node) {
        editingNode = node;
        editorOpen = true;
        rebuildEditorFields(node);
        setEditorFieldsEnabled(!node.locked);
    }

    public void refreshEditorFromNode(int nodeId) {
        ProcessNode node = graph.findNode(nodeId);
        if (node == null) {
            return;
        }
        graph.selectedNodeId = nodeId;
        if (editorOpen && editingNode != null && editingNode.id == nodeId) {
            rebuildEditorFields(node);
            setEditorFieldsEnabled(!node.locked);
            if (closeCandidateSelectorAfterApply) {
                closeCandidateSelector();
            }
        }
    }

    public void closeCandidateSelectorAfterExternalApply(int nodeId) {
        refreshEditorFromNode(nodeId);
        if (editingNode != null && editingNode.id == nodeId) {
            closeCandidateSelector();
        }
    }

    private void rebuildEditorFields(ProcessNode node) {
        int x = getEditorX();
        int y = getEditorY();
        nameField = createTextField(x + 42, y + 24, 118, node.name);
        durationField = createTextField(x + 28, y + 48, 50, String.valueOf(node.durationTicks));
        euField = createTextField(x + 106, y + 48, 50, String.valueOf(node.euPerTick));
        overclockField = createTextField(x + 28, y + 72, 50, String.valueOf(node.overclockCount));
        parallelField = createTextField(x + 106, y + 72, 50, String.valueOf(node.parallelLimit));
    }

    private void closeNodeEditor() {
        setEditorTextFieldFocus(null);
        editorOpen = false;
        editingNode = null;
        nameField = null;
        durationField = null;
        euField = null;
        overclockField = null;
        parallelField = null;
    }

    private void closeNodeEditorWithDefaultName() {
        if (editingNode != null) {
            applyEditorFields();
            if (editingNode.name == null || editingNode.name.trim()
                .isEmpty()) {
                editingNode.name = defaultNodeName(editingNode);
                syncGraph();
            }
        }
        closeNodeEditor();
    }

    private void closeNodeEditorWithoutChangingName() {
        if (editingNode != null) {
            applyEditorFields();
        }
        closeNodeEditor();
    }

    private GuiTextField createTextField(int x, int y, int width, String text) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, y, width, 14);
        field.setText(text == null ? "" : text);
        field.setMaxStringLength(64);
        return field;
    }

    private boolean handleEditorKey(char typedChar, int keyCode) {
        if (candidateSelectorOpen) {
            if (keyCode == 1) {
                closeCandidateSelector();
            }
            return true;
        }
        if (amountEditorOpen) {
            if (keyCode == 1) {
                closeAmountEditor();
                return true;
            }
            if (keyCode == 28 || keyCode == 156) {
                confirmAmountEditor();
                return true;
            }
            return typeUnsignedInteger(amountField, typedChar, keyCode);
        }
        if (editingNode != null && editingNode.locked) {
            return false;
        }
        boolean handled = false;
        handled |= nameField.textboxKeyTyped(typedChar, keyCode);
        handled |= typeUnsignedInteger(durationField, typedChar, keyCode);
        handled |= typeUnsignedInteger(euField, typedChar, keyCode);
        handled |= typeUnsignedInteger(overclockField, typedChar, keyCode);
        handled |= typeUnsignedInteger(parallelField, typedChar, keyCode);
        if (handled) {
            applyEditorFields();
            syncGraph();
        }
        return handled;
    }

    private void setEditorFieldsEnabled(boolean enabled) {
        nameField.setEnabled(enabled);
        durationField.setEnabled(enabled);
        euField.setEnabled(enabled);
        overclockField.setEnabled(enabled);
        parallelField.setEnabled(enabled);
    }

    private void applyEditorFields() {
        if (editingNode == null) {
            return;
        }
        String oldName = editingNode.name == null ? "" : editingNode.name;
        int oldDuration = editingNode.durationTicks;
        long oldEu = editingNode.euPerTick;
        int oldOverclock = editingNode.overclockCount;
        int oldParallel = editingNode.parallelLimit;
        editingNode.name = nameField.getText();
        editingNode.durationTicks = Math.max(0, parseInt(durationField.getText(), editingNode.durationTicks));
        editingNode.euPerTick = Math.max(0L, parseLong(euField.getText(), editingNode.euPerTick));
        editingNode.overclockCount = Math.max(0, parseInt(overclockField.getText(), editingNode.overclockCount));
        editingNode.parallelLimit = Math.max(1, parseInt(parallelField.getText(), editingNode.parallelLimit));
        if ((oldDuration != editingNode.durationTicks || oldEu != editingNode.euPerTick) && !editingNode.locked) {
            editingNode.lastRecipeCheckPassed = false;
        }
        if (!oldName.equals(editingNode.name) || oldOverclock != editingNode.overclockCount
            || oldParallel != editingNode.parallelLimit) {
            syncGraph();
        }
    }

    private void normalizeFocusedNumericFields() {
        if (durationField != null && durationField.isFocused()
            && durationField.getText()
                .trim()
                .isEmpty()) {
            durationField.setText("0");
        }
        if (euField != null && euField.isFocused()
            && euField.getText()
                .trim()
                .isEmpty()) {
            euField.setText("0");
        }
        if (overclockField != null && overclockField.isFocused()
            && overclockField.getText()
                .trim()
                .isEmpty()) {
            overclockField.setText("0");
        }
        if (parallelField != null && parallelField.isFocused()
            && parallelField.getText()
                .trim()
                .isEmpty()) {
            parallelField.setText("1");
        }
    }

    private boolean typeUnsignedInteger(GuiTextField field, char typedChar, int keyCode) {
        if (!field.isFocused()) {
            return false;
        }
        if (Character.isDigit(typedChar) || keyCode == 14
            || keyCode == 199
            || keyCode == 203
            || keyCode == 205
            || keyCode == 207
            || keyCode == 211) {
            return field.textboxKeyTyped(typedChar, keyCode);
        }
        return false;
    }

    private void resetView() {
        graph.zoom = 1.0D;
        graph.viewportX = canvasWidth / 2;
        graph.viewportY = canvasHeight / 2;
        syncGraph();
    }

    private int getEditorWidth() {
        return Math.min(EDITOR_WIDTH, canvasWidth - 18);
    }

    private int getEditorHeight() {
        return Math.min(EDITOR_HEIGHT, canvasHeight - 18);
    }

    private int getEditorX() {
        return canvasLeft + (canvasWidth - getEditorWidth()) / 2;
    }

    private int getEditorY() {
        return canvasTop + (canvasHeight - getEditorHeight()) / 2;
    }

    private void closeGui() {
        MTESuperIntegratedFactory.setClientEditingFactory(null);
        mc.displayGuiScreen(null);
    }

    private void syncGraph() {
        if (factory.getBaseMetaTileEntity() != null) {
            NBTTagCompound graphTag = graph.writeToNBT();
            NetworkLoader.INSTANCE
                .sendToServer(new MessageUpdateProcessGraph(factory.getBaseMetaTileEntity(), graphTag));
        }
    }

    private ProcessNode findNodeAt(int mouseX, int mouseY) {
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            ProcessNode node = graph.nodes.get(i);
            int x = worldToScreenX(node.x);
            int y = worldToScreenY(node.y);
            if (inRect(mouseX, mouseY, x, y, scale(ProcessNode.WIDTH), scale(ProcessNode.HEIGHT))) {
                return node;
            }
        }
        return null;
    }

    private boolean tryStartEdgeDrag(int mouseX, int mouseY) {
        ProcessNode node = findConnectorNodeAt(mouseX, mouseY, true);
        if (node == null) {
            return false;
        }
        draggingEdge = true;
        edgeDragFromNodeId = node.id;
        edgeDragStartX = worldToScreenX(node.x + ProcessNode.WIDTH);
        edgeDragStartY = worldToScreenY(node.y + ProcessNode.HEIGHT / 2);
        graph.selectedNodeId = node.id;
        return true;
    }

    private void finishEdgeDrag(int mouseX, int mouseY) {
        ProcessNode from = graph.findNode(edgeDragFromNodeId);
        ProcessNode to = findConnectorNodeAt(mouseX, mouseY, false);
        draggingEdge = false;
        edgeDragFromNodeId = 0;
        if (from != null && to != null
            && from.id != to.id
            && canConnectNodes(from, to)
            && !edgeExists(from.id, to.id)) {
            graph.edges.add(new ProcessEdge(graph.nextEdgeId++, from.id, to.id));
            if (from.endNode) {
                from.endNode = false;
            }
            syncGraph();
        }
    }

    private ProcessNode findConnectorNodeAt(int mouseX, int mouseY, boolean outputConnector) {
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            ProcessNode node = graph.nodes.get(i);
            if (!node.locked) {
                continue;
            }
            int x = outputConnector ? worldToScreenX(node.x + ProcessNode.WIDTH) : worldToScreenX(node.x);
            int y = worldToScreenY(node.y + ProcessNode.HEIGHT / 2);
            int radius = Math.max(5, scale(5));
            if (inRect(mouseX, mouseY, x - radius, y - radius, radius * 2, radius * 2)) {
                return node;
            }
        }
        return null;
    }

    private boolean edgeExists(int fromNodeId, int toNodeId) {
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId == fromNodeId && edge.toNodeId == toNodeId) {
                return true;
            }
        }
        return false;
    }

    private boolean canConnectNodes(ProcessNode from, ProcessNode to) {
        for (int outputSlot = 0; outputSlot < from.outputHandler.getSlots(); outputSlot++) {
            ItemStack output = from.outputHandler.getStackInSlot(outputSlot);
            if (output == null) {
                continue;
            }
            FluidStack outputFluid = GTUtility.getFluidFromDisplayStack(output);
            for (int inputSlot = 0; inputSlot < to.inputHandler.getSlots(); inputSlot++) {
                ItemStack input = to.inputHandler.getStackInSlot(inputSlot);
                if (input == null) {
                    continue;
                }
                FluidStack inputFluid = GTUtility.getFluidFromDisplayStack(input);
                if (outputFluid != null || inputFluid != null) {
                    if (outputFluid != null && inputFluid != null && outputFluid.isFluidEqual(inputFluid)) {
                        return true;
                    }
                    continue;
                }
                if (recipeInputAcceptsProvided(input, output) || GTUtility.areStacksEqual(input, output, true)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int worldToScreenX(int worldX) {
        return canvasLeft + graph.viewportX + (int) Math.round(worldX * graph.zoom);
    }

    private int worldToScreenY(int worldY) {
        return canvasTop + graph.viewportY + (int) Math.round(worldY * graph.zoom);
    }

    private int screenToWorldX(int screenX) {
        return (int) Math.round((screenX - canvasLeft - graph.viewportX) / graph.zoom);
    }

    private int screenToWorldY(int screenY) {
        return (int) Math.round((screenY - canvasTop - graph.viewportY) / graph.zoom);
    }

    private int scale(int value) {
        return Math.max(1, (int) Math.round(value * graph.zoom));
    }

    @Override
    protected int getGridStep() {
        return scale(GRID_SIZE);
    }

    @Override
    protected int getGridOriginX() {
        return canvasLeft + graph.viewportX;
    }

    @Override
    protected int getGridOriginY() {
        return canvasTop + graph.viewportY;
    }

    private int snap(int value) {
        return Math.round((float) value / GRID_SIZE) * GRID_SIZE;
    }

    private boolean inRect(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void updatePanning(int mouseX, int mouseY) {
        if (!panning) {
            return;
        }
        if (editorOpen || !Mouse.isButtonDown(2)) {
            panning = false;
            syncGraph();
            return;
        }
        int dx = mouseX - lastPanMouseX;
        int dy = mouseY - lastPanMouseY;
        if (dx != 0 || dy != 0) {
            graph.viewportX += dx;
            graph.viewportY += dy;
            lastPanMouseX = mouseX;
            lastPanMouseY = mouseY;
        }
    }

    private int floorToGrid(int value) {
        return Math.floorDiv(value, GRID_SIZE) * GRID_SIZE;
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        drawRect(x, y, x + w, y + 1, color);
        drawRect(x, y + h - 1, x + w, y + h, color);
        drawRect(x, y, x + 1, y + h, color);
        drawRect(x + w - 1, y, x + w, y + h, color);
    }

    private void drawNodeConnector(int x, int y) {
        drawRect(x, y + 1, x + 8, y + 7, 0xFFFFFFFF);
        drawRect(x + 1, y, x + 7, y + 8, 0xFFFFFFFF);
        drawRect(x + 2, y + 2, x + 6, y + 6, 0xFF243040);
    }

    private void drawLine(int x1, int y1, int x2, int y2, int color) {
        float alpha = ((color >>> 24) & 0xFF) / 255.0F;
        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x1 + 0.5F, y1 + 0.5F);
        GL11.glVertex2f(x2 + 0.5F, y2 + 0.5F);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    private String trimToWidth(String text, int maxWidth) {
        return fontRendererObj.trimStringToWidth(text == null ? "" : text, maxWidth);
    }

    private String safeNodeName(ProcessNode node) {
        return node.name == null || node.name.isEmpty() ? defaultNodeName(node) : node.name;
    }

    private String defaultNodeName(ProcessNode node) {
        return "Node " + node.id;
    }

    private void fillEmptyNameFromRecipeOutput() {
        if (editingNode == null || editingNode.name != null && !editingNode.name.trim()
            .isEmpty()) {
            return;
        }
        ItemStack firstOutput = firstNonEmptyStack(editingNode.outputHandler);
        editingNode.name = firstOutput == null ? defaultNodeName(editingNode) : firstOutput.getDisplayName();
    }

    private ItemStack firstNonEmptyStack(ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void invalidateNodeCheck() {
        if (editingNode != null && !editingNode.locked) {
            editingNode.lastRecipeCheckPassed = false;
        }
    }

    private boolean fieldIsHovered(GuiTextField field, int mouseX, int mouseY) {
        return field != null && mouseX >= field.xPosition
            && mouseX < field.xPosition + field.width
            && mouseY >= field.yPosition
            && mouseY < field.yPosition + field.height;
    }

    private long getDisplayAmount(ItemStack stack) {
        if (stack == null) {
            return 0L;
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null) {
            return Math.max(0, fluid.amount);
        }
        return Math.max(0, stack.stackSize);
    }

    private long getEditableAmount(ItemStack stack) {
        return Math.max(1L, getDisplayAmount(stack));
    }

    private ItemStack withDisplayAmount(ItemStack stack, long amount) {
        ItemStack copy = stack.copy();
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(copy);
        if (fluid != null && fluid.getFluid() != null) {
            fluid.amount = (int) Math.min(Integer.MAX_VALUE, amount);
            ItemStack display = GTUtility.getFluidDisplayStack(fluid, true);
            return display == null ? copy : display;
        }
        copy.stackSize = (int) Math.min(Integer.MAX_VALUE, amount);
        return copy;
    }

    private static final class SlotHit {

        private final ItemStackHandler handler;
        private final int slot;

        private SlotHit(ItemStackHandler handler, int slot) {
            this.handler = handler;
            this.slot = slot;
        }
    }

    private String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private boolean isInsideCandidateSelector(int mouseX, int mouseY) {
        int x = getCandidateListX();
        int y = getCandidateListY();
        return inRect(mouseX, mouseY, x, y, getCandidateListWidth(), getCandidateListHeight());
    }

    private void onCandidateSelectorWheel(int direction) {
        int maxScroll = Math.max(0, recipeCandidates.size() - getCandidateRows());
        if (maxScroll == 0) {
            candidateScroll = 0;
            return;
        }
        candidateScroll = Math.max(0, Math.min(maxScroll, candidateScroll - direction));
    }
}
