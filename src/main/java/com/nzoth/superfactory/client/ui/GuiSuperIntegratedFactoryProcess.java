package com.nzoth.superfactory.client.ui;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizons.modularui.api.forge.ItemStackHandler;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;
import com.nzoth.superfactory.common.network.MessageSubmitProcessRequirements;
import com.nzoth.superfactory.common.network.MessageUpdateProcessGraph;
import com.nzoth.superfactory.common.network.NetworkLoader;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.ProcessRequirements;

import codechicken.nei.ItemPanels;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.Recipe;
import cpw.mods.fml.common.Loader;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.nei.GTNEIDefaultHandler;

public final class GuiSuperIntegratedFactoryProcess extends AbstractProcessCanvasScreen {

    private static final int EDITOR_WIDTH = 428;
    private static final int EDITOR_HEIGHT = 238;
    private static final int EDITOR_BUTTON_Y_OFFSET = 212;
    private static final int COMPACT_EDITOR_WIDTH = 340;
    private static final int COMPACT_EDITOR_HEIGHT = 286;
    private static final int PATTERN_VISIBLE_SLOTS = 18;
    private static final int PATTERN_MAX_PAGES = 10;
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
    private boolean candidateSelectorFillOutputs;
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
    private String statusMessage = "";
    private int statusMessageColor = 0xFFFF7777;
    private long statusMessageUntil;
    private boolean exportDialogOpen;
    private GuiTextField exportNameField;
    private boolean importSelectorOpen;
    private List<File> importGraphFiles = new ArrayList<>();
    private int importScroll;

    public GuiSuperIntegratedFactoryProcess(MTESuperIntegratedFactory factory) {
        this.factory = factory;
        this.graph = factory.getProcessGraph();
    }

