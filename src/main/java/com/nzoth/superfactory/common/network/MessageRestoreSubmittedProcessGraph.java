package com.nzoth.superfactory.common.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;

import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.netty.buffer.ByteBuf;

public final class MessageRestoreSubmittedProcessGraph implements IMessage {

    private int x;
    private int y;
    private int z;

    public MessageRestoreSubmittedProcessGraph() {}

    public MessageRestoreSubmittedProcessGraph(IGregTechTileEntity baseTile) {
        this.x = baseTile.getXCoord();
        this.y = baseTile.getYCoord();
        this.z = baseTile.getZCoord();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static final class Handler implements IMessageHandler<MessageRestoreSubmittedProcessGraph, IMessage> {

        @Override
        public IMessage onMessage(MessageRestoreSubmittedProcessGraph message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (tile instanceof IGregTechTileEntity baseTile
                && baseTile.getMetaTileEntity() instanceof MTESuperIntegratedFactory factory) {
                factory.restoreSubmittedProcessGraph(player);
                baseTile.markDirty();
            }
            return null;
        }
    }
}
