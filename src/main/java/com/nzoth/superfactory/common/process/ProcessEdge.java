package com.nzoth.superfactory.common.process;

import net.minecraft.nbt.NBTTagCompound;

public final class ProcessEdge {

    public final int id;
    public final int fromNodeId;
    public final int toNodeId;
    public String resourceKey = "";

    public ProcessEdge(int id, int fromNodeId, int toNodeId) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Id", id);
        tag.setInteger("From", fromNodeId);
        tag.setInteger("To", toNodeId);
        tag.setString("Resource", resourceKey == null ? "" : resourceKey);
        return tag;
    }

    public static ProcessEdge readFromNBT(NBTTagCompound tag) {
        ProcessEdge edge = new ProcessEdge(tag.getInteger("Id"), tag.getInteger("From"), tag.getInteger("To"));
        edge.resourceKey = tag.getString("Resource");
        return edge;
    }
}
