package com.nzoth.superfactory.common.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import io.netty.buffer.ByteBuf;

/**
 * Lossless NBT serialization for always-compatible payloads.
 *
 * <p>
 * Standard ByteBufUtils uses a two-byte length prefix, limiting the payload to
 * 32767 bytes. When a recipe contains hundreds of output entries (e.g. merged
 * multi-page Singularity fake recipes) the uncompressed NBT can exceed that
 * limit. This helper always sends a four-byte header followed by the NBT data
 * gzip-compressed with {@code CompressedStreamTools}, which is the same format
 * {@link net.minecraft.network.PacketBuffer#writeNBTTagCompoundToBuffer(NBTTagCompound)} uses internally.
 *
 * <p>
 * Two variants are provided:
 * <ul>
 * <li>{@link #writeLargeTag(ByteBuf, NBTTagCompound)} / {@link #readLargeTag(ByteBuf)} — for FML {@code IMessage}
 * messages that receive a raw {@code ByteBuf}.</li>
 * <li>{@link #writeToPacketBuffer(net.minecraft.network.PacketBuffer, NBTTagCompound)} /
 * {@link #readFromPacketBuffer(net.minecraft.network.PacketBuffer)} — for ModularUI {@code FakeSyncWidget} channels
 * that deliver a {@code PacketBuffer} (which already extends {@code ByteBuf}).</li>
 * </ul>
 */
public final class LargeNbtHelper {

    private LargeNbtHelper() {}

    /**
     * Writes an NBT tag into a raw ByteBuf with an int-length prefix via gzipped compressed output.
     */
    public static void writeLargeTag(ByteBuf buf, NBTTagCompound tag) {
        try {
            byte[] compressed = compress(tag);
            if (compressed == null || compressed.length == 0) {
                buf.writeInt(0);
                return;
            }
            buf.writeInt(compressed.length);
            buf.writeBytes(compressed);
        } catch (IOException impossible) {
            buf.writeInt(0);
        }
    }

    /**
     * Reads an NBT tag from a raw ByteBuf that was written by {@link #writeLargeTag(ByteBuf, NBTTagCompound)}.
     */
    public static NBTTagCompound readLargeTag(ByteBuf buf) {
        int length = buf.readInt();
        if (length <= 0) {
            return null;
        }
        byte[] compressed = new byte[Math.min(length, buf.readableBytes())];
        buf.readBytes(compressed);
        try {
            return CompressedStreamTools
                .read(new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed))));
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Writes an NBT tag into a Netty PacketBuffer via compat-valid compressed output using a 4-byte length prefix.
     */
    public static void writeToPacketBuffer(net.minecraft.network.PacketBuffer buffer, NBTTagCompound tag) {
        writeLargeTag(buffer, tag);
    }

    /**
     * Reads an NBT tag from a Netty PacketBuffer written by
     * {@link #writeToPacketBuffer(net.minecraft.network.PacketBuffer, NBTTagCompound)}.
     */
    public static NBTTagCompound readFromPacketBuffer(net.minecraft.network.PacketBuffer buffer) {
        return readLargeTag(buffer);
    }

    private static byte[] compress(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream gzipStream = new DataOutputStream(new GZIPOutputStream(raw))) {
            CompressedStreamTools.write(tag, gzipStream);
        }
        byte[] data = raw.toByteArray();
        return data.length == 0 ? null : data;
    }
}