    @Override
    public void initGui() {
        MTESuperIntegratedFactory.setClientEditingFactory(factory);
        factory.setActiveProcessGui(this);
        super.initGui();
        if (editorOpen && editingNode != null) {
            rebuildEditorFields(editingNode);
            setEditorFieldsEnabled(!editingNode.locked);
        }
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
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 48,
            24,
            20,
            "B",
            tr("superfactory.machine.super_integrated_factory.process.balance"),
            this::balanceProcess);
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 72,
            24,
            20,
            "1",
            tr("superfactory.machine.super_integrated_factory.process.reset_parallel"),
            this::confirmResetParallel);
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 96,
            24,
            20,
            "I",
            tr("superfactory.machine.super_integrated_factory.process.reset_node_state"),
            this::confirmResetNodeState);
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 120,
            24,
            20,
            "S",
            tr("superfactory.machine.super_integrated_factory.process.submit"),
            this::submitProcess);
        addCanvasButton(
            toolbarLeft,
            toolbarTop + 144,
            24,
            20,
            "X",
            tr("superfactory.machine.super_integrated_factory.process.close"),
            this::closeGui);
        addCanvasButton(
            canvasLeft + canvasWidth - 30,
            canvasTop + canvasHeight - 48,
            26,
            18,
            "EX",
            tr("superfactory.machine.super_integrated_factory.process.export"),
            this::openExportDialog);
        addCanvasButton(
            canvasLeft + canvasWidth - 30,
            canvasTop + canvasHeight - 26,
            26,
            18,
            "IM",
            tr("superfactory.machine.super_integrated_factory.process.import"),
            this::openImportSelector);
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
        boolean consumed = false;
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
            consumed = true;
        }
        if (exportDialogOpen) {
            drawExportDialog(mouseX, mouseY);
            consumed = true;
        }
        if (importSelectorOpen) {
            drawImportSelector(mouseX, mouseY);
            consumed = true;
        }
        return consumed;
    }

    @Override
    public void drawCanvasAfterNei(int mouseX, int mouseY, float partialTicks) {
        super.drawCanvasAfterNei(mouseX, mouseY, partialTicks);
        drawNeiDraggedStackAboveCanvas(mouseX, mouseY);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
    }

    @Override
    protected boolean shouldHandleCanvasWheel(int mouseX, int mouseY) {
        return !editorOpen && !candidateSelectorOpen && !oreVariantSelectorOpen;
    }

    @Override
    protected boolean shouldHandleCanvasClickBeforeButtons(int mouseX, int mouseY, int mouseButton) {
        return exportDialogOpen || importSelectorOpen
            || candidateSelectorOpen
            || amountEditorOpen
            || oreVariantSelectorOpen
            || editorOpen && isInsideNodeEditor(mouseX, mouseY);
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
        if (exportDialogOpen) {
            handleExportDialogClick(mouseX, mouseY, mouseButton);
            return;
        }
        if (importSelectorOpen) {
            handleImportSelectorClick(mouseX, mouseY, mouseButton);
            return;
        }
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
                openConfirmDialog(
                    tr("superfactory.machine.super_integrated_factory.node_delete.title"),
                    tr("superfactory.machine.super_integrated_factory.node_delete.body") + " "
                        + safeNodeName(node)
                        + "?",
                    () -> deleteNode(node.id));
            }
        }
    }

    @Override
    protected void onCanvasMouseReleased(int mouseX, int mouseY, int state) {
        if (editorOpen && !candidateSelectorOpen
            && !oreVariantSelectorOpen
            && !amountEditorOpen
            && state == 0
            && handleNeiDraggedStackDrop(mouseX, mouseY, state)) {
            return;
        }
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
        if (exportDialogOpen) {
            if (keyCode == 1) {
                closeExportDialog();
                return;
            }
            if (keyCode == 28 || keyCode == 156) {
                confirmExportGraph();
                return;
            }
            exportNameField.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        if (importSelectorOpen) {
            if (keyCode == 1) {
                closeImportSelector();
                return;
            }
            super.keyTyped(typedChar, keyCode);
            return;
        }
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
        if ((candidateSelectorOpen || oreVariantSelectorOpen || importSelectorOpen) && wheel != 0) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (candidateSelectorOpen && isInsideCandidateSelector(mouseX, mouseY)) {
                onCandidateSelectorWheel(wheel > 0 ? 1 : -1);
            }
            if (oreVariantSelectorOpen && isInsideOreVariantSelector(mouseX, mouseY)) {
                onOreVariantSelectorWheel(wheel > 0 ? 1 : -1);
            }
            if (importSelectorOpen && isInsideCandidateSelector(mouseX, mouseY)) {
                onImportSelectorWheel(wheel > 0 ? 1 : -1);
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
        if (!statusMessage.isEmpty() && System.currentTimeMillis() < statusMessageUntil) {
            fontRendererObj.drawString(
                trimToWidth(statusMessage, canvasWidth - 8),
                canvasLeft + 4,
                canvasTop + 6,
                statusMessageColor);
        }
    }

    private void drawEdges() {
        for (ProcessEdge edge : graph.edges) {
            ProcessNode from = graph.findNode(edge.fromNodeId);
            ProcessNode to = graph.findNode(edge.toNodeId);
            if (from != null && to != null) {
                if (from.id == to.id) {
                    drawSelfLoop(from, 0xFF71C7EC);
                } else {
                    drawFlowLine(
                        worldToScreenX(from.x + ProcessNode.WIDTH),
                        worldToScreenY(from.y + ProcessNode.HEIGHT / 2),
                        worldToScreenX(to.x),
                        worldToScreenY(to.y + ProcessNode.HEIGHT / 2),
                        0xFF71C7EC);
                }
            }
        }
        if (draggingEdge) {
            drawFlowLine(edgeDragStartX, edgeDragStartY, lastMouseX, lastMouseY, 0xFFFFFFFF);
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
            float textScale = getNodeTextScale();
            drawScaledNodeString(safeNodeName(node), x + scale(4), y + scale(3), w - scale(8), 0xFFFFFFFF, textScale);
            String state = node.endNode ? tr("superfactory.machine.super_integrated_factory.node_state.end")
                : node.locked ? tr("superfactory.machine.super_integrated_factory.node_state.locked")
                    : tr("superfactory.machine.super_integrated_factory.node_state.draft");
            drawScaledNodeString(
                state,
                x + scale(4),
                y + Math.max(scale(18), scale(22)),
                w - scale(8),
                border,
                textScale);
            if (node.locked) {
                drawNodeConnector(x, y + h / 2);
                drawNodeConnector(x + w, y + h / 2);
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

        int leftPanelWidth = getEditorLeftPanelWidth();
        int rightX = getEditorPatternX();
        int panelBottom = getEditorButtonY() - 8;
        drawRect(x + 8, y + 24, x + 8 + leftPanelWidth, panelBottom, 0xFF273445);
        drawRect(rightX - 8, y + 24, x + w - 8, panelBottom, 0xFF273445);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.node_editor.name"),
            x + 10,
            y + 28,
            0xFFE8EEF5);
        nameField.drawTextBox();
        boolean allowEditorTooltips = !amountEditorOpen;
        drawHoverLabel(
            "t",
            x + 10,
            y + 52,
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.duration_tooltip")
                : "");
        durationField.drawTextBox();
        drawHoverLabel(
            "EU/t",
            x + 84,
            y + 52,
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.eut_tooltip") : "");
        euField.drawTextBox();
        drawHoverLabel(
            "OC",
            x + 10,
            y + 76,
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.oc_tooltip") : "");
        overclockField.drawTextBox();
        drawHoverLabel(
            "P",
            x + 84,
            y + 76,
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.parallel_tooltip")
                : "");
        parallelField.drawTextBox();
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.node_editor.state") + ": "
                + (editingNode.locked ? tr("superfactory.machine.super_integrated_factory.node_state.locked")
                    : tr("superfactory.machine.super_integrated_factory.node_state.draft")),
            x + 10,
            y + 101,
            0xFFE8EEF5);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.node_editor.recipe") + ": "
                + trimToWidth(editingNode.recipeHandlerName, 92),
            x + 10,
            y + 115,
            0xFFBFD0E2);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.node_editor.check") + ": "
                + (editingNode.lastRecipeCheckPassed
                    ? tr("superfactory.machine.super_integrated_factory.node_editor.check_passed")
                    : tr("superfactory.machine.super_integrated_factory.node_editor.check_unchecked")),
            x + 10,
            y + 129,
            editingNode.lastRecipeCheckPassed ? 0xFF75D17C : 0xFFFF7777);
        if (editingNode.estimatedOutputLine != null && !editingNode.estimatedOutputLine.isEmpty()) {
            String[] lines = editingNode.estimatedOutputLine.split("\n", -1);
            for (int i = 0; i < lines.length && i < 6; i++) {
                fontRendererObj.drawString(trimToWidth(lines[i], 148), x + 10, y + 143 + i * 10, 0xFFB8F6C0);
            }
        }

        drawPatternPreview(
            rightX,
            y + 38,
            tr("superfactory.machine.super_integrated_factory.node_editor.inputs"),
            editingNode.inputHandler,
            9,
            PATTERN_VISIBLE_SLOTS,
            getClampedInputPage(),
            mouseX,
            mouseY,
            true,
            false);
        drawPatternPreview(
            rightX,
            y + 94,
            tr("superfactory.machine.super_integrated_factory.node_editor.outputs"),
            editingNode.outputHandler,
            9,
            PATTERN_VISIBLE_SLOTS,
            getClampedOutputPage(),
            mouseX,
            mouseY,
            false,
            true);
        drawPatternPreview(
            rightX,
            y + 150,
            tr("superfactory.machine.super_integrated_factory.node_editor.non_consumables"),
            editingNode.nonConsumableHandler,
            9,
            9,
            mouseX,
            mouseY,
            false,
            false);

        int buttonY = getEditorButtonY();
        drawEditorButton(
            x + 10,
            buttonY,
            48,
            16,
            editingNode.endNode ? tr("superfactory.machine.super_integrated_factory.node_state.end") + "*"
                : tr("superfactory.machine.super_integrated_factory.node_state.end"),
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.end_node") : "");
        drawEditorButton(
            x + 68,
            buttonY,
            52,
            16,
            editingNode.locked ? tr("superfactory.machine.super_integrated_factory.node_editor.unlock_button")
                : tr("superfactory.machine.super_integrated_factory.node_editor.check_button"),
            mouseX,
            mouseY,
            allowEditorTooltips
                ? editingNode.locked ? tr("superfactory.machine.super_integrated_factory.node_editor.unlock")
                    : tr("superfactory.machine.super_integrated_factory.node_editor.check_recipe")
                : "");
        drawEditorButton(
            x + 132,
            buttonY,
            52,
            16,
            tr("superfactory.machine.super_integrated_factory.node_editor.complete_button"),
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.complete_recipe") : "");
        drawEditorButton(
            x + 194,
            buttonY,
            42,
            16,
            "OK",
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.confirm") : "");
        drawEditorButton(
            x + 246,
            buttonY,
            44,
            16,
            tr("superfactory.machine.super_integrated_factory.node_editor.close"),
            mouseX,
            mouseY,
            allowEditorTooltips ? tr("superfactory.machine.super_integrated_factory.node_editor.close") : "");
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
        if (handlePatternPageButtonClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (handlePatternSlotClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (mouseButton != 0) {
            return;
        }
        int x = getEditorX();
        int buttonY = getEditorButtonY();
        if (inRect(mouseX, mouseY, x + 10, buttonY, 48, 16)) {
            editingNode.endNode = !editingNode.endNode;
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 68, buttonY, 52, 16)) {
            if (editingNode.locked) {
                unlockNode(editingNode);
                setEditorFieldsEnabled(true);
            } else {
                applyEditorFields();
                checkCurrentRecipe(false);
            }
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 132, buttonY, 52, 16)) {
            applyEditorFields();
            checkCurrentRecipe(true);
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 194, buttonY, 42, 16)) {
            applyEditorFields();
            checkCurrentRecipe(false);
            if (editingNode.lastRecipeCheckPassed) {
                editingNode.locked = true;
                closeNodeEditorWithoutChangingName();
            }
            syncGraph();
        } else if (inRect(mouseX, mouseY, x + 246, buttonY, 44, 16)) {
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
            field.setText(numeric ? formatWholeNumber(emptyNumericValue) : "");
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
        drawPatternPreview(x, y, label, handler, columns, visibleSlots, mouseX, mouseY, false, false);
    }

    private void drawPatternPreview(int x, int y, String label, ItemStackHandler handler, int columns, int visibleSlots,
        int mouseX, int mouseY, boolean inputs) {
        drawPatternPreview(x, y, label, handler, columns, visibleSlots, mouseX, mouseY, inputs, false);
    }

    private void drawPatternPreview(int x, int y, String label, ItemStackHandler handler, int columns, int visibleSlots,
        int mouseX, int mouseY, boolean inputs, boolean outputChances) {
        drawPatternPreview(x, y, label, handler, columns, visibleSlots, 0, mouseX, mouseY, inputs, outputChances);
    }

    private void drawPatternPreview(int x, int y, String label, ItemStackHandler handler, int columns, int visibleSlots,
        int page, int mouseX, int mouseY, boolean inputs, boolean outputChances) {
        int maxPage = getEditorMaxPage(handler, page);
        int clampedPage = Math.max(0, Math.min(page, maxPage));
        int startSlot = clampedPage * visibleSlots;
        fontRendererObj.drawString(label, x, y - 10, 0xFFE8EEF5);
        if (handler == editingNode.inputHandler || handler == editingNode.outputHandler) {
            int pageX = x + columns * 18 - 48;
            drawEditorButton(
                pageX,
                y - 14,
                10,
                10,
                "<",
                mouseX,
                mouseY,
                tr("superfactory.machine.super_integrated_factory.node_editor.prev_page"));
            fontRendererObj.drawString((clampedPage + 1) + "/" + (maxPage + 1), pageX + 14, y - 11, 0xFFE8EEF5);
            drawEditorButton(
                pageX + 36,
                y - 14,
                10,
                10,
                ">",
                mouseX,
                mouseY,
                tr("superfactory.machine.super_integrated_factory.node_editor.next_page"));
        }
        for (int i = 0; i < Math.min(visibleSlots, handler.getSlots()); i++) {
            int slot = startSlot + i;
            if (slot >= handler.getSlots()) {
                break;
            }
            int sx = x + i % columns * 18;
            int sy = y + i / columns * 18;
            drawRect(sx, sy, sx + 16, sy + 16, 0xFF324052);
            drawRect(sx + 1, sy + 1, sx + 15, sy + 15, 0xFF1A2430);
            ItemStack stack = inputs ? getRenderedInputStack(slot) : handler.getStackInSlot(slot);
            drawStack(stack, sx, sy);
            if (stack != null && outputChances && editingNode != null) {
                drawOutputChanceOverlay(editingNode.getOutputChance(slot), sx, sy);
            }
            if (inputs && editingNode.hasInputVariants(slot)
                && editingNode.getInputVariants(slot)
                    .size() > 1) {
                drawRect(sx + 11, sy + 1, sx + 15, sy + 5, 0xFFE6C15C);
            }
            if (!amountEditorOpen && stack != null && inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                queueTooltip(stack.getDisplayName(), mouseX, mouseY);
            }
        }
    }

    private void drawOutputChanceOverlay(int chance, int x, int y) {
        if (chance >= 10000) {
            return;
        }
        String text = chance <= 0 ? "0" : formatChancePercent(chance);
        GL11.glPushMatrix();
        GL11.glScalef(0.5F, 0.5F, 1.0F);
        fontRendererObj.drawStringWithShadow(text, (x + 1) * 2, (y + 1) * 2, 0xFFFFD86B);
        GL11.glPopMatrix();
    }

    private String formatChancePercent(int chance) {
        double percent = Math.max(0, Math.min(10000, chance)) / 100.0D;
        if (percent >= 10.0D || Math.abs(percent - Math.rint(percent)) < 0.001D) {
            return String.format(java.util.Locale.ROOT, "%.0f%%", percent);
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", percent);
    }

    private boolean handlePatternPageButtonClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || editingNode == null) {
            return false;
        }
        int rightX = getEditorPatternX();
        int inputY = getEditorY() + 38;
        int outputY = getEditorY() + 94;
        if (handleOnePatternPageButton(mouseX, mouseY, rightX, inputY, editingNode.inputHandler, true)) {
            return true;
        }
        return handleOnePatternPageButton(mouseX, mouseY, rightX, outputY, editingNode.outputHandler, false);
    }

    private boolean handleOnePatternPageButton(int mouseX, int mouseY, int x, int y, ItemStackHandler handler,
        boolean input) {
        int pageX = x + 9 * 18 - 48;
        int currentPage = input ? getClampedInputPage() : getClampedOutputPage();
        int maxPage = getEditorMaxPage(handler, currentPage);
        if (inRect(mouseX, mouseY, pageX, y - 14, 10, 10)) {
            if (input) {
                editingNode.inputPage = Math.max(0, currentPage - 1);
            } else {
                editingNode.outputPage = Math.max(0, currentPage - 1);
            }
            syncGraph();
            return true;
        }
        if (inRect(mouseX, mouseY, pageX + 36, y - 14, 10, 10)) {
            if (input) {
                editingNode.inputPage = Math.min(maxPage, currentPage + 1);
            } else {
                editingNode.outputPage = Math.min(maxPage, currentPage + 1);
            }
            syncGraph();
            return true;
        }
        return false;
    }

    private int getClampedInputPage() {
        if (editingNode == null) {
            return 0;
        }
        editingNode.inputPage = Math
            .max(0, Math.min(editingNode.inputPage, getEditorMaxPage(editingNode.inputHandler, editingNode.inputPage)));
        return editingNode.inputPage;
    }

    private int getClampedOutputPage() {
        if (editingNode == null) {
            return 0;
        }
        editingNode.outputPage = Math.max(
            0,
            Math.min(editingNode.outputPage, getEditorMaxPage(editingNode.outputHandler, editingNode.outputPage)));
        return editingNode.outputPage;
    }

    private int getEditorMaxPage(ItemStackHandler handler, int currentPage) {
        int maxSlotsPage = Math.min(PATTERN_MAX_PAGES, Math.max(1, handler.getSlots() / PATTERN_VISIBLE_SLOTS)) - 1;
        int usedPage = 0;
        for (int slot = 0; slot < handler.getSlots() && slot < PATTERN_MAX_PAGES * PATTERN_VISIBLE_SLOTS; slot++) {
            if (handler.getStackInSlot(slot) != null) {
                usedPage = Math.max(usedPage, slot / PATTERN_VISIBLE_SLOTS);
            }
        }
        int visibleMax = usedPage;
        if (isPatternPageFull(handler, usedPage) && usedPage < maxSlotsPage) {
            visibleMax = usedPage + 1;
        }
        return Math.max(0, Math.min(maxSlotsPage, Math.max(visibleMax, currentPage)));
    }

    private boolean isPatternPageFull(ItemStackHandler handler, int page) {
        int start = page * PATTERN_VISIBLE_SLOTS;
        for (int slot = start; slot < start + PATTERN_VISIBLE_SLOTS && slot < handler.getSlots(); slot++) {
            if (handler.getStackInSlot(slot) == null) {
                return false;
            }
        }
        return true;
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
        if (hit.handler == editingNode.inputHandler) {
            editingNode.clearInputVariants(hit.slot);
        } else if (hit.handler == editingNode.outputHandler) {
            editingNode.setOutputChance(hit.slot, 10000);
        }
        invalidateNodeCheck();
        applyEditorFields();
        syncGraph();
        return true;
    }

    public boolean canAcceptExternalRecipeFill() {
        return editorOpen && editingNode != null && !editingNode.locked;
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
        itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.renderEngine, stack, x, y, "");
        itemRender.zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawCompactStackAmount(stack, x, y);
    }

    private void drawCompactStackAmount(ItemStack stack, int x, int y) {
        boolean fluid = GTUtility.getFluidFromDisplayStack(stack) != null;
        long amount = getDisplayAmount(stack);
        if (amount <= 1L) {
            return;
        }
        String text = formatCompactAmount(amount);
        int textWidth = fontRendererObj.getStringWidth(text);
        float scale = textWidth > 14 ? Math.max(0.5F, 14.0F / textWidth) : 1.0F;
        if (fluid) {
            drawRect(x, y + 10, x + 16, y + 16, 0xAA000000);
        }
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1.0F);
        int scaledX = Math.round((fluid ? x + 1 : x + 17 - textWidth * scale) / scale);
        int scaledY = Math.round((y + 10) / scale);
        fontRendererObj.drawStringWithShadow(text, scaledX, scaledY, 0xFFFFFFFF);
        GL11.glPopMatrix();
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
        if (hit == null) {
            return hit != null;
        }
        ItemStack stack = hit.handler.getStackInSlot(hit.slot);
        if (stack == null) {
            if (!editingNode.locked && mouseButton == 1 && hit.handler == editingNode.outputHandler) {
                editingNode.setOutputChance(hit.slot, 10000);
                invalidateNodeCheck();
                syncGraph();
            }
            return true;
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
            } else if (hit.handler == editingNode.outputHandler) {
                editingNode.setOutputChance(hit.slot, 10000);
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
        int rightX = getEditorPatternX();
        SlotHit hit = findPatternSlot(
            mouseX,
            mouseY,
            rightX,
            y + 38,
            editingNode.inputHandler,
            9,
            PATTERN_VISIBLE_SLOTS,
            getClampedInputPage() * PATTERN_VISIBLE_SLOTS);
        if (hit != null) {
            return hit;
        }
        hit = findPatternSlot(
            mouseX,
            mouseY,
            rightX,
            y + 94,
            editingNode.outputHandler,
            9,
            PATTERN_VISIBLE_SLOTS,
            getClampedOutputPage() * PATTERN_VISIBLE_SLOTS);
        if (hit != null) {
            return hit;
        }
        return findPatternSlot(mouseX, mouseY, rightX, y + 150, editingNode.nonConsumableHandler, 9, 9, 0);
    }

    private SlotHit findPatternSlot(int mouseX, int mouseY, int x, int y, ItemStackHandler handler, int columns,
        int visibleSlots, int startSlot) {
        for (int i = 0; i < Math.min(visibleSlots, handler.getSlots()); i++) {
            int slot = startSlot + i;
            if (slot >= handler.getSlots()) {
                break;
            }
            int sx = x + i % columns * 18;
            int sy = y + i / columns * 18;
            if (inRect(mouseX, mouseY, sx, sy, 16, 16)) {
                return new SlotHit(handler, slot);
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
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.amount_editor.title"),
            x + 6,
            y + 5,
            0xFFFFFFFF);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.amount_editor.amount"),
            x + 10,
            y + 32,
            0xFFE8EEF5);
        amountField.drawTextBox();
        drawEditorButton(
            x + 34,
            y + 50,
            38,
            16,
            "OK",
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.amount_editor.confirm"));
        drawEditorButton(
            x + 88,
            y + 50,
            48,
            16,
            tr("superfactory.machine.super_integrated_factory.dialog.back"),
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.dialog.back"));
    }

    private void drawCandidateSelector(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.recipe_selector.title"),
            x + 6,
            y + 5,
            0xFFFFFFFF);
        int rowY = y + 24;
        if (recipeCandidates.isEmpty()) {
            fontRendererObj.drawString(
                tr("superfactory.machine.super_integrated_factory.recipe_selector.empty"),
                x + 10,
                rowY,
                0xFFFFB8B8);
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
                    formatWholeNumber(candidate.recipe.mDuration) + "t "
                        + formatWholeNumber(candidate.recipe.mEUt)
                        + "EU/t",
                    x + 12,
                    itemY + 16,
                    candidate.ratioMatches ? 0xFFBFD0E2 : 0xFFFFC7A0);
                drawEditorButton(
                    detailX,
                    itemY + 2,
                    22,
                    16,
                    "...",
                    mouseX,
                    mouseY,
                    tr("superfactory.machine.super_integrated_factory.recipe_selector.open_nei"));
            }
            drawScrollBar(x + w - 10, y + 24, h - 56, startIndex, endIndex, recipeCandidates.size());
        }
        drawEditorButton(
            x + w - 64,
            y + h - 24,
            48,
            16,
            tr("superfactory.machine.super_integrated_factory.dialog.back"),
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.dialog.back"));
    }

    private void drawExportDialog(int mouseX, int mouseY) {
        int w = 220;
        int h = 86;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj
            .drawString(tr("superfactory.machine.super_integrated_factory.export.title"), x + 6, y + 5, 0xFFFFFFFF);
        fontRendererObj
            .drawString(tr("superfactory.machine.super_integrated_factory.export.file"), x + 10, y + 32, 0xFFE8EEF5);
        exportNameField.drawTextBox();
        drawEditorButton(
            x + 54,
            y + 62,
            42,
            16,
            "OK",
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.process.export"));
        drawEditorButton(
            x + 126,
            y + 62,
            52,
            16,
            tr("superfactory.machine.super_integrated_factory.dialog.back"),
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.dialog.back"));
    }

    private void drawImportSelector(int mouseX, int mouseY) {
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        drawRect(canvasLeft, canvasTop, canvasLeft + canvasWidth, canvasTop + canvasHeight, 0x66000000);
        drawRect(x, y, x + w, y + h, 0xFF1F2A38);
        drawRect(x, y, x + w, y + 16, 0xFF33465C);
        fontRendererObj
            .drawString(tr("superfactory.machine.super_integrated_factory.import.title"), x + 6, y + 5, 0xFFFFFFFF);
        int rowY = y + 24;
        int rows = getCandidateRows();
        if (importGraphFiles.isEmpty()) {
            fontRendererObj
                .drawString(tr("superfactory.machine.super_integrated_factory.import.empty"), x + 10, rowY, 0xFFFFB8B8);
        } else {
            int startIndex = Math.min(importScroll, Math.max(0, importGraphFiles.size() - rows));
            int endIndex = Math.min(importGraphFiles.size(), startIndex + rows);
            for (int i = startIndex; i < endIndex; i++) {
                int itemY = rowY + (i - startIndex) * CANDIDATE_ROW_HEIGHT;
                boolean hover = inRect(mouseX, mouseY, x + 8, itemY - 2, w - 24, CANDIDATE_ROW_HEIGHT - 3);
                drawRect(
                    x + 8,
                    itemY - 2,
                    x + w - 8,
                    itemY + CANDIDATE_ROW_HEIGHT - 4,
                    hover ? 0xFF405872 : 0xFF273445);
                fontRendererObj.drawString(
                    trimToWidth(
                        stripGraphExtension(
                            importGraphFiles.get(i)
                                .getName()),
                        w - 36),
                    x + 14,
                    itemY + 7,
                    0xFFE8EEF5);
            }
            drawScrollBar(x + w - 10, y + 24, h - 56, startIndex, endIndex, importGraphFiles.size());
        }
        drawEditorButton(
            x + w - 64,
            y + h - 24,
            48,
            16,
            tr("superfactory.machine.super_integrated_factory.dialog.back"),
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.dialog.back"));
    }

    private void handleExportDialogClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int w = 220;
        int h = 86;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        exportNameField.mouseClicked(mouseX, mouseY, mouseButton);
        if (inRect(mouseX, mouseY, x + 54, y + 62, 42, 16)) {
            confirmExportGraph();
        } else if (inRect(mouseX, mouseY, x + 126, y + 62, 52, 16)) {
            closeExportDialog();
        }
    }

    private void handleImportSelectorClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int w = getCandidateListWidth();
        int h = getCandidateListHeight();
        int x = getCandidateListX();
        int y = getCandidateListY();
        if (inRect(mouseX, mouseY, x + w - 64, y + h - 24, 48, 16)) {
            closeImportSelector();
            return;
        }
        int startIndex = Math.min(importScroll, Math.max(0, importGraphFiles.size() - getCandidateRows()));
        int endIndex = Math.min(importGraphFiles.size(), startIndex + getCandidateRows());
        for (int i = startIndex; i < endIndex; i++) {
            int itemY = y + 24 + (i - startIndex) * CANDIDATE_ROW_HEIGHT;
            if (inRect(mouseX, mouseY, x + 8, itemY - 2, w - 24, CANDIDATE_ROW_HEIGHT - 3)) {
                File selected = importGraphFiles.get(i);
                openConfirmDialog(
                    tr("superfactory.machine.super_integrated_factory.import.title"),
                    tr("superfactory.machine.super_integrated_factory.import.confirm") + " "
                        + stripGraphExtension(selected.getName())
                        + "?",
                    () -> importGraphFile(selected));
                return;
            }
        }
    }

    private void handleOreDictionaryKeys(char typedChar, int keyCode) {
        if (editingNode == null) {
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
        fontRendererObj.drawString(
            tr("superfactory.machine.super_integrated_factory.ore_variants.title"),
            x + 6,
            y + 5,
            0xFFFFFFFF);
        int rowY = y + 24;
        if (oreVariantStacks.isEmpty()) {
            fontRendererObj.drawString(
                tr("superfactory.machine.super_integrated_factory.ore_variants.empty"),
                x + 10,
                rowY,
                0xFFFFB8B8);
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
        drawEditorButton(
            x + w - 64,
            y + h - 24,
            48,
            16,
            tr("superfactory.machine.super_integrated_factory.dialog.back"),
            mouseX,
            mouseY,
            tr("superfactory.machine.super_integrated_factory.dialog.back"));
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
                applyRecipeCandidateForCheck(recipeCandidates.get(i), candidateSelectorFillOutputs);
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
        candidateSelectorFillOutputs = false;
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

    private void checkCurrentRecipe(boolean fillOutputs) {
        if (editingNode == null) {
            return;
        }
        if (!hasAnyProvidedHints(editingNode)) {
            editingNode.lastRecipeCheckPassed = false;
            closeCandidateSelector();
            showError(tr("superfactory.machine.super_integrated_factory.process.error.no_recipe_hints"));
            return;
        }
        List<RecipeMatchCandidate> candidates;
        try {
            candidates = findRecipeCandidates(editingNode);
        } catch (RuntimeException exception) {
            editingNode.lastRecipeCheckPassed = false;
            closeCandidateSelector();
            showError(tr("superfactory.machine.super_integrated_factory.process.error.no_valid_recipe"));
            return;
        }
        List<RecipeMatchCandidate> exactCandidates = new ArrayList<>();
        for (RecipeMatchCandidate candidate : candidates) {
            if (recipeMatchesNodeExactly(editingNode, candidate.recipe)) {
                exactCandidates.add(candidate);
            }
        }
        if (exactCandidates.size() == 1) {
            applyRecipeCandidateForCheck(exactCandidates.get(0), fillOutputs);
            return;
        }
        if (candidates.size() == 1) {
            applyRecipeCandidateForCheck(candidates.get(0), fillOutputs);
            return;
        }
        if (candidates.isEmpty()) {
            candidates = findRecipeCandidates(editingNode, true);
        }
        editingNode.lastRecipeCheckPassed = false;
        if (candidates.size() > 1) {
            recipeCandidates = candidates;
            candidateSelectorFillOutputs = true;
            candidateSelectorOpen = true;
            candidateScroll = Math.min(candidateScroll, Math.max(0, recipeCandidates.size() - getCandidateRows()));
        } else {
            closeCandidateSelector();
            showError(
                tr("superfactory.machine.super_integrated_factory.process.error.no_matching_recipe") + ": "
                    + safeNodeName(editingNode));
        }
    }

    private boolean applyRecipeCandidateForCheck(RecipeMatchCandidate candidate, boolean fillOutputs) {
        if (editingNode == null || candidate == null) {
            return false;
        }
        if (!recipeHasOutputs(candidate.recipe)) {
            editingNode.lastRecipeCheckPassed = false;
            showError(tr("superfactory.machine.super_integrated_factory.process.error.no_recipe_outputs"));
            return false;
        }
        boolean shouldFillOutputs = fillOutputs
            || !hasProvidedOutputs(editingNode) && !hasProvidedOutputFluids(editingNode);
        applyRecipeCandidate(candidate, shouldFillOutputs);
        return true;
    }

    private boolean recipeHasOutputs(GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        for (ItemStack stack : safeItems(recipe.mOutputs)) {
            if (stack != null) {
                return true;
            }
        }
        for (FluidStack fluid : safeFluids(recipe.mFluidOutputs)) {
            if (fluid != null && fluid.getFluid() != null) {
                return true;
            }
        }
        return false;
    }

    private List<RecipeMatchCandidate> findRecipeCandidates(ProcessNode node) {
        return findRecipeCandidates(node, false);
    }

    private void fillCandidateSelectorOutputs(List<RecipeMatchCandidate> candidates) {
        if (editingNode == null || candidates.isEmpty()
            || hasProvidedOutputs(editingNode)
            || hasProvidedOutputFluids(editingNode)) {
            return;
        }
        RecipeMatchCandidate candidate = candidates.get(0);
        fillHandlerFromRecipe(editingNode.outputHandler, candidate.recipe.mOutputs, candidate.recipe.mFluidOutputs);
        MTESuperIntegratedFactory.applyRecipeOutputChances(editingNode.outputHandler, candidate.recipe, editingNode);
    }

    private List<RecipeMatchCandidate> findRecipeCandidates(ProcessNode node, boolean ignoreHints) {
        Map<String, RecipeMatchCandidate> candidates = new LinkedHashMap<>();
        for (RecipeMap<?> recipeMap : RecipeMap.ALL_RECIPE_MAPS.values()) {
            if (recipeMap == null || recipeMap.getAllRecipes() == null) {
                continue;
            }
            if (isUnsupportedRecipeMapName(recipeMap.unlocalizedName)) {
                continue;
            }
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
            + "|oc="
            + groupedOutputChanceKey(recipe)
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

    private String groupedOutputChanceKey(GTRecipe recipe) {
        if (recipe == null || recipe.mOutputs == null) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < recipe.mOutputs.length; i++) {
            ItemStack stack = recipe.mOutputs[i];
            if (stack == null) {
                continue;
            }
            int chance = recipe.mChances != null && i < recipe.mChances.length ? recipe.mChances[i] : 10000;
            parts.add(itemGroupKey(stack) + "@" + Math.max(1, stack.stackSize) + "%" + normalizeRecipeChance(chance));
        }
        parts.sort(String::compareTo);
        return parts.toString();
    }

    private int normalizeRecipeChance(int chance) {
        return chance <= 0 ? 10000 : Math.max(0, Math.min(10000, chance));
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
        if (recipeMap == null || recipeMap.unlocalizedName == null) {
            return false;
        }
        if (isUnsupportedRecipeMapName(recipeMap.unlocalizedName)) {
            return false;
        }
        if (node.recipeMapName != null && !node.recipeMapName.isEmpty()) {
            return node.recipeMapName.equals(recipeMap.unlocalizedName);
        }
        return node.recipeHandlerName == null || node.recipeHandlerName.isEmpty()
            || node.recipeHandlerName.equals(StatCollector.translateToLocal(recipeMap.unlocalizedName))
            || node.recipeHandlerName.equals(recipeMap.unlocalizedName);
    }

    private boolean isUnsupportedRecipeMapName(String recipeMapName) {
        return "gt.recipe.eyeofharmony".equals(recipeMapName);
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
        applyRecipeCandidate(candidate, false);
    }

    private void applyRecipeCandidate(RecipeMatchCandidate candidate, boolean fillOutputs) {
        if (editingNode == null) {
            return;
        }
        applyRecipeCandidateState(candidate, true);
        if (fillOutputs) {
            fillHandlerFromRecipe(editingNode.outputHandler, candidate.recipe.mOutputs, candidate.recipe.mFluidOutputs);
        } else {
            normalizeExistingOutputs(candidate.recipe);
        }
        MTESuperIntegratedFactory.applyRecipeOutputChances(editingNode.outputHandler, candidate.recipe, editingNode);
        fillHandlerFromNonConsumables(editingNode.nonConsumableHandler, candidate.recipe);
        editingNode.recipeMapName = candidate.recipeMap.unlocalizedName;
        editingNode.recipeHandlerName = StatCollector.translateToLocal(candidate.recipeMap.unlocalizedName);
        applyRecipeTiming(candidate);
        editingNode.recipeFingerprint = editingNode.buildRecipeFingerprint();
        editingNode.lastRecipeCheckPassed = true;
        fillEmptyNameFromRecipeOutput();
        writeEstimatedOutputs(java.util.Collections.singletonList(editingNode));
        rebuildEditorFields(editingNode);
        setEditorFieldsEnabled(!editingNode.locked);
    }

    private void applyRecipeCandidateState(RecipeMatchCandidate candidate, boolean fillMissingInputs) {
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
        if (fillMissingInputs || hasProvidedInputs(editingNode) || hasProvidedInputFluids(editingNode)) {
            fillHandlerFromRecipe(
                editingNode.inputHandler,
                selectDisplayedInputStacks(recipeInputs, currentInputs, preferredVariantsBySlot)
                    .toArray(new ItemStack[0]),
                candidate.recipe.mFluidInputs);
        }
        clearAllInputVariants(editingNode);
        applyRecipeInputVariants(candidate.recipe, preferredVariantsBySlot, currentInputs);
    }

    private void normalizeExistingOutputs(GTRecipe recipe) {
        if (editingNode == null) {
            return;
        }
        normalizeExistingHandlerStacks(editingNode.outputHandler, recipe.mOutputs, recipe.mFluidOutputs);
    }

    private void normalizeExistingHandlerStacks(ItemStackHandler handler, ItemStack[] recipeItems,
        FluidStack[] recipeFluids) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack existing = handler.getStackInSlot(i);
            if (existing == null) {
                continue;
            }
            FluidStack existingFluid = GTUtility.getFluidFromDisplayStack(existing);
            if (existingFluid != null) {
                FluidStack matched = findMatchingFluid(recipeFluids, existingFluid);
                if (matched != null) {
                    handler.setStackInSlot(i, withDisplayAmount(existing, Math.max(1, matched.amount)));
                }
                continue;
            }
            ItemStack matched = findMatchingItem(recipeItems, existing);
            if (matched != null) {
                ItemStack normalized = existing.copy();
                normalized.stackSize = Math.max(1, matched.stackSize);
                handler.setStackInSlot(i, normalized);
            }
        }
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
        editingNode.baseDurationTicks = Math.max(0, candidate.recipe.mDuration);
        editingNode.baseEuPerTick = Math.max(0L, candidate.recipe.mEUt);
        applyNoLossOverclock(editingNode);
        editingNode.recipeMapName = candidate.recipeMap.unlocalizedName;
        editingNode.recipeHandlerName = StatCollector.translateToLocal(candidate.recipeMap.unlocalizedName);
    }

    private void applyNoLossOverclock(ProcessNode node) {
        long duration = Math.max(1L, node.baseDurationTicks > 0 ? node.baseDurationTicks : node.durationTicks);
        long euPerTick = Math.max(0L, node.baseEuPerTick > 0 ? node.baseEuPerTick : node.euPerTick);
        for (int i = 0; i < node.overclockCount; i++) {
            duration = Math.max(1L, (duration + 3L) / 4L);
            euPerTick = saturatingMultiply(euPerTick, 4L);
        }
        node.durationTicks = (int) Math.min(Integer.MAX_VALUE, duration);
        node.euPerTick = Math.min(Long.MAX_VALUE, euPerTick);
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

    private enum EstimateKind {

        INTERNAL(EnumChatFormatting.RED),
        FINAL(EnumChatFormatting.GREEN),
        CYCLIC(EnumChatFormatting.YELLOW);

        private final EnumChatFormatting color;

        EstimateKind(EnumChatFormatting color) {
            this.color = color;
        }
    }

    private static final class EstimateEntry {

        private final ItemStack stack;
        private final EstimateKind kind;
        private double ratePerSecond;

        private EstimateEntry(ItemStack stack, EstimateKind kind, double ratePerSecond) {
            this.stack = stack.copy();
            this.kind = kind;
            this.ratePerSecond = ratePerSecond;
        }
    }

    private static final class ProcessBuildResult {

        private boolean ok;
        private String message;
        private List<ProcessNode> nodes = new ArrayList<>();
        private List<ProcessNode> endNodes = new ArrayList<>();
        private ProcessRequirements requirements = new ProcessRequirements();

        private static ProcessBuildResult ok() {
            ProcessBuildResult result = new ProcessBuildResult();
            result.ok = true;
            result.message = "";
            return result;
        }

        private static ProcessBuildResult error(String message) {
            ProcessBuildResult result = new ProcessBuildResult();
            result.ok = false;
            result.message = message;
            return result;
        }
    }

    private static final class BalanceFraction {

        private static final BalanceFraction ZERO = new BalanceFraction(BigInteger.ZERO, BigInteger.ONE);
        private static final BalanceFraction ONE = new BalanceFraction(BigInteger.ONE, BigInteger.ONE);

        private final BigInteger numerator;
        private final BigInteger denominator;

        private BalanceFraction(long numerator, long denominator) {
            this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        private BalanceFraction(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) {
                throw new ArithmeticException("zero denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            if (!BigInteger.ONE.equals(gcd)) {
                numerator = numerator.divide(gcd);
                denominator = denominator.divide(gcd);
            }
            this.numerator = numerator;
            this.denominator = denominator;
        }

        private BalanceFraction add(BalanceFraction other) {
            return new BalanceFraction(
                numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
        }

        private BalanceFraction subtract(BalanceFraction other) {
            return add(other.negate());
        }

        private BalanceFraction multiply(BalanceFraction other) {
            return new BalanceFraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        private BalanceFraction divide(BalanceFraction other) {
            return new BalanceFraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        private BalanceFraction negate() {
            return new BalanceFraction(numerator.negate(), denominator);
        }

        private boolean isZero() {
            return numerator.signum() == 0;
        }
    }

    private void addNode() {
        ProcessNode node = graph.addDraftNode(screenToWorldX(canvasLeft + 36), screenToWorldY(canvasTop + 36));
        node.name = tr("superfactory.machine.super_integrated_factory.process.new_node") + " " + node.id;
        graph.selectedNodeId = node.id;
        syncGraph();
    }

    private void balanceProcess() {
        ProcessBuildResult result = validateProcessGraph();
        if (!result.ok) {
            showError(result.message);
            return;
        }
        if (!propagateSimpleBalance(result.nodes)) {
            showError(tr("superfactory.machine.super_integrated_factory.process.error.balance_failed"));
            return;
        }
        writeEstimatedOutputs(result.nodes);
        showStatus(tr("superfactory.machine.super_integrated_factory.process.status.balanced"), 0xFF75D17C);
        syncGraph();
    }

    private void confirmResetParallel() {
        openConfirmDialog(
            tr("superfactory.machine.super_integrated_factory.process.reset_parallel"),
            tr("superfactory.machine.super_integrated_factory.process.confirm_reset_parallel"),
            this::resetAllParallel);
    }

    private void confirmResetNodeState() {
        openConfirmDialog(
            tr("superfactory.machine.super_integrated_factory.process.reset_node_state"),
            tr("superfactory.machine.super_integrated_factory.process.confirm_reset_node_state"),
            this::resetAllNodeState);
    }

    private void resetAllParallel() {
        for (ProcessNode node : graph.nodes) {
            node.parallelLimit = 1;
            writeEstimatedOutputs(java.util.Collections.singletonList(node));
        }
        refreshOpenEditorFields();
        showStatus(tr("superfactory.machine.super_integrated_factory.process.status.parallel_reset"), 0xFF75D17C);
        syncGraph();
    }

    private void resetAllNodeState() {
        for (ProcessNode node : graph.nodes) {
            node.parallelLimit = 1;
            node.overclockCount = 0;
            if (node.baseDurationTicks > 0) {
                node.durationTicks = node.baseDurationTicks;
                node.euPerTick = node.baseEuPerTick;
            }
            writeEstimatedOutputs(java.util.Collections.singletonList(node));
        }
        refreshOpenEditorFields();
        showStatus(tr("superfactory.machine.super_integrated_factory.process.status.node_state_reset"), 0xFF75D17C);
        syncGraph();
    }

    private void refreshOpenEditorFields() {
        if (editorOpen && editingNode != null) {
            rebuildEditorFields(editingNode);
            setEditorFieldsEnabled(!editingNode.locked);
        }
    }

    private void submitProcess() {
        ProcessBuildResult result = buildProcessSubmission(true);
        if (!result.ok) {
            showError(result.message);
            return;
        }
        if (factory.getBaseMetaTileEntity() != null) {
            syncGraph();
            NetworkLoader.INSTANCE.sendToServer(
                new MessageSubmitProcessRequirements(
                    factory.getBaseMetaTileEntity(),
                    result.requirements.writeToNBT()));
        }
        closeGui();
    }

    private ProcessBuildResult buildProcessSubmission(boolean requireRequirements) {
        ProcessBuildResult result = validateProcessGraph();
        if (!result.ok) {
            return result;
        }
        if (!refreshSubmittedNodeRecipes(result.nodes)) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.submitted_recipe_invalid"));
        }
        result.requirements = collectProcessRequirements(result.nodes);
        if (requireRequirements && result.requirements.isEmpty()) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.no_requirements"));
        }
        return result;
    }

    private ProcessBuildResult validateProcessGraph() {
        List<ProcessNode> relevantNodes = findRelevantNodes();
        if (relevantNodes.isEmpty()) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.no_connected_process"));
        }
        List<ProcessNode> endNodes = new ArrayList<>();
        for (ProcessNode node : relevantNodes) {
            if (!node.locked) {
                return ProcessBuildResult.error(
                    tr("superfactory.machine.super_integrated_factory.process.error.node_unlocked") + ": "
                        + safeNodeName(node));
            }
            if (!node.lastRecipeCheckPassed) {
                return ProcessBuildResult.error(
                    tr("superfactory.machine.super_integrated_factory.process.error.node_unchecked") + ": "
                        + safeNodeName(node));
            }
            if (node.recipeMapName == null || node.recipeMapName.isEmpty()) {
                return ProcessBuildResult.error(
                    tr("superfactory.machine.super_integrated_factory.process.error.node_no_recipe_map") + ": "
                        + safeNodeName(node));
            }
            if (node.endNode) {
                endNodes.add(node);
            }
        }
        if (endNodes.isEmpty()) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.no_end_node"));
        }
        if (endNodes.size() > 1 && hasAnyCycle(relevantNodes)) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.multi_end_cycle"));
        }
        ProcessNode endNode = endNodes.get(0);
        if (hasIllegalCycle(endNode, relevantNodes)) {
            return ProcessBuildResult
                .error(tr("superfactory.machine.super_integrated_factory.process.error.illegal_cycle"));
        }
        ProcessBuildResult result = ProcessBuildResult.ok();
        result.nodes = relevantNodes;
        result.endNodes = endNodes;
        return result;
    }

    private List<ProcessNode> findRelevantNodes() {
        List<ProcessNode> relevant = new ArrayList<>();
        for (ProcessNode node : graph.nodes) {
            if (node.endNode) {
                collectConnectedNodes(node.id, relevant);
            }
        }
        return relevant;
    }

    private void collectConnectedNodes(int nodeId, List<ProcessNode> relevant) {
        ProcessNode node = graph.findNode(nodeId);
        if (node == null || relevant.contains(node)) {
            return;
        }
        relevant.add(node);
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId == nodeId) {
                collectConnectedNodes(edge.toNodeId, relevant);
            }
            if (edge.toNodeId == nodeId) {
                collectConnectedNodes(edge.fromNodeId, relevant);
            }
        }
    }

    private boolean hasIllegalCycle(ProcessNode endNode, List<ProcessNode> relevantNodes) {
        return hasCycleOutsideAllowedEnd(endNode.id, relevantNodes);
    }

    private boolean hasAnyCycle(List<ProcessNode> relevantNodes) {
        return hasCycleOutsideAllowedEnd(null, relevantNodes);
    }

    private boolean hasCycleOutsideAllowedEnd(Integer allowedEndNodeId, List<ProcessNode> relevantNodes) {
        Set<Integer> relevantIds = new HashSet<>();
        for (ProcessNode node : relevantNodes) {
            relevantIds.add(node.id);
        }
        for (ProcessNode node : relevantNodes) {
            List<Integer> path = new ArrayList<>();
            if (hasCycleOutsideAllowedEndFrom(node.id, allowedEndNodeId, relevantIds, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleOutsideAllowedEndFrom(int nodeId, Integer allowedEndNodeId, Set<Integer> relevantIds,
        List<Integer> path) {
        int existingIndex = path.indexOf(nodeId);
        if (existingIndex >= 0) {
            if (allowedEndNodeId == null) {
                return true;
            }
            for (int i = existingIndex; i < path.size(); i++) {
                if (path.get(i) == allowedEndNodeId) {
                    return false;
                }
            }
            return nodeId != allowedEndNodeId;
        }
        path.add(nodeId);
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId == nodeId && relevantIds.contains(edge.toNodeId)
                && hasCycleOutsideAllowedEndFrom(edge.toNodeId, allowedEndNodeId, relevantIds, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private boolean propagateSimpleBalance(List<ProcessNode> relevantNodes) {
        Map<Integer, Integer> nodeColumns = new LinkedHashMap<>();
        Set<Integer> relevantIds = new HashSet<>();
        for (int i = 0; i < relevantNodes.size(); i++) {
            ProcessNode node = relevantNodes.get(i);
            nodeColumns.put(node.id, i);
            relevantIds.add(node.id);
            node.estimatedOutputLine = "";
        }
        List<BalanceFraction[]> equations = buildBalanceEquations(relevantNodes, relevantIds, nodeColumns);
        long[] solution = solvePositiveIntegerNullspace(equations, relevantNodes.size());
        if (solution == null || !selfLoopsAreNonLosing(relevantNodes, relevantIds)) {
            return false;
        }
        for (int i = 0; i < relevantNodes.size(); i++) {
            if (solution[i] <= 0L || solution[i] > Integer.MAX_VALUE) {
                return false;
            }
            relevantNodes.get(i).parallelLimit = (int) solution[i];
        }
        return true;
    }

    private List<BalanceFraction[]> buildBalanceEquations(List<ProcessNode> relevantNodes, Set<Integer> relevantIds,
        Map<Integer, Integer> nodeColumns) {
        List<BalanceFraction[]> equations = new ArrayList<>();
        for (ProcessNode producer : relevantNodes) {
            for (int outputSlot = 0; outputSlot < producer.outputHandler.getSlots(); outputSlot++) {
                ItemStack output = producer.outputHandler.getStackInSlot(outputSlot);
                if (output == null) {
                    continue;
                }
                BalanceFraction[] equation = newZeroEquation(relevantNodes.size());
                List<ProcessNode> consumers = matchingDirectConsumers(producer.id, output, relevantIds);
                if (consumers.isEmpty()) {
                    continue;
                }
                int producerColumn = nodeColumns.get(producer.id);
                equation[producerColumn] = equation[producerColumn].add(
                    new BalanceFraction(
                        BigInteger.valueOf(expectedOutputNumerator(producer, outputSlot)),
                        BigInteger.valueOf(Math.max(1, producer.durationTicks))
                            .multiply(BigInteger.valueOf(10000L))));
                for (ProcessNode consumer : consumers) {
                    int consumerColumn = nodeColumns.get(consumer.id);
                    equation[consumerColumn] = equation[consumerColumn].subtract(
                        new BalanceFraction(
                            matchingInputAmount(consumer, output),
                            Math.max(1, consumer.durationTicks)));
                }
                equations.add(equation);
            }
        }
        return equations;
    }

    private BalanceFraction[] newZeroEquation(int size) {
        BalanceFraction[] equation = new BalanceFraction[size];
        for (int i = 0; i < equation.length; i++) {
            equation[i] = BalanceFraction.ZERO;
        }
        return equation;
    }

    private List<ProcessNode> matchingDirectConsumers(int producerId, ItemStack output, Set<Integer> relevantIds) {
        List<ProcessNode> consumers = new ArrayList<>();
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId != producerId || !relevantIds.contains(edge.toNodeId)) {
                continue;
            }
            if (edge.toNodeId == producerId) {
                continue;
            }
            ProcessNode consumer = graph.findNode(edge.toNodeId);
            if (consumer != null && matchingInputAmount(consumer, output) > 0L) {
                consumers.add(consumer);
            }
        }
        return consumers;
    }

    private boolean selfLoopsAreNonLosing(List<ProcessNode> relevantNodes, Set<Integer> relevantIds) {
        for (ProcessNode node : relevantNodes) {
            for (ProcessEdge edge : graph.edges) {
                if (edge.fromNodeId != node.id || edge.toNodeId != node.id || !relevantIds.contains(node.id)) {
                    continue;
                }
                for (int inputSlot = 0; inputSlot < node.inputHandler.getSlots(); inputSlot++) {
                    ItemStack input = node.inputHandler.getStackInSlot(inputSlot);
                    if (input == null) {
                        continue;
                    }
                    long inputAmount = getEditableAmount(input);
                    double outputAmount = matchingExpectedOutputAmount(node, input);
                    if (outputAmount > 0.0D && outputAmount + 1.0e-9D < inputAmount) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private long[] solvePositiveIntegerNullspace(List<BalanceFraction[]> equations, int variableCount) {
        if (variableCount <= 0) {
            return null;
        }
        if (equations.isEmpty()) {
            long[] ones = new long[variableCount];
            java.util.Arrays.fill(ones, 1L);
            return ones;
        }
        BalanceFraction[][] matrix = new BalanceFraction[equations.size()][variableCount];
        for (int row = 0; row < equations.size(); row++) {
            System.arraycopy(equations.get(row), 0, matrix[row], 0, variableCount);
        }
        int[] pivotColumns = new int[Math.min(equations.size(), variableCount)];
        int pivotCount = reduceToRref(matrix, pivotColumns, variableCount);
        boolean[] pivot = new boolean[variableCount];
        for (int i = 0; i < pivotCount; i++) {
            pivot[pivotColumns[i]] = true;
        }
        List<Integer> freeColumns = new ArrayList<>();
        for (int column = 0; column < variableCount; column++) {
            if (!pivot[column]) {
                freeColumns.add(column);
            }
        }
        if (freeColumns.isEmpty()) {
            return null;
        }
        List<BalanceFraction[]> basis = new ArrayList<>();
        for (int freeColumn : freeColumns) {
            BalanceFraction[] vector = new BalanceFraction[variableCount];
            for (int i = 0; i < variableCount; i++) {
                vector[i] = BalanceFraction.ZERO;
            }
            vector[freeColumn] = BalanceFraction.ONE;
            for (int pivotRow = 0; pivotRow < pivotCount; pivotRow++) {
                int pivotColumn = pivotColumns[pivotRow];
                vector[pivotColumn] = matrix[pivotRow][freeColumn].negate();
            }
            basis.add(vector);
        }
        BalanceFraction[] candidate = findPositiveCombination(basis, variableCount);
        return candidate == null ? null : toSmallPositiveIntegers(candidate);
    }

    private int reduceToRref(BalanceFraction[][] matrix, int[] pivotColumns, int variableCount) {
        int pivotRow = 0;
        for (int column = 0; column < variableCount && pivotRow < matrix.length; column++) {
            int selected = -1;
            for (int row = pivotRow; row < matrix.length; row++) {
                if (!matrix[row][column].isZero()) {
                    selected = row;
                    break;
                }
            }
            if (selected < 0) {
                continue;
            }
            BalanceFraction[] swap = matrix[pivotRow];
            matrix[pivotRow] = matrix[selected];
            matrix[selected] = swap;
            BalanceFraction divisor = matrix[pivotRow][column];
            for (int c = column; c < variableCount; c++) {
                matrix[pivotRow][c] = matrix[pivotRow][c].divide(divisor);
            }
            for (int row = 0; row < matrix.length; row++) {
                if (row == pivotRow || matrix[row][column].isZero()) {
                    continue;
                }
                BalanceFraction factor = matrix[row][column];
                for (int c = column; c < variableCount; c++) {
                    matrix[row][c] = matrix[row][c].subtract(factor.multiply(matrix[pivotRow][c]));
                }
            }
            pivotColumns[pivotRow] = column;
            pivotRow++;
        }
        return pivotRow;
    }

    private BalanceFraction[] findPositiveCombination(List<BalanceFraction[]> basis, int variableCount) {
        if (basis.size() == 1) {
            BalanceFraction[] vector = basis.get(0);
            if (allPositive(vector)) {
                return vector;
            }
            if (allNegative(vector)) {
                return negateVector(vector);
            }
            return null;
        }
        BalanceFraction[] sum = newZeroFractionVector(variableCount);
        for (BalanceFraction[] vector : basis) {
            for (int i = 0; i < variableCount; i++) {
                sum[i] = sum[i].add(vector[i]);
            }
        }
        if (allPositive(sum)) {
            return sum;
        }
        return searchPositiveCombination(basis, variableCount);
    }

    private BalanceFraction[] searchPositiveCombination(List<BalanceFraction[]> basis, int variableCount) {
        if (basis.size() > 6) {
            return null;
        }
        long deadline = System.nanoTime() + 1_500_000_000L;
        int[] coefficients = new int[basis.size()];
        for (int max = 1; max <= 16; max++) {
            BalanceFraction[] found = searchPositiveCombination(basis, variableCount, coefficients, 0, max, deadline);
            if (found != null) {
                return found;
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        return null;
    }

    private BalanceFraction[] searchPositiveCombination(List<BalanceFraction[]> basis, int variableCount,
        int[] coefficients, int index, int maxCoefficient, long deadline) {
        if (System.nanoTime() > deadline) {
            return null;
        }
        if (index == coefficients.length) {
            BalanceFraction[] candidate = combineBasis(basis, variableCount, coefficients);
            return allPositive(candidate) ? candidate : null;
        }
        for (int coefficient = -maxCoefficient; coefficient <= maxCoefficient; coefficient++) {
            if (coefficient == 0) {
                continue;
            }
            coefficients[index] = coefficient;
            BalanceFraction[] found = searchPositiveCombination(
                basis,
                variableCount,
                coefficients,
                index + 1,
                maxCoefficient,
                deadline);
            if (found != null) {
                return found;
            }
        }
        coefficients[index] = 0;
        return null;
    }

    private BalanceFraction[] combineBasis(List<BalanceFraction[]> basis, int variableCount, int[] coefficients) {
        BalanceFraction[] sum = newZeroFractionVector(variableCount);
        for (int basisIndex = 0; basisIndex < basis.size(); basisIndex++) {
            BalanceFraction coefficient = new BalanceFraction(coefficients[basisIndex], 1);
            BalanceFraction[] vector = basis.get(basisIndex);
            for (int i = 0; i < variableCount; i++) {
                sum[i] = sum[i].add(vector[i].multiply(coefficient));
            }
        }
        return sum;
    }

    private BalanceFraction[] newZeroFractionVector(int size) {
        BalanceFraction[] vector = new BalanceFraction[size];
        for (int i = 0; i < size; i++) {
            vector[i] = BalanceFraction.ZERO;
        }
        return vector;
    }

    private boolean allPositive(BalanceFraction[] vector) {
        for (BalanceFraction value : vector) {
            if (value.numerator.signum() <= 0) {
                return false;
            }
        }
        return true;
    }

    private boolean allNegative(BalanceFraction[] vector) {
        for (BalanceFraction value : vector) {
            if (value.numerator.signum() >= 0) {
                return false;
            }
        }
        return true;
    }

    private BalanceFraction[] negateVector(BalanceFraction[] vector) {
        BalanceFraction[] negated = new BalanceFraction[vector.length];
        for (int i = 0; i < vector.length; i++) {
            negated[i] = vector[i].negate();
        }
        return negated;
    }

    private long[] toSmallPositiveIntegers(BalanceFraction[] vector) {
        BigInteger lcm = BigInteger.ONE;
        for (BalanceFraction value : vector) {
            lcm = lcm(lcm, value.denominator);
        }
        BigInteger gcd = BigInteger.ZERO;
        BigInteger[] integers = new BigInteger[vector.length];
        for (int i = 0; i < vector.length; i++) {
            integers[i] = vector[i].numerator.multiply(lcm.divide(vector[i].denominator));
            if (integers[i].signum() <= 0) {
                return null;
            }
            gcd = gcd.signum() == 0 ? integers[i].abs() : gcd.gcd(integers[i].abs());
        }
        long[] result = new long[vector.length];
        for (int i = 0; i < vector.length; i++) {
            BigInteger reduced = integers[i].divide(gcd);
            if (reduced.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return null;
            }
            result[i] = reduced.longValue();
        }
        return result;
    }

    private BigInteger lcm(BigInteger a, BigInteger b) {
        if (a.signum() == 0 || b.signum() == 0) {
            return BigInteger.ZERO;
        }
        return a.divide(a.gcd(b))
            .multiply(b)
            .abs();
    }

    private void writeEstimatedOutputs(List<ProcessNode> relevantNodes) {
        for (ProcessNode node : relevantNodes) {
            List<String> lines = buildNodeOutputEstimateLines(node, relevantNodes, 6);
            node.estimatedOutputLine = lines.isEmpty() ? "" : String.join("\n", lines);
        }
    }

    private List<String> buildNodeOutputEstimateLines(ProcessNode node, List<ProcessNode> relevantNodes, int limit) {
        List<String> lines = new ArrayList<>();
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            double perSecond = expectedOutputAmount(node, slot) * Math.max(1, node.parallelLimit)
                * 20.0D
                / Math.max(1, node.durationTicks);
            lines.add(output.getDisplayName() + "(~" + formatRate(perSecond) + displayUnit(output) + "/s)");
        }
        return foldLines(lines, limit);
    }

    private List<String> buildGraphOutputEstimateLines(List<ProcessNode> relevantNodes, int limit) {
        Map<String, EstimateEntry> entries = new LinkedHashMap<>();
        for (ProcessNode node : relevantNodes) {
            if (!node.locked || node.durationTicks <= 0) {
                continue;
            }
            for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
                ItemStack output = node.outputHandler.getStackInSlot(slot);
                if (output == null) {
                    continue;
                }
                EstimateKind kind = classifyEstimateKind(node, output, relevantNodes);
                double perSecond = expectedOutputAmount(node, slot) * Math.max(1, node.parallelLimit)
                    * 20.0D
                    / Math.max(1, node.durationTicks);
                String key = estimateKey(output, kind);
                EstimateEntry entry = entries.get(key);
                if (entry == null) {
                    entries.put(key, new EstimateEntry(output, kind, perSecond));
                } else {
                    entry.ratePerSecond += perSecond;
                }
            }
        }
        List<String> lines = new ArrayList<>();
        if (entries.isEmpty()) {
            return lines;
        }
        lines.add(tr("superfactory.machine.super_integrated_factory.gui.output_estimate") + ":");
        int shown = Math.min(entries.size(), Math.max(0, limit - 1));
        int index = 0;
        for (EstimateEntry entry : entries.values()) {
            if (index++ >= shown) {
                break;
            }
            lines.add(
                entry.kind.color + entry.stack
                    .getDisplayName() + " (~" + formatRate(entry.ratePerSecond) + displayUnit(entry.stack) + "/s)");
        }
        if (entries.size() > shown) {
            lines.add(
                EnumChatFormatting.DARK_GRAY + tr("superfactory.machine.super_proxy_factory.gui.folded_prefix")
                    + " "
                    + (entries.size() - shown)
                    + " "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_suffix"));
        }
        return lines;
    }

    private List<String> foldLines(List<String> lines, int limit) {
        if (lines.size() <= limit) {
            return lines;
        }
        List<String> folded = new ArrayList<>();
        int shown = Math.max(0, limit - 1);
        for (int i = 0; i < shown; i++) {
            folded.add(lines.get(i));
        }
        folded.add("+" + (lines.size() - shown));
        return folded;
    }

    private EstimateKind classifyEstimateKind(ProcessNode node, ItemStack output, List<ProcessNode> relevantNodes) {
        boolean consumed = firstConsumerForOutput(node, output, relevantNodes) != null;
        if (node.endNode && consumed) {
            return EstimateKind.CYCLIC;
        }
        return consumed ? EstimateKind.INTERNAL : EstimateKind.FINAL;
    }

    private ProcessNode firstConsumerForOutput(ProcessNode node, ItemStack output, List<ProcessNode> relevantNodes) {
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId != node.id) {
                continue;
            }
            ProcessNode to = graph.findNode(edge.toNodeId);
            if (to != null && relevantNodes.contains(to) && nodeInputConsumes(to, output)) {
                return to;
            }
        }
        return null;
    }

    private String estimateKey(ItemStack output, EstimateKind kind) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
        if (fluid != null && fluid.getFluid() != null) {
            return kind.name() + ":fluid:"
                + fluid.getFluid()
                    .getName();
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(output.getItem());
        return kind.name() + ":item:" + itemName + ":" + output.getItemDamage();
    }

    private String displayUnit(ItemStack output) {
        return GTUtility.getFluidFromDisplayStack(output) == null ? "" : "L";
    }

    private ItemStack firstConsumedOutput(ProcessNode node, List<ProcessNode> relevantNodes) {
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId != node.id) {
                continue;
            }
            ProcessNode to = graph.findNode(edge.toNodeId);
            if (to == null || !relevantNodes.contains(to)) {
                continue;
            }
            for (int outputSlot = 0; outputSlot < node.outputHandler.getSlots(); outputSlot++) {
                ItemStack output = node.outputHandler.getStackInSlot(outputSlot);
                if (output != null && nodeInputConsumes(to, output)) {
                    return output;
                }
            }
        }
        return null;
    }

    private boolean nodeInputConsumes(ProcessNode node, ItemStack output) {
        for (int inputSlot = 0; inputSlot < node.inputHandler.getSlots(); inputSlot++) {
            ItemStack input = node.inputHandler.getStackInSlot(inputSlot);
            if (input == null) {
                continue;
            }
            FluidStack inputFluid = GTUtility.getFluidFromDisplayStack(input);
            FluidStack outputFluid = GTUtility.getFluidFromDisplayStack(output);
            if (inputFluid != null || outputFluid != null) {
                if (inputFluid != null && outputFluid != null && inputFluid.isFluidEqual(outputFluid)) {
                    return true;
                }
                continue;
            }
            if (GTUtility.areStacksEqual(input, output, true)) {
                return true;
            }
        }
        return false;
    }

    private String formatRate(double rate) {
        if (rate >= 1000.0D) {
            return formatCompactAmount(rate);
        }
        if (rate >= 100.0D) {
            return formatWholeNumber(Math.round(rate));
        }
        if (rate >= 10.0D) {
            return addThousandsToDecimal(String.format(java.util.Locale.ROOT, "%.1f", rate));
        }
        return addThousandsToDecimal(String.format(java.util.Locale.ROOT, "%.2f", rate));
    }

    private String addThousandsToDecimal(String number) {
        int dot = number.indexOf('.');
        if (dot < 0) {
            return formatWholeNumber(parseLong(number, 0L));
        }
        long whole = parseLong(number.substring(0, dot), 0L);
        return formatWholeNumber(whole) + number.substring(dot);
    }

    private double matchingExpectedOutputAmount(ProcessNode node, ItemStack input) {
        double amount = 0.0D;
        FluidStack inputFluid = GTUtility.getFluidFromDisplayStack(input);
        for (int outputSlot = 0; outputSlot < node.outputHandler.getSlots(); outputSlot++) {
            ItemStack output = node.outputHandler.getStackInSlot(outputSlot);
            if (output == null) {
                continue;
            }
            FluidStack outputFluid = GTUtility.getFluidFromDisplayStack(output);
            if (inputFluid != null || outputFluid != null) {
                if (inputFluid != null && outputFluid != null && inputFluid.isFluidEqual(outputFluid)) {
                    amount += expectedOutputAmount(node, outputSlot);
                }
                continue;
            }
            if (GTUtility.areStacksEqual(input, output, true)) {
                amount += expectedOutputAmount(node, outputSlot);
            }
        }
        return amount;
    }

    private long expectedOutputNumerator(ProcessNode node, int outputSlot) {
        ItemStack output = node.outputHandler.getStackInSlot(outputSlot);
        if (output == null) {
            return 0L;
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
        long amount = getEditableAmount(output);
        int chance = fluid == null ? node.getOutputChance(outputSlot) : 10000;
        return amount * Math.max(0, Math.min(10000, chance));
    }

    private double expectedOutputAmount(ProcessNode node, int outputSlot) {
        return expectedOutputNumerator(node, outputSlot) / 10000.0D;
    }

    private long matchingInputAmount(ProcessNode node, ItemStack output) {
        long amount = 0L;
        FluidStack outputFluid = GTUtility.getFluidFromDisplayStack(output);
        for (int inputSlot = 0; inputSlot < node.inputHandler.getSlots(); inputSlot++) {
            ItemStack input = node.inputHandler.getStackInSlot(inputSlot);
            if (input == null) {
                continue;
            }
            FluidStack inputFluid = GTUtility.getFluidFromDisplayStack(input);
            if (inputFluid != null || outputFluid != null) {
                if (inputFluid != null && outputFluid != null && inputFluid.isFluidEqual(outputFluid)) {
                    amount += getEditableAmount(input);
                }
                continue;
            }
            if (GTUtility.areStacksEqual(input, output, true)) {
                amount += getEditableAmount(input);
            }
        }
        return amount;
    }

    private ProcessRequirements collectProcessRequirements(List<ProcessNode> nodes) {
        ProcessRequirements requirements = new ProcessRequirements();
        for (ProcessNode node : nodes) {
            collectNodeNonConsumables(requirements, node);
            collectNodeRecipeMap(requirements, node);
        }
        if (hasAnyCycle(nodes)) {
            collectStartupMaterials(requirements, nodes);
        }
        return requirements;
    }

    private void collectStartupMaterials(ProcessRequirements requirements, List<ProcessNode> nodes) {
        Set<Integer> relevantIds = new HashSet<>();
        for (ProcessNode node : nodes) {
            relevantIds.add(node.id);
        }
        for (ProcessEdge edge : graph.edges) {
            if (!relevantIds.contains(edge.fromNodeId) || !relevantIds.contains(edge.toNodeId)
                || !hasDirectedPath(edge.toNodeId, edge.fromNodeId, relevantIds, new HashSet<>())) {
                continue;
            }
            ProcessNode producer = graph.findNode(edge.fromNodeId);
            ProcessNode consumer = graph.findNode(edge.toNodeId);
            if (producer == null || consumer == null) {
                continue;
            }
            collectStartupMaterialsForEdge(requirements, producer, consumer);
        }
    }

    private boolean hasDirectedPath(int fromNodeId, int targetNodeId, Set<Integer> relevantIds, Set<Integer> visited) {
        if (fromNodeId == targetNodeId) {
            return true;
        }
        if (!visited.add(fromNodeId)) {
            return false;
        }
        for (ProcessEdge edge : graph.edges) {
            if (edge.fromNodeId == fromNodeId && relevantIds.contains(edge.toNodeId)
                && hasDirectedPath(edge.toNodeId, targetNodeId, relevantIds, visited)) {
                return true;
            }
        }
        return false;
    }

    private void collectStartupMaterialsForEdge(ProcessRequirements requirements, ProcessNode producer,
        ProcessNode consumer) {
        for (int outputSlot = 0; outputSlot < producer.outputHandler.getSlots(); outputSlot++) {
            ItemStack output = producer.outputHandler.getStackInSlot(outputSlot);
            if (output == null) {
                continue;
            }
            for (int inputSlot = 0; inputSlot < consumer.inputHandler.getSlots(); inputSlot++) {
                ItemStack input = consumer.inputHandler.getStackInSlot(inputSlot);
                if (input != null && inputAcceptsOutput(input, output)) {
                    long reserveMin = saturatingMultiply(getEditableAmount(input), Math.max(1, consumer.parallelLimit));
                    long reserveTarget = safeCeilMultiply(reserveMin, 3L, 2L);
                    addStartupMaterial(requirements, input, safeAddLong(reserveTarget, reserveMin));
                }
            }
        }
    }

    private boolean inputAcceptsOutput(ItemStack input, ItemStack output) {
        FluidStack inputFluid = GTUtility.getFluidFromDisplayStack(input);
        FluidStack outputFluid = GTUtility.getFluidFromDisplayStack(output);
        if (inputFluid != null || outputFluid != null) {
            return inputFluid != null && outputFluid != null && inputFluid.isFluidEqual(outputFluid);
        }
        return GTUtility.areStacksEqual(input, output, true);
    }

    private void addStartupMaterial(ProcessRequirements requirements, ItemStack stack, long amount) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        int required = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
        if (fluid != null && fluid.getFluid() != null) {
            FluidStack copy = fluid.copy();
            copy.amount = 1;
            ProcessRequirements.FluidDemand existing = findStartupFluidDemand(requirements, copy);
            if (existing == null) {
                requirements.startupFluids.add(new ProcessRequirements.FluidDemand(copy, required));
            } else {
                existing.required = Math.max(existing.required, required);
            }
            return;
        }
        ProcessRequirements.ItemDemand existing = findStartupItemDemand(requirements, stack);
        if (existing == null) {
            requirements.startupItems.add(new ProcessRequirements.ItemDemand(withStackSize(stack, 1), required));
        } else {
            existing.required = Math.max(existing.required, required);
        }
    }

    private ProcessRequirements.ItemDemand findStartupItemDemand(ProcessRequirements requirements, ItemStack stack) {
        for (ProcessRequirements.ItemDemand demand : requirements.startupItems) {
            if (demand.stack != null && GTUtility.areStacksEqual(demand.stack, stack, true)) {
                return demand;
            }
        }
        return null;
    }

    private ProcessRequirements.FluidDemand findStartupFluidDemand(ProcessRequirements requirements, FluidStack stack) {
        for (ProcessRequirements.FluidDemand demand : requirements.startupFluids) {
            if (demand.stack != null && demand.stack.isFluidEqual(stack)) {
                return demand;
            }
        }
        return null;
    }

    private boolean refreshSubmittedNodeRecipes(List<ProcessNode> nodes) {
        ProcessNode previousEditingNode = editingNode;
        boolean previousEditorOpen = editorOpen;
        for (ProcessNode node : nodes) {
            editingNode = node;
            List<RecipeMatchCandidate> candidates = findRecipeCandidates(node);
            RecipeMatchCandidate selected = null;
            for (RecipeMatchCandidate candidate : candidates) {
                if (recipeRatioMatches(node, candidate.recipe)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null && candidates.size() == 1) {
                selected = candidates.get(0);
            }
            if (selected == null) {
                editingNode = previousEditingNode;
                editorOpen = previousEditorOpen;
                return false;
            }
            applyRecipeCandidate(selected);
            node.locked = true;
        }
        editingNode = previousEditingNode;
        editorOpen = previousEditorOpen;
        return true;
    }

    private void collectNodeNonConsumables(ProcessRequirements requirements, ProcessNode node) {
        for (int i = 0; i < node.nonConsumableHandler.getSlots(); i++) {
            ItemStack stack = node.nonConsumableHandler.getStackInSlot(i);
            if (stack == null) {
                continue;
            }
            int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getEditableAmount(stack)));
            ProcessRequirements.ItemDemand existing = findItemDemand(requirements, stack);
            if (existing == null) {
                requirements.nonConsumables.add(new ProcessRequirements.ItemDemand(withStackSize(stack, 1), amount));
            } else {
                existing.required = safeAddInt(existing.required, amount);
            }
        }
    }

    private ProcessRequirements.ItemDemand findItemDemand(ProcessRequirements requirements, ItemStack stack) {
        for (ProcessRequirements.ItemDemand demand : requirements.nonConsumables) {
            if (demand.stack != null && GTUtility.areStacksEqual(demand.stack, stack, true)) {
                return demand;
            }
        }
        return null;
    }

    private void collectNodeRecipeMap(ProcessRequirements requirements, ProcessNode node) {
        ProcessRequirements.RecipeMapDemand existing = null;
        for (ProcessRequirements.RecipeMapDemand demand : requirements.recipeMaps) {
            if (demand.recipeMapName.equals(node.recipeMapName)) {
                existing = demand;
                break;
            }
        }
        if (existing == null) {
            requirements.recipeMaps
                .add(new ProcessRequirements.RecipeMapDemand(node.recipeMapName, node.recipeHandlerName, 1));
        } else {
            existing.required = safeAddInt(existing.required, 1);
        }
    }

    private ItemStack withStackSize(ItemStack stack, int amount) {
        ItemStack copy = stack.copy();
        copy.stackSize = amount;
        return copy;
    }

    private int safeAddInt(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private long saturatingMultiply(long a, long b) {
        if (a > 0L && b > Long.MAX_VALUE / a) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private long safeAddLong(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private long safeCeilMultiply(long value, long numerator, long denominator) {
        if (denominator <= 0L) {
            return Long.MAX_VALUE;
        }
        long product = saturatingMultiply(Math.max(0L, value), Math.max(0L, numerator));
        if (product == Long.MAX_VALUE) {
            return product;
        }
        long roundingOffset = denominator - 1L;
        if (roundingOffset > 0L && product > Long.MAX_VALUE - roundingOffset) {
            return Long.MAX_VALUE;
        }
        return (product + roundingOffset) / denominator;
    }

    private long ceilDiv(long a, long b) {
        if (a == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (a - 1L) / b + 1L;
    }

    private void showError(String message) {
        showStatus(message, 0xFFFF7777);
    }

    private void showStatus(String message, int color) {
        statusMessage = message == null ? "" : message;
        statusMessageColor = color;
        statusMessageUntil = System.currentTimeMillis() + 5000L;
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
        node.estimatedOutputLine = "";
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
        int leftPanelWidth = getEditorLeftPanelWidth();
        nameField = createTextField(x + 42, y + 24, Math.max(72, leftPanelWidth - 44), node.name);
        durationField = createTextField(x + 28, y + 48, 50, formatWholeNumber(node.durationTicks));
        euField = createTextField(
            x + Math.min(106, leftPanelWidth - 54),
            y + 48,
            50,
            formatWholeNumber(node.euPerTick));
        overclockField = createTextField(x + 28, y + 72, 50, formatWholeNumber(node.overclockCount));
        parallelField = createTextField(
            x + Math.min(106, leftPanelWidth - 54),
            y + 72,
            50,
            formatWholeNumber(node.parallelLimit));
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
        int editedDuration = Math.max(0, parseInt(durationField.getText(), editingNode.durationTicks));
        long editedEu = Math.max(0L, parseLong(euField.getText(), editingNode.euPerTick));
        editingNode.overclockCount = Math.max(0, parseInt(overclockField.getText(), editingNode.overclockCount));
        if (oldDuration != editedDuration || oldEu != editedEu) {
            editingNode.baseDurationTicks = editedDuration;
            editingNode.baseEuPerTick = editedEu;
        } else if (editingNode.baseDurationTicks <= 0 && editingNode.durationTicks > 0) {
            editingNode.baseDurationTicks = editingNode.durationTicks;
            editingNode.baseEuPerTick = editingNode.euPerTick;
        }
        applyNoLossOverclock(editingNode);
        editingNode.parallelLimit = Math.max(1, parseInt(parallelField.getText(), editingNode.parallelLimit));
        normalizeNumericFieldsToNode();
        if ((oldDuration != editingNode.durationTicks || oldEu != editingNode.euPerTick) && !editingNode.locked) {
            editingNode.lastRecipeCheckPassed = false;
        }
        if (!oldName.equals(editingNode.name) || oldOverclock != editingNode.overclockCount
            || oldParallel != editingNode.parallelLimit) {
            writeEstimatedOutputs(java.util.Collections.singletonList(editingNode));
            syncGraph();
        }
    }

    private void normalizeFocusedNumericFields() {
        if (durationField != null && durationField.isFocused()
            && durationField.getText()
                .trim()
                .isEmpty()) {
            durationField.setText(formatWholeNumber(0));
        }
        if (euField != null && euField.isFocused()
            && euField.getText()
                .trim()
                .isEmpty()) {
            euField.setText(formatWholeNumber(0));
        }
        if (overclockField != null && overclockField.isFocused()
            && overclockField.getText()
                .trim()
                .isEmpty()) {
            overclockField.setText(formatWholeNumber(0));
        }
        if (parallelField != null && parallelField.isFocused()
            && parallelField.getText()
                .trim()
                .isEmpty()) {
            parallelField.setText(formatWholeNumber(1));
        }
        normalizeNumericFieldsToNode();
    }

    private void normalizeNumericFieldsToNode() {
        if (editingNode == null) {
            return;
        }
        if (durationField != null && !durationField.isFocused()) {
            durationField.setText(formatWholeNumber(editingNode.durationTicks));
        }
        if (euField != null && !euField.isFocused()) {
            euField.setText(formatWholeNumber(editingNode.euPerTick));
        }
        if (overclockField != null && !overclockField.isFocused()) {
            overclockField.setText(formatWholeNumber(editingNode.overclockCount));
        }
        if (parallelField != null && !parallelField.isFocused()) {
            parallelField.setText(formatWholeNumber(editingNode.parallelLimit));
        }
    }

    private boolean typeUnsignedInteger(GuiTextField field, char typedChar, int keyCode) {
        if (!field.isFocused()) {
            return false;
        }
        if (Character.isDigit(typedChar) || typedChar == ','
            || keyCode == 14
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
        return Math.min(width >= 420 ? EDITOR_WIDTH : COMPACT_EDITOR_WIDTH, width - 24);
    }

    private int getEditorHeight() {
        return Math.min(width >= 420 ? EDITOR_HEIGHT : COMPACT_EDITOR_HEIGHT, height - 48);
    }

    private int getEditorX() {
        return (width - getEditorWidth()) / 2;
    }

    private int getEditorY() {
        return Math.max(24, (height - getEditorHeight()) / 2);
    }

    private int getEditorLeftPanelWidth() {
        if (getEditorWidth() < 400) {
            return 126;
        }
        return Math.min(152, Math.max(126, getEditorWidth() / 2 - 16));
    }

    private int getEditorPatternX() {
        return getEditorX() + getEditorLeftPanelWidth() + 22;
    }

    private int getEditorButtonY() {
        return getEditorY() + getEditorHeight() - 26;
    }

    private boolean isInsideNodeEditor(int mouseX, int mouseY) {
        return inRect(mouseX, mouseY, getEditorX(), getEditorY(), getEditorWidth(), getEditorHeight());
    }

    private void closeGui() {
        MTESuperIntegratedFactory.setClientEditingFactory(null);
        factory.setActiveProcessGui(null);
        mc.displayGuiScreen(null);
    }

    private void openExportDialog() {
        closeNodeEditorWithDefaultName();
        closeImportSelector();
        exportDialogOpen = true;
        String defaultName = "process_graph";
        exportNameField = createTextField((width - 220) / 2 + 48, (height - 86) / 2 + 28, 150, defaultName);
        exportNameField.setFocused(true);
    }

    private void closeExportDialog() {
        exportDialogOpen = false;
        exportNameField = null;
    }

    private void confirmExportGraph() {
        String fileName = sanitizeGraphFileName(exportNameField == null ? "" : exportNameField.getText());
        if (fileName.isEmpty()) {
            showError(tr("superfactory.machine.super_integrated_factory.export.invalid_name"));
            return;
        }
        File file = new File(getProcessGraphDirectory(), fileName + ".dat");
        try {
            ensureProcessGraphDirectory();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("Version", 1);
            tag.setString("Name", fileName);
            tag.setTag("Graph", graph.writeToNBT());
            CompressedStreamTools.write(tag, file);
            closeExportDialog();
            showStatus(
                tr("superfactory.machine.super_integrated_factory.export.success") + ": " + fileName,
                0xFF75D17C);
        } catch (IOException exception) {
            showError(
                tr("superfactory.machine.super_integrated_factory.export.failed") + ": " + exception.getMessage());
        }
    }

    private void openImportSelector() {
        closeNodeEditorWithDefaultName();
        closeExportDialog();
        importGraphFiles = listProcessGraphFiles();
        importScroll = 0;
        importSelectorOpen = true;
    }

    private void closeImportSelector() {
        importSelectorOpen = false;
        importGraphFiles = new ArrayList<>();
        importScroll = 0;
    }

    private void importGraphFile(File file) {
        try {
            NBTTagCompound tag = CompressedStreamTools.read(file);
            NBTTagCompound graphTag = tag != null && tag.hasKey("Graph") ? tag.getCompoundTag("Graph") : tag;
            if (graphTag == null) {
                showError(tr("superfactory.machine.super_integrated_factory.import.empty_file"));
                return;
            }
            graph.readFromNBT(graphTag);
            closeImportSelector();
            closeNodeEditor();
            syncGraph();
            showStatus(
                tr("superfactory.machine.super_integrated_factory.import.success") + ": "
                    + stripGraphExtension(file.getName()),
                0xFF75D17C);
        } catch (IOException | RuntimeException exception) {
            showError(
                tr("superfactory.machine.super_integrated_factory.import.failed") + ": " + exception.getMessage());
        }
    }

    private List<File> listProcessGraphFiles() {
        ensureProcessGraphDirectory();
        File[] files = getProcessGraphDirectory().listFiles((directory, name) -> name.endsWith(".dat"));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return new ArrayList<>(Arrays.asList(files));
    }

    private void ensureProcessGraphDirectory() {
        File directory = getProcessGraphDirectory();
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    private File getProcessGraphDirectory() {
        return new File(
            new File(
                Loader.instance()
                    .getConfigDir(),
                "superfactory"),
            "process_graphs");
    }

    private String sanitizeGraphFileName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String stripGraphExtension(String fileName) {
        return fileName != null && fileName.endsWith(".dat") ? fileName.substring(0, fileName.length() - 4) : fileName;
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
        if (from != null && to != null && canConnectNodes(from, to) && !edgeExists(from.id, to.id)) {
            graph.edges.add(new ProcessEdge(graph.nextEdgeId++, from.id, to.id));
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
                if (GTUtility.areStacksEqual(input, output, true)) {
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

    private float getNodeTextScale() {
        return (float) Math.max(0.42D, Math.min(1.0D, graph.zoom));
    }

    private void drawScaledNodeString(String text, int x, int y, int maxWidth, int color, float textScale) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        String trimmed = trimToWidth(text, Math.max(8, (int) (maxWidth / textScale)));
        GL11.glPushMatrix();
        GL11.glScalef(textScale, textScale, 1.0F);
        fontRendererObj.drawString(trimmed, Math.round(x / textScale), Math.round(y / textScale), color);
        GL11.glPopMatrix();
    }

    private void drawNodeConnector(int centerX, int centerY) {
        int outer = Math.max(4, scale(8));
        int inner = Math.max(2, scale(4));
        int left = centerX - outer / 2;
        int top = centerY - outer / 2;
        drawRect(left, top + 1, left + outer, top + outer - 1, 0xFFFFFFFF);
        drawRect(left + 1, top, left + outer - 1, top + outer, 0xFFFFFFFF);
        drawRect(
            centerX - inner / 2,
            centerY - inner / 2,
            centerX + (inner + 1) / 2,
            centerY + (inner + 1) / 2,
            0xFF243040);
    }

    private void drawFlowLine(int x1, int y1, int x2, int y2, int color) {
        if (Math.abs(y2 - y1) <= 2) {
            drawSegment(x1, y1, x2, y1, color);
            drawRect(x2 - Math.max(2, scale(4)), y1 - Math.max(2, scale(3)), x2, y1 + Math.max(3, scale(4)), color);
            return;
        }
        int direction = x2 >= x1 ? 1 : -1;
        int exitX = x1 + direction * Math.max(12, scale(12));
        int enterX = x2 - direction * Math.max(12, scale(12));
        int midY = y1 + (y2 - y1) / 2;
        if (direction > 0 && enterX > exitX) {
            int midX = exitX + (enterX - exitX) / 2;
            drawSegment(x1, y1, midX, y1, color);
            drawSegment(midX, y1, midX, y2, color);
            drawSegment(midX, y2, x2, y2, color);
        } else {
            drawSegment(x1, y1, exitX, y1, color);
            drawSegment(exitX, y1, exitX, midY, color);
            drawSegment(exitX, midY, enterX, midY, color);
            drawSegment(enterX, midY, enterX, y2, color);
            drawSegment(enterX, y2, x2, y2, color);
        }
        drawRect(x2 - 4, y2 - 3, x2, y2 + 4, color);
    }

    private void drawSelfLoop(ProcessNode node, int color) {
        int left = worldToScreenX(node.x);
        int right = worldToScreenX(node.x + ProcessNode.WIDTH);
        int centerY = worldToScreenY(node.y + ProcessNode.HEIGHT / 2);
        int top = worldToScreenY(node.y) - Math.max(18, scale(18));
        int rightOut = right + Math.max(18, scale(18));
        int leftOut = left - Math.max(18, scale(18));
        int leftAnchorX = left;
        int rightAnchorX = right;
        int elbowY = Math.min(top, centerY - Math.max(18, scale(18)));
        drawSegment(rightAnchorX, centerY, rightOut, centerY, color);
        drawSegment(rightOut, centerY, rightOut, elbowY, color);
        drawSegment(rightOut, elbowY, leftOut, elbowY, color);
        drawSegment(leftOut, elbowY, leftOut, centerY, color);
        drawSegment(leftOut, centerY, leftAnchorX, centerY, color);
        drawRect(leftAnchorX, centerY - 3, leftAnchorX + 4, centerY + 4, color);
    }

    private void drawSegment(int x1, int y1, int x2, int y2, int color) {
        if (x1 == x2) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            drawRect(x1 - 1, minY, x1 + 1, maxY + 1, color);
            return;
        }
        if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            drawRect(minX, y1 - 1, maxX + 1, y1 + 1, color);
        }
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
            return Integer.parseInt(normalizeNumberText(text));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(normalizeNumberText(text));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalizeNumberText(String text) {
        return text == null ? ""
            : text.replace(",", "")
                .trim();
    }

    private String formatWholeNumber(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    private String formatCompactAmount(long value) {
        return formatCompactAmount((double) value);
    }

    private String formatCompactAmount(double value) {
        double abs = Math.abs(value);
        if (abs < 1000.0D) {
            if (Math.abs(value - Math.rint(value)) < 0.001D) {
                return formatWholeNumber(Math.round(value));
            }
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }
        String[] suffixes = { "K", "M", "G", "T", "P", "E", "Z", "Y" };
        int suffix = -1;
        double scaled = value;
        while (Math.abs(scaled) >= 1000.0D && suffix + 1 < suffixes.length) {
            scaled /= 1000.0D;
            suffix++;
        }
        if (Math.abs(scaled) >= 1000.0D) {
            return String.format(java.util.Locale.ROOT, "%.2e", value);
        }
        String number;
        double scaledAbs = Math.abs(scaled);
        if (scaledAbs >= 100.0D) {
            number = String.format(java.util.Locale.ROOT, "%.0f", scaled);
        } else if (scaledAbs >= 10.0D) {
            number = trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", scaled));
        } else {
            number = trimTrailingZero(String.format(java.util.Locale.ROOT, "%.2f", scaled));
        }
        return number + suffixes[Math.max(0, suffix)];
    }

    private String trimTrailingZero(String value) {
        while (value.endsWith("0") && value.contains(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
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

    private void onImportSelectorWheel(int direction) {
        int maxScroll = Math.max(0, importGraphFiles.size() - getCandidateRows());
        if (maxScroll == 0) {
            importScroll = 0;
            return;
        }
        importScroll = Math.max(0, Math.min(maxScroll, importScroll - direction));
    }
}
