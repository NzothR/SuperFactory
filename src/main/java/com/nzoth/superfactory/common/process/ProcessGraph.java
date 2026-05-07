package com.nzoth.superfactory.common.process;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

public final class ProcessGraph {

    public static final int DATA_VERSION = 1;

    public int nextNodeId = 1;
    public int nextEdgeId = 1;
    public int viewportX;
    public int viewportY;
    public double zoom = 1.0D;
    public int selectedNodeId;
    public boolean snapToGrid = true;
    public final List<ProcessNode> nodes = new ArrayList<>();
    public final List<ProcessEdge> edges = new ArrayList<>();

    public ProcessNode addDraftNode(int x, int y) {
        ProcessNode node = new ProcessNode(nextNodeId++, x, y);
        nodes.add(node);
        return node;
    }

    public ProcessNode findNode(int id) {
        for (ProcessNode node : nodes) {
            if (node.id == id) {
                return node;
            }
        }
        return null;
    }

    public boolean hasOutgoingEdges(int nodeId) {
        for (ProcessEdge edge : edges) {
            if (edge.fromNodeId == nodeId) {
                return true;
            }
        }
        return false;
    }

    public boolean deleteSelectedNode() {
        ProcessNode selected = findNode(selectedNodeId);
        if (selected == null) {
            return false;
        }
        return deleteNode(selected.id);
    }

    public boolean deleteNode(int nodeId) {
        ProcessNode selected = findNode(nodeId);
        if (selected == null) {
            return false;
        }
        nodes.remove(selected);
        edges.removeIf(edge -> edge.fromNodeId == selected.id || edge.toNodeId == selected.id);
        selectedNodeId = nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).id;
        return true;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", DATA_VERSION);
        tag.setInteger("NextNodeId", nextNodeId);
        tag.setInteger("NextEdgeId", nextEdgeId);
        tag.setInteger("ViewportX", viewportX);
        tag.setInteger("ViewportY", viewportY);
        tag.setDouble("Zoom", zoom);
        tag.setInteger("SelectedNodeId", selectedNodeId);
        tag.setBoolean("SnapToGrid", snapToGrid);

        NBTTagList nodeList = new NBTTagList();
        for (ProcessNode node : nodes) {
            nodeList.appendTag(node.writeToNBT());
        }
        tag.setTag("Nodes", nodeList);

        NBTTagList edgeList = new NBTTagList();
        for (ProcessEdge edge : edges) {
            edgeList.appendTag(edge.writeToNBT());
        }
        tag.setTag("Edges", edgeList);
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        nextNodeId = Math.max(1, tag.getInteger("NextNodeId"));
        nextEdgeId = Math.max(1, tag.getInteger("NextEdgeId"));
        viewportX = tag.getInteger("ViewportX");
        viewportY = tag.getInteger("ViewportY");
        zoom = tag.hasKey("Zoom") ? Math.max(0.25D, Math.min(4.0D, tag.getDouble("Zoom"))) : 1.0D;
        selectedNodeId = tag.getInteger("SelectedNodeId");
        snapToGrid = !tag.hasKey("SnapToGrid") || tag.getBoolean("SnapToGrid");

        nodes.clear();
        NBTTagList nodeList = tag.getTagList("Nodes", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < nodeList.tagCount(); i++) {
            nodes.add(ProcessNode.readFromNBT(nodeList.getCompoundTagAt(i)));
        }

        edges.clear();
        NBTTagList edgeList = tag.getTagList("Edges", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < edgeList.tagCount(); i++) {
            edges.add(ProcessEdge.readFromNBT(edgeList.getCompoundTagAt(i)));
        }
        if (findNode(selectedNodeId) == null) {
            selectedNodeId = nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).id;
        }
    }
}
