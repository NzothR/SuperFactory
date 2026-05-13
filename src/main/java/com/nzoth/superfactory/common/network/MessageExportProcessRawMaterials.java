package com.nzoth.superfactory.common.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;

import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.netty.buffer.ByteBuf;

public final class MessageExportProcessRawMaterials implements IMessage {

    private int x;
    private int y;
    private int z;

    public MessageExportProcessRawMaterials() {}

    public MessageExportProcessRawMaterials(IGregTechTileEntity baseTile) {
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

    public static final class Handler implements IMessageHandler<MessageExportProcessRawMaterials, IMessage> {

        @Override
        public IMessage onMessage(MessageExportProcessRawMaterials message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (tile instanceof IGregTechTileEntity baseTile
                && baseTile.getMetaTileEntity() instanceof MTESuperIntegratedFactory factory) {
                factory.exportProcessRawMaterials(player);
                baseTile.markDirty();
            }
            return null;
        }
    }
}
