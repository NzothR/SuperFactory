package com.nzoth.superfactory.common.process;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

public final class ProcessRequirements {

    public final List<ItemDemand> nonConsumables = new ArrayList<>();
    public final List<ItemDemand> startupItems = new ArrayList<>();
    public final List<FluidDemand> startupFluids = new ArrayList<>();
    public final List<RecipeMapDemand> recipeMaps = new ArrayList<>();
    public final List<ItemStack> storedMachines = new ArrayList<>();

    public void clear() {
        nonConsumables.clear();
        startupItems.clear();
        startupFluids.clear();
        recipeMaps.clear();
        storedMachines.clear();
    }

    public boolean isEmpty() {
        return nonConsumables.isEmpty() && startupItems.isEmpty() && startupFluids.isEmpty() && recipeMaps.isEmpty();
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList ncList = new NBTTagList();
        for (ItemDemand demand : nonConsumables) {
            ncList.appendTag(demand.writeToNBT());
        }
        tag.setTag("NonConsumables", ncList);
        NBTTagList startupItemList = new NBTTagList();
        for (ItemDemand demand : startupItems) {
            startupItemList.appendTag(demand.writeToNBT());
        }
        tag.setTag("StartupItems", startupItemList);
        NBTTagList startupFluidList = new NBTTagList();
        for (FluidDemand demand : startupFluids) {
            startupFluidList.appendTag(demand.writeToNBT());
        }
        tag.setTag("StartupFluids", startupFluidList);
        NBTTagList recipeMapList = new NBTTagList();
        for (RecipeMapDemand demand : recipeMaps) {
            recipeMapList.appendTag(demand.writeToNBT());
        }
        tag.setTag("RecipeMaps", recipeMapList);
        NBTTagList storedMachineList = new NBTTagList();
        for (ItemStack stack : storedMachines) {
            if (stack != null) {
                storedMachineList.appendTag(stack.writeToNBT(new NBTTagCompound()));
            }
        }
        tag.setTag("StoredMachines", storedMachineList);
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        clear();
        if (tag == null) {
            return;
        }
        NBTTagList ncList = tag.getTagList("NonConsumables", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < ncList.tagCount(); i++) {
            ItemDemand demand = ItemDemand.readFromNBT(ncList.getCompoundTagAt(i));
            if (demand != null) {
                nonConsumables.add(demand);
            }
        }
        NBTTagList startupItemList = tag.getTagList("StartupItems", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < startupItemList.tagCount(); i++) {
            ItemDemand demand = ItemDemand.readFromNBT(startupItemList.getCompoundTagAt(i));
            if (demand != null) {
                startupItems.add(demand);
            }
        }
        NBTTagList startupFluidList = tag.getTagList("StartupFluids", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < startupFluidList.tagCount(); i++) {
            FluidDemand demand = FluidDemand.readFromNBT(startupFluidList.getCompoundTagAt(i));
            if (demand != null) {
                startupFluids.add(demand);
            }
        }
        NBTTagList recipeMapList = tag.getTagList("RecipeMaps", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < recipeMapList.tagCount(); i++) {
            RecipeMapDemand demand = RecipeMapDemand.readFromNBT(recipeMapList.getCompoundTagAt(i));
            if (demand != null) {
                recipeMaps.add(demand);
            }
        }
        NBTTagList storedMachineList = tag.getTagList("StoredMachines", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < storedMachineList.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(storedMachineList.getCompoundTagAt(i));
            if (stack != null) {
                storedMachines.add(stack);
            }
        }
    }

    public ProcessRequirements copy() {
        ProcessRequirements copy = new ProcessRequirements();
        copy.readFromNBT(writeToNBT());
        return copy;
    }

    public boolean hasSubmittedDemands() {
        return !nonConsumables.isEmpty() || !startupItems.isEmpty()
            || !startupFluids.isEmpty()
            || !recipeMaps.isEmpty();
    }

    public boolean hasStoredAnything() {
        if (!storedMachines.isEmpty()) {
            return true;
        }
        for (ItemDemand demand : nonConsumables) {
            if (demand.stored > 0) {
                return true;
            }
        }
        for (ItemDemand demand : startupItems) {
            if (demand.stored > 0) {
                return true;
            }
        }
        for (FluidDemand demand : startupFluids) {
            if (demand.stored > 0) {
                return true;
            }
        }
        for (RecipeMapDemand demand : recipeMaps) {
            if (demand.stored > 0) {
                return true;
            }
        }
        return false;
    }

    public static final class ItemDemand {

        public ItemStack stack;
        public int required;
        public int stored;

        public ItemDemand(ItemStack stack, int required) {
            this.stack = stack == null ? null : stack.copy();
            this.required = Math.max(0, required);
        }

        public int missing() {
            return Math.max(0, required - stored);
        }

        private NBTTagCompound writeToNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            if (stack != null) {
                tag.setTag("Stack", stack.writeToNBT(new NBTTagCompound()));
            }
            tag.setInteger("Required", required);
            tag.setInteger("Stored", stored);
            return tag;
        }

        private static ItemDemand readFromNBT(NBTTagCompound tag) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Stack"));
            if (stack == null) {
                return null;
            }
            ItemDemand demand = new ItemDemand(stack, tag.getInteger("Required"));
            demand.stored = Math.max(0, tag.getInteger("Stored"));
            return demand;
        }
    }

    public static final class FluidDemand {

        public FluidStack stack;
        public int required;
        public int stored;

        public FluidDemand(FluidStack stack, int required) {
            this.stack = stack == null ? null : stack.copy();
            this.required = Math.max(0, required);
        }

        public int missing() {
            return Math.max(0, required - stored);
        }

        private NBTTagCompound writeToNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            if (stack != null) {
                tag.setTag("Fluid", stack.writeToNBT(new NBTTagCompound()));
            }
            tag.setInteger("Required", required);
            tag.setInteger("Stored", stored);
            return tag;
        }

        private static FluidDemand readFromNBT(NBTTagCompound tag) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("Fluid"));
            if (stack == null) {
                return null;
            }
            FluidDemand demand = new FluidDemand(stack, tag.getInteger("Required"));
            demand.stored = Math.max(0, tag.getInteger("Stored"));
            return demand;
        }
    }

    public static final class RecipeMapDemand {

        public String recipeMapName;
        public String displayName;
        public int required;
        public int stored;

        public RecipeMapDemand(String recipeMapName, String displayName, int required) {
            this.recipeMapName = recipeMapName == null ? "" : recipeMapName;
            this.displayName = displayName == null ? this.recipeMapName : displayName;
            this.required = Math.max(0, required);
        }

        public int missing() {
            return Math.max(0, required - stored);
        }

        private NBTTagCompound writeToNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("RecipeMapName", recipeMapName == null ? "" : recipeMapName);
            tag.setString("DisplayName", displayName == null ? "" : displayName);
            tag.setInteger("Required", required);
            tag.setInteger("Stored", stored);
            return tag;
        }

        private static RecipeMapDemand readFromNBT(NBTTagCompound tag) {
            RecipeMapDemand demand = new RecipeMapDemand(
                tag.getString("RecipeMapName"),
                tag.getString("DisplayName"),
                tag.getInteger("Required"));
            demand.stored = Math.max(0, tag.getInteger("Stored"));
            return demand;
        }
    }
}
