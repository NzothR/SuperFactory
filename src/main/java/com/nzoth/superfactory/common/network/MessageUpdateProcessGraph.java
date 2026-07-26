package com.nzoth.superfactory.common.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.netty.buffer.ByteBuf;

public final class MessageUpdateProcessGraph implements IMessage {

    private int x;
    private int y;
    private int z;
    private NBTTagCompound graphTag;

    public MessageUpdateProcessGraph() {}

    public MessageUpdateProcessGraph(IGregTechTileEntity baseTile, NBTTagCompound graphTag) {
        this.x = baseTile.getXCoord();
        this.y = baseTile.getYCoord();
        this.z = baseTile.getZCoord();
        this.graphTag = graphTag;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        graphTag = LargeNbtHelper.readLargeTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        LargeNbtHelper.writeLargeTag(buf, graphTag);
    }

    public static final class Handler implements IMessageHandler<MessageUpdateProcessGraph, IMessage> {

        @Override
        public IMessage onMessage(MessageUpdateProcessGraph message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (tile instanceof IGregTechTileEntity baseTile
                && baseTile.getMetaTileEntity() instanceof MTESuperIntegratedFactory factory
                && message.graphTag != null) {
                factory.readProcessGraphFromClient(message.graphTag);
                baseTile.markDirty();
                if (com.nzoth.superfactory.Config.debugIntegratedFactoryNetwork) {
                    com.nzoth.superfactory.SuperFactory.LOG.info(
                        "[Super Integrated Factory/Network] MessageUpdateProcessGraph 同步图: nodes={}",
                        message.graphTag.getTagList("Nodes", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)
                            .tagCount());
                }
            }
            return null;
        }
    }
}
