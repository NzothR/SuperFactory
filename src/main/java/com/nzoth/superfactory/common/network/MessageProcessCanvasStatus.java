package com.nzoth.superfactory.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.nzoth.superfactory.client.ui.GuiSuperIntegratedFactoryProcess;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class MessageProcessCanvasStatus implements IMessage {

    private String message;
    private int color;

    public MessageProcessCanvasStatus() {}

    public MessageProcessCanvasStatus(String message, int color) {
        this.message = message;
        this.color = color;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        message = ByteBufUtils.readUTF8String(buf);
        color = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, message == null ? "" : message);
        buf.writeInt(color);
    }

    public static final class Handler implements IMessageHandler<MessageProcessCanvasStatus, IMessage> {

        @Override
        public IMessage onMessage(MessageProcessCanvasStatus message, MessageContext ctx) {
            GuiScreen screen = Minecraft.getMinecraft().currentScreen;
            if (screen instanceof GuiSuperIntegratedFactoryProcess processGui) {
                processGui.showServerStatus(message.message, message.color);
            }
            return null;
        }
    }
}
