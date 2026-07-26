package com.nzoth.superfactory.common.network;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Transfer id + chunk index + total chunks + a byte payload. The first chunk
 * (index 0) carries the transfer id (128-bit UUID); all subsequent chunks repeat
 * the same id so the server-side buffer can reassemble the complete NBT blob.
 */
public final class MessageLargeNbtChunk {

    private long transferIdMost;
    private long transferIdLeast;
    private int chunkIndex;
    private int totalChunks;
    private byte[] payload;

    public MessageLargeNbtChunk() {}

    MessageLargeNbtChunk(UUID transferId, int chunkIndex, int totalChunks, byte[] payload) {
        this.transferIdMost = transferId.getMostSignificantBits();
        this.transferIdLeast = transferId.getLeastSignificantBits();
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.payload = payload;
    }

    UUID transferId() {
        return new UUID(transferIdMost, transferIdLeast);
    }

    int chunkIndex() {
        return chunkIndex;
    }

    int totalChunks() {
        return totalChunks;
    }

    byte[] payload() {
        return payload;
    }

    void encode(ByteBuf buf) {
        buf.writeLong(transferIdMost);
        buf.writeLong(transferIdLeast);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeInt(payload == null ? 0 : payload.length);
        if (payload != null && payload.length > 0) {
            buf.writeBytes(payload);
        }
    }

    void decode(ByteBuf buf) {
        transferIdMost = buf.readLong();
        transferIdLeast = buf.readLong();
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int length = buf.readInt();
        if (length <= 0 || length > 30000) {
            payload = new byte[0];
            return;
        }
        payload = new byte[length];
        buf.readBytes(payload);
    }
}
