package com.nzoth.superfactory.common.network;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Client-side utility that splits a potentially oversize NBT tag into
 * compressed chunks, writes each chunk as a separate network message, and
 * sends them as chunked {@link MessageLargeNbtChunkTransfer} messages.
 */
public final class LargeNbtSplitter {

    private static final int MAX_PAYLOAD_BYTES = 30000;

    private LargeNbtSplitter() {}

    /**
     * Compresses a tag, splits into ≤30000-byte chunks, and sends every chunk.
     */
    public static void send(IGregTechTileEntity baseTile, NBTTagCompound tag, int transferType, int nodeId) {
        byte[] compressed;
        try {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream gzipStream = new java.io.DataOutputStream(
                new java.util.zip.GZIPOutputStream(raw))) {
                CompressedStreamTools.write(tag, gzipStream);
            }
            compressed = raw.toByteArray();
        } catch (java.io.IOException impossible) {
            com.nzoth.superfactory.SuperFactory.LOG.error(
                "[Super Integrated Factory/Network] NBT 压缩失败: transferType={}", transferType, impossible);
            return;
        }
        int total = Math.max(1, (compressed.length + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES);
        java.util.UUID transferId = java.util.UUID.randomUUID();
        int x = baseTile.getXCoord();
        int y = baseTile.getYCoord();
        int z = baseTile.getZCoord();
        boolean debug = com.nzoth.superfactory.Config.debugIntegratedFactoryNetwork;
        if (debug) {
            com.nzoth.superfactory.SuperFactory.LOG.info(
                "[Super Integrated Factory/Network] 发送分块传输: transferId={}, type={}, totalChunks={}, compressedBytes={}",
                transferId, transferType, total, compressed.length);
        }
        for (int i = 0; i < total; i++) {
            int start = i * MAX_PAYLOAD_BYTES;
            int end = Math.min(compressed.length, start + MAX_PAYLOAD_BYTES);
            byte[] payload = java.util.Arrays.copyOfRange(compressed, start, end);
            MessageLargeNbtChunk chunk = new MessageLargeNbtChunk(transferId, i, total, payload);
            MessageLargeNbtChunkTransfer message = new MessageLargeNbtChunkTransfer(
                chunk,
                x,
                y,
                z,
                transferType,
                nodeId);
            NetworkLoader.INSTANCE.sendToServer(message);
        }
    }
}
