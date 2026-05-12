package com.nzoth.superfactory.common.ui.canvas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import org.lwjgl.opengl.GL11;

import com.gtnewhorizons.modularui.api.GlStateManager;
import com.gtnewhorizons.modularui.api.drawable.GuiHelper;
import com.gtnewhorizons.modularui.api.math.Pos2d;
import com.gtnewhorizons.modularui.api.widget.ISyncedWidget;
import com.gtnewhorizons.modularui.api.widget.Interactable;
import com.gtnewhorizons.modularui.api.widget.Widget;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class CanvasWidget extends Widget implements Interactable, ISyncedWidget {

    private static final int GRID_SIZE = 16;
    private static final int MAJOR_GRID_INTERVAL = 4;
    private static final int NODE_HEADER_HEIGHT = 12;
    private static final int ACTION_OPEN_NODE = 1;
    private static final int ACTION_CONFIRM_DELETE = 2;
    private static final int ACTION_DELETE_NODE = 3;

    private final ProcessGraph graph;
    private final int nodeOpenWindowId;
    private final int deleteConfirmWindowId;
    private boolean needsUpdate;
    private ProcessNode draggingNode;
    private int dragOffsetX;
    private int dragOffsetY;
    private boolean panning;
    private int lastMouseX;
    private int lastMouseY;

    public CanvasWidget(ProcessGraph graph) {
        this(graph, -1, -1);
    }

    public CanvasWidget(ProcessGraph graph, int nodeOpenWindowId) {
        this(graph, nodeOpenWindowId, -1);
    }

    public CanvasWidget(ProcessGraph graph, int nodeOpenWindowId, int deleteConfirmWindowId) {
        this.graph = graph;
        this.nodeOpenWindowId = nodeOpenWindowId;
        this.deleteConfirmWindowId = deleteConfirmWindowId;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void draw(float partialTicks) {
        drawRect(0, 0, getSize().width, getSize().height, 0xFFF3F6FA);
        drawGrid();
        drawEdges();
        drawNodes();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ClickResult onClick(int buttonId, boolean doubleClick) {
        Pos2d mouse = getLocalMouse();
        lastMouseX = mouse.x;
        lastMouseY = mouse.y;
        if (buttonId == 0) {
            ProcessNode node = findNodeAt(mouse.x, mouse.y);
            if (node != null) {
                graph.selectedNodeId = node.id;
                if (doubleClick && nodeOpenWindowId >= 0) {
                    syncCanvasAction(ACTION_OPEN_NODE, node.id);
                    return ClickResult.SUCCESS;
                }
                draggingNode = node;
                int screenX = worldToScreenX(node.x);
                int screenY = worldToScreenY(node.y);
                dragOffsetX = mouse.x - screenX;
                dragOffsetY = mouse.y - screenY;
                return ClickResult.SUCCESS;
            }
        }
        if (buttonId == 1) {
            ProcessNode node = findNodeAt(mouse.x, mouse.y);
            if (node != null) {
                graph.selectedNodeId = node.id;
                if (Interactable.hasShiftDown()) {
                    graph.deleteSelectedNode();
                    syncCanvasAction(ACTION_DELETE_NODE, node.id);
                } else if (deleteConfirmWindowId >= 0) {
                    syncCanvasAction(ACTION_CONFIRM_DELETE, node.id);
                }
                return ClickResult.SUCCESS;
            }
        }
        if (buttonId == 1 || buttonId == 2) {
            panning = true;
            return ClickResult.SUCCESS;
        }
        return ClickResult.ACCEPT;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean onClickReleased(int buttonId) {
        if (draggingNode != null) {
            snapDraggedNodeIfNeeded();
            draggingNode = null;
        }
        panning = false;
        return true;
    }

    @Override
    public void readOnClient(int id, PacketBuffer buf) {}

    @Override
    public void readOnServer(int id, PacketBuffer buf) {
        if (id == ACTION_OPEN_NODE) {
            int nodeId = buf.readInt();
            graph.selectedNodeId = nodeId;
            if (nodeOpenWindowId >= 0 && graph.findNode(nodeId) != null) {
                getContext().openSyncedWindow(nodeOpenWindowId);
            }
        } else if (id == ACTION_CONFIRM_DELETE) {
            int nodeId = buf.readInt();
            graph.selectedNodeId = nodeId;
            if (deleteConfirmWindowId >= 0 && graph.findNode(nodeId) != null) {
                getContext().openSyncedWindow(deleteConfirmWindowId);
            }
        } else if (id == ACTION_DELETE_NODE) {
            graph.selectedNodeId = buf.readInt();
            graph.deleteSelectedNode();
        }
        markForUpdate();
    }

    @Override
    public void markForUpdate() {
        needsUpdate = true;
    }

    @Override
    public void unMarkForUpdate() {
        needsUpdate = false;
    }

    @Override
    public boolean isMarkedForUpdate() {
        return needsUpdate;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onMouseDragged(int buttonId, long deltaTime) {
        Pos2d mouse = getLocalMouse();
        if (draggingNode != null) {
            draggingNode.x = screenToWorldX(mouse.x - dragOffsetX);
            draggingNode.y = screenToWorldY(mouse.y - dragOffsetY);
            if (graph.snapToGrid || Interactable.hasShiftDown()) {
                snapDraggedNodeIfNeeded();
            }
            return;
        }
        if (panning) {
            graph.viewportX += mouse.x - lastMouseX;
            graph.viewportY += mouse.y - lastMouseY;
            lastMouseX = mouse.x;
            lastMouseY = mouse.y;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean onMouseScroll(int direction) {
        double oldZoom = graph.zoom;
        graph.zoom = Math.max(0.25D, Math.min(4.0D, graph.zoom + direction * 0.1D));
        if (oldZoom == graph.zoom) {
            return true;
        }
        Pos2d mouse = getLocalMouse();
        double worldX = (mouse.x - graph.viewportX) / oldZoom;
        double worldY = (mouse.y - graph.viewportY) / oldZoom;
        graph.viewportX = (int) Math.round(mouse.x - worldX * graph.zoom);
        graph.viewportY = (int) Math.round(mouse.y - worldY * graph.zoom);
        return true;
    }

    private void syncCanvasAction(int actionId, int nodeId) {
        if (isClient()) {
            syncToServer(actionId, buffer -> buffer.writeInt(nodeId));
        } else if (actionId == ACTION_OPEN_NODE && nodeOpenWindowId >= 0) {
            getContext().openSyncedWindow(nodeOpenWindowId);
        } else if (actionId == ACTION_CONFIRM_DELETE && deleteConfirmWindowId >= 0) {
            getContext().openSyncedWindow(deleteConfirmWindowId);
        } else if (actionId == ACTION_DELETE_NODE) {
            graph.deleteSelectedNode();
        }
    }

    private void drawGrid() {
        int minorSpacing = Math.max(4, (int) Math.round(GRID_SIZE * graph.zoom));
        int majorSpacing = minorSpacing * MAJOR_GRID_INTERVAL;

        /*
         * The visual grid is derived from the same GRID_SIZE used by snapDraggedNodeIfNeeded(), so every visible
         * intersection is an actual snap point in world space. The viewport offset shifts both together while panning.
         */
        drawGridLines(minorSpacing, 0xFFDDE5EE, false);
        drawGridLines(majorSpacing, 0xFFBAC8D8, true);

        if (minorSpacing >= 8) {
            drawSnapPoints(minorSpacing);
        }

        int originX = worldToScreenX(0);
        int originY = worldToScreenY(0);
        if (originX >= 0 && originX < getSize().width) {
            drawRect(originX - 1, 0, originX + 2, getSize().height, 0xFF4E7190);
        }
        if (originY >= 0 && originY < getSize().height) {
            drawRect(0, originY - 1, getSize().width, originY + 2, 0xFF4E7190);
        }
        if (originX >= 0 && originX < getSize().width && originY >= 0 && originY < getSize().height) {
            drawRect(originX - 3, originY - 3, originX + 4, originY + 4, 0xFF2D506D);
        }
    }

    private void drawGridLines(int spacing, int color, boolean major) {
        int startX = floorMod(graph.viewportX, spacing) - spacing;
        int startY = floorMod(graph.viewportY, spacing) - spacing;
        int lineWidth = major ? 2 : 1;
        for (int x = startX; x < getSize().width; x += spacing) {
            drawRect(x, 0, x + lineWidth, getSize().height, color);
        }
        for (int y = startY; y < getSize().height; y += spacing) {
            drawRect(0, y, getSize().width, y + lineWidth, color);
        }
    }

    private void drawSnapPoints(int spacing) {
        int startX = floorMod(graph.viewportX, spacing) - spacing;
        int startY = floorMod(graph.viewportY, spacing) - spacing;
        for (int x = startX; x < getSize().width; x += spacing) {
            for (int y = startY; y < getSize().height; y += spacing) {
                drawRect(x, y, x + 1, y + 1, 0xFFAAB8C7);
            }
        }
    }

    private void drawEdges() {
        for (ProcessEdge edge : graph.edges) {
            ProcessNode from = graph.findNode(edge.fromNodeId);
            ProcessNode to = graph.findNode(edge.toNodeId);
            if (from == null || to == null) {
                continue;
            }
            int x1 = worldToScreenX(from.x + ProcessNode.WIDTH);
            int y1 = worldToScreenY(from.y + ProcessNode.HEIGHT / 2);
            int x2 = worldToScreenX(to.x);
            int y2 = worldToScreenY(to.y + ProcessNode.HEIGHT / 2);
            if (from.id == to.id) {
                drawSelfLoop(from, 0xFF71C7EC);
                continue;
            }
            drawLine(x1, y1, x2, y2, 0xFF71C7EC);
            drawArrowHead(x1, y1, x2, y2);
        }
    }

    private void drawSelfLoop(ProcessNode node, int color) {
        int left = worldToScreenX(node.x);
        int right = worldToScreenX(node.x + ProcessNode.WIDTH);
        int centerY = worldToScreenY(node.y + ProcessNode.HEIGHT / 2);
        int elbowY = Math.min(worldToScreenY(node.y) - scale(18), centerY - scale(18));
        int leftOut = left - scale(18);
        int rightOut = right + scale(18);
        drawLine(right, centerY, rightOut, centerY, color);
        drawLine(rightOut, centerY, rightOut, elbowY, color);
        drawLine(rightOut, elbowY, leftOut, elbowY, color);
        drawLine(leftOut, elbowY, leftOut, centerY, color);
        drawLine(leftOut, centerY, left, centerY, color);
        drawArrowHead(leftOut, centerY, left, centerY);
    }

    private void drawNodes() {
        for (ProcessNode node : graph.nodes) {
            int x = worldToScreenX(node.x);
            int y = worldToScreenY(node.y);
            int width = scale(ProcessNode.WIDTH);
            int height = scale(ProcessNode.HEIGHT);
            int header = scale(NODE_HEADER_HEIGHT);
            int border = node.endNode ? 0xFFE6C15C : node.locked ? 0xFF75D17C : 0xFF7D8794;
            if (node.id == graph.selectedNodeId) {
                border = 0xFFFFFFFF;
            }
            drawRect(x + 2, y + 2, x + width + 2, y + height + 2, 0x22000000);
            drawRect(x, y, x + width, y + height, 0xFF243040);
            drawRect(x, y, x + width, y + header, node.endNode ? 0xFF6B5028 : 0xFF33465C);
            drawOutline(x, y, width, height, border);
            Minecraft.getMinecraft().fontRenderer
                .drawString(getNodeTitle(node), x + scale(4), y + scale(3), 0xFFFFFFFF);
            String state = node.endNode ? "END" : node.locked ? "LOCKED" : "DRAFT";
            Minecraft.getMinecraft().fontRenderer.drawString(state, x + scale(4), y + scale(22), border);
        }
    }

    private String getNodeTitle(ProcessNode node) {
        ItemStack primaryOutput = node.outputHandler.getStackInSlot(0);
        if (primaryOutput != null) {
            return primaryOutput.getDisplayName();
        }
        ItemStack machine = node.machineHandler.getStackInSlot(0);
        if (machine != null) {
            return machine.getDisplayName();
        }
        return node.name == null ? "" : node.name;
    }

    private void drawArrowHead(int x1, int y1, int x2, int y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int size = 5;
        int ax = (int) Math.round(x2 - Math.cos(angle) * 8);
        int ay = (int) Math.round(y2 - Math.sin(angle) * 8);
        int bx = (int) Math.round(ax - Math.cos(angle - Math.PI / 6.0D) * size);
        int by = (int) Math.round(ay - Math.sin(angle - Math.PI / 6.0D) * size);
        int cx = (int) Math.round(ax - Math.cos(angle + Math.PI / 6.0D) * size);
        int cy = (int) Math.round(ay - Math.sin(angle + Math.PI / 6.0D) * size);
        drawLine(ax, ay, bx, by, 0xFF71C7EC);
        drawLine(ax, ay, cx, cy, 0xFF71C7EC);
    }

    private ProcessNode findNodeAt(int screenX, int screenY) {
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            ProcessNode node = graph.nodes.get(i);
            int x = worldToScreenX(node.x);
            int y = worldToScreenY(node.y);
            if (screenX >= x && screenX < x + scale(ProcessNode.WIDTH)
                && screenY >= y
                && screenY < y + scale(ProcessNode.HEIGHT)) {
                return node;
            }
        }
        return null;
    }

    private void snapDraggedNodeIfNeeded() {
        if (draggingNode == null) {
            return;
        }
        draggingNode.x = Math.round((float) draggingNode.x / GRID_SIZE) * GRID_SIZE;
        draggingNode.y = Math.round((float) draggingNode.y / GRID_SIZE) * GRID_SIZE;
    }

    private Pos2d getLocalMouse() {
        Pos2d mouse = getContext().getMousePos();
        return mouse.subtract(getAbsolutePos());
    }

    private int worldToScreenX(int worldX) {
        return (int) Math.round(graph.viewportX + worldX * graph.zoom);
    }

    private int worldToScreenY(int worldY) {
        return (int) Math.round(graph.viewportY + worldY * graph.zoom);
    }

    private int screenToWorldX(int screenX) {
        return (int) Math.round((screenX - graph.viewportX) / graph.zoom);
    }

    private int screenToWorldY(int screenY) {
        return (int) Math.round((screenY - graph.viewportY) / graph.zoom);
    }

    private int scale(int value) {
        return Math.max(1, (int) Math.round(value * graph.zoom));
    }

    private int floorMod(int value, int modulo) {
        int result = value % modulo;
        return result < 0 ? result + modulo : result;
    }

    private void drawOutline(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y, x + 1, y + height, color);
        drawRect(x + width - 1, y, x + width, y + height, color);
    }

    private void drawRect(int left, int top, int right, int bottom, int color) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(getSize().width, right);
        bottom = Math.min(getSize().height, bottom);
        if (left >= right || top >= bottom) {
            return;
        }
        GuiHelper.drawGradientRect(0, left, top, right, bottom, color, color);
    }

    private void drawLine(int x1, int y1, int x2, int y2, int color) {
        if ((x1 < 0 && x2 < 0) || (y1 < 0 && y2 < 0)
            || (x1 > getSize().width && x2 > getSize().width)
            || (y1 > getSize().height && y2 > getSize().height)) {
            return;
        }
        float alpha = (float) (color >> 24 & 255) / 255.0F;
        float red = (float) (color >> 16 & 255) / 255.0F;
        float green = (float) (color >> 8 & 255) / 255.0F;
        float blue = (float) (color & 255) / 255.0F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x2, y2, 0);
        tessellator.draw();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }
}
