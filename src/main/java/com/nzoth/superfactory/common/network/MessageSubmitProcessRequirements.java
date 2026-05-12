package com.nzoth.superfactory.common.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.netty.buffer.ByteBuf;

public final class MessageSubmitProcessRequirements implements IMessage {

    private int x;
    private int y;
    private int z;
    private NBTTagCompound requirementsTag;

    public MessageSubmitProcessRequirements() {}

    public MessageSubmitProcessRequirements(IGregTechTileEntity baseTile, NBTTagCompound requirementsTag) {
        this.x = baseTile.getXCoord();
        this.y = baseTile.getYCoord();
        this.z = baseTile.getZCoord();
        this.requirementsTag = requirementsTag;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        requirementsTag = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        ByteBufUtils.writeTag(buf, requirementsTag);
    }

    public static final class Handler implements IMessageHandler<MessageSubmitProcessRequirements, IMessage> {

        @Override
        public IMessage onMessage(MessageSubmitProcessRequirements message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (tile instanceof IGregTechTileEntity baseTile
                && baseTile.getMetaTileEntity() instanceof MTESuperIntegratedFactory factory
                && message.requirementsTag != null) {
                factory.submitProcessRequirements(message.requirementsTag);
                baseTile.markDirty();
            }
            return null;
        }
    }
}
