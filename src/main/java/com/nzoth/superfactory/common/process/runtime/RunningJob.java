package com.nzoth.superfactory.common.process.runtime;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

public final class RunningJob {

    public final int nodeId;
    public final int parallel;
    public final int durationTicks;
    public final long euPerTick;
    public int remainingTicks;
    public long reservedEnergy;
    public final List<ItemStack> consumedItems = new ArrayList<>();
    public final List<FluidStack> consumedFluids = new ArrayList<>();

    public RunningJob(int nodeId, int parallel, int durationTicks, long euPerTick) {
        this.nodeId = nodeId;
        this.parallel = Math.max(1, parallel);
        this.durationTicks = Math.max(1, durationTicks);
        this.euPerTick = Math.max(0L, euPerTick);
        this.remainingTicks = this.durationTicks;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("NodeId", nodeId);
        tag.setInteger("Parallel", parallel);
        tag.setInteger("DurationTicks", durationTicks);
        tag.setLong("EUt", euPerTick);
        tag.setInteger("RemainingTicks", remainingTicks);
        tag.setLong("ReservedEnergy", reservedEnergy);
        NBTTagList items = new NBTTagList();
        for (ItemStack stack : consumedItems) {
            if (stack != null && stack.stackSize > 0) {
                items.appendTag(stack.writeToNBT(new NBTTagCompound()));
            }
        }
        tag.setTag("ConsumedItems", items);
        NBTTagList fluids = new NBTTagList();
        for (FluidStack stack : consumedFluids) {
            if (stack != null && stack.amount > 0) {
                fluids.appendTag(stack.writeToNBT(new NBTTagCompound()));
            }
        }
        tag.setTag("ConsumedFluids", fluids);
        return tag;
    }

    public static RunningJob readFromNBT(NBTTagCompound tag) {
        RunningJob job = new RunningJob(
            tag.getInteger("NodeId"),
            tag.getInteger("Parallel"),
            tag.getInteger("DurationTicks"),
            tag.getLong("EUt"));
        job.remainingTicks = Math.max(0, tag.getInteger("RemainingTicks"));
        job.reservedEnergy = Math.max(0L, tag.getLong("ReservedEnergy"));
        NBTTagList items = tag.getTagList("ConsumedItems", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < items.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(items.getCompoundTagAt(i));
            if (stack != null && stack.stackSize > 0) {
                job.consumedItems.add(stack);
            }
        }
        NBTTagList fluids = tag.getTagList("ConsumedFluids", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < fluids.tagCount(); i++) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(fluids.getCompoundTagAt(i));
            if (stack != null && stack.amount > 0) {
                job.consumedFluids.add(stack);
            }
        }
        return job;
    }
}
