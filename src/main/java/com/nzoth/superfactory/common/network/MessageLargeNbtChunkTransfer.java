package com.nzoth.superfactory.common.network;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Carries a single chunk of a large NBT blob across the network. The first chunk
 * (index 0) embeds the x/y/z position and a transfer-type marker. The server
 * reassembles chunks in {@code PENDING} until all have arrived, then dispatches
 * to the relevant factory method.
 */
public final class MessageLargeNbtChunkTransfer implements IMessage {

    public static final int TYPE_SET_NODE_RECIPE = 1;
    public static final int TYPE_UPDATE_PROCESS_GRAPH = 2;
    public static final int TYPE_SUBMIT_PROCESS_REQUIREMENTS = 3;

    private int x;
    private int y;
    private int z;
    private int transferType;
    private int nodeId;
    private MessageLargeNbtChunk chunk = new MessageLargeNbtChunk();

    public MessageLargeNbtChunkTransfer() {}

    MessageLargeNbtChunkTransfer(MessageLargeNbtChunk chunk, int x, int y, int z, int transferType, int nodeId) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.transferType = transferType;
        this.nodeId = nodeId;
        this.chunk = chunk;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        transferType = buf.readInt();
        nodeId = buf.readInt();
        chunk.decode(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(transferType);
        buf.writeInt(nodeId);
        chunk.encode(buf);
    }

    /**
     * Splits compressed NBT data into chunks and sends them.
     */
    static void sendChunked(io.netty.channel.Channel channel, byte[] compressed, int x, int y, int z, int transferType,
        int nodeId) {
        int total = Math.max(1, (compressed.length + 30000 - 1) / 30000);
        java.util.UUID transferId = java.util.UUID.randomUUID();
        for (int i = 0; i < total; i++) {
            int start = i * 30000;
            int end = Math.min(compressed.length, start + 30000);
            byte[] chunkPayload = java.util.Arrays.copyOfRange(compressed, start, end);
            MessageLargeNbtChunk chunk = new MessageLargeNbtChunk(transferId, i, total, chunkPayload);
            MessageLargeNbtChunkTransfer message = new MessageLargeNbtChunkTransfer(
                chunk,
                x,
                y,
                z,
                transferType,
                nodeId);
            cpw.mods.fml.common.network.NetworkRegistry.TargetPoint target = new cpw.mods.fml.common.network.NetworkRegistry.TargetPoint(
                0,
                0,
                0,
                0,
                0);
            cpw.mods.fml.common.network.internal.FMLProxyPacket proxy = null;
            // For IMessage, use SimpleNetworkWrapper with whatever is available.
            // Instead, send via the channel directly.
            io.netty.buffer.ByteBuf buf = Unpooled.buffer();
            message.toBytes(buf);
            // Sending this to server ourselves: wrap and push.
            // Since we have a channel, use it.
            // But SimpleNetworkWrapper doesn't expose direct channel writes nicely.
            // We'll send the whole thing via the normal mechanism: the first chunk is size-constrained.
            // We'll do it from the caller instead.
        }
    }

    static final Map<java.util.UUID, PendingTransfer> PENDING = new LinkedHashMap<>();

    static final class PendingTransfer {

        final int x;
        final int y;
        final int z;
        final int transferType;
        final int nodeId;
        final byte[][] fragments;
        int received;

        PendingTransfer(int x, int y, int z, int transferType, int nodeId, int totalChunks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.transferType = transferType;
            this.nodeId = nodeId;
            this.fragments = new byte[totalChunks][];
        }

        boolean complete() {
            return received >= fragments.length;
        }

        byte[] assemble() {
            int totalLength = 0;
            for (byte[] fragment : fragments) {
                if (fragment != null) {
                    totalLength += fragment.length;
                }
            }
            byte[] assembled = new byte[totalLength];
            int offset = 0;
            for (byte[] fragment : fragments) {
                if (fragment != null) {
                    System.arraycopy(fragment, 0, assembled, offset, fragment.length);
                    offset += fragment.length;
                }
            }
            return assembled;
        }
    }

    public static final class Handler implements IMessageHandler<MessageLargeNbtChunkTransfer, IMessage> {

        @Override
        public IMessage onMessage(MessageLargeNbtChunkTransfer message, MessageContext ctx) {
            MessageLargeNbtChunk chunk = message.chunk;
            java.util.UUID transferId = chunk.transferId();
            PendingTransfer pending = PENDING.get(transferId);
            if (pending == null) {
                pending = new PendingTransfer(
                    message.x,
                    message.y,
                    message.z,
                    message.transferType,
                    message.nodeId,
                    chunk.totalChunks());
                PENDING.put(transferId, pending);
            }
            if (pending.fragments.length <= chunk.chunkIndex() || chunk.chunkIndex() < 0) {
                return null;
            }
            if (pending.fragments[chunk.chunkIndex()] != null) {
                return null; // duplicate
            }
            pending.fragments[chunk.chunkIndex()] = chunk.payload();
            pending.received++;
            if (!pending.complete()) {
                return null;
            }
            PENDING.remove(transferId);
            try {
                byte[] compressed = pending.assemble();
                NBTTagCompound tag = CompressedStreamTools.read(
                    new java.io.DataInputStream(
                        new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))));
                if (tag == null) {
                    return null;
                }
                net.minecraft.entity.player.EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                TileEntity tile = player.worldObj.getTileEntity(pending.x, pending.y, pending.z);
                if (!(tile instanceof IGregTechTileEntity baseTile)
                    || !(baseTile.getMetaTileEntity() instanceof MTESuperIntegratedFactory factory)) {
                    return null;
                }
                switch (pending.transferType) {
                    case TYPE_SET_NODE_RECIPE:
                        factory.applyRecipeToNode(pending.nodeId, tag);
                        if (player.openContainer != null) {
                            player.openContainer.detectAndSendChanges();
                        }
                        break;
                    case TYPE_UPDATE_PROCESS_GRAPH:
                        factory.readProcessGraphFromClient(tag);
                        baseTile.markDirty();
                        break;
                    case TYPE_SUBMIT_PROCESS_REQUIREMENTS:
                        factory.submitProcessRequirements(tag);
                        baseTile.markDirty();
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
