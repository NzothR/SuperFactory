package com.nzoth.superfactory.common.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.modularui.api.forge.ItemStackHandler;

import gregtech.api.util.GTUtility;

public final class ProcessNode {

    public static final int WIDTH = 92;
    public static final int HEIGHT = 42;
    public static final int INPUT_SLOTS = 180;
    public static final int OUTPUT_SLOTS = 180;
    public static final int NON_CONSUMABLE_SLOTS = 9;

    public final int id;
    public int x;
    public int y;
    public boolean locked;
    public boolean endNode;
    public String name;
    public int durationTicks;
    public long euPerTick;
    public int baseDurationTicks;
    public long baseEuPerTick;
    public int overclockCount;
    public int parallelLimit = 1;
    public int inputPage;
    public int outputPage;
    public String recipeHandlerName = "";
    public String recipeMapName = "";
    public String recipeFingerprint = "";
    public String estimatedOutputLine = "";
    public boolean lastRecipeCheckPassed;
    public final int[] outputChances = new int[OUTPUT_SLOTS];
    public final InputVariantState[] inputVariants = new InputVariantState[INPUT_SLOTS];
    public final ItemStackHandler machineHandler = new TrackingItemStackHandler(1, this::markRecipeDirty);
    public final ItemStackHandler inputHandler = new TrackingItemStackHandler(INPUT_SLOTS, this::markRecipeDirty);
    public final ItemStackHandler outputHandler = new TrackingItemStackHandler(OUTPUT_SLOTS, this::markRecipeDirty);
    public final ItemStackHandler nonConsumableHandler = new TrackingItemStackHandler(
        NON_CONSUMABLE_SLOTS,
        this::markRecipeDirty);

    public ProcessNode(int id, int x, int y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.name = "Node " + id;
        java.util.Arrays.fill(outputChances, 10000);
        for (int i = 0; i < inputVariants.length; i++) {
            inputVariants[i] = new InputVariantState();
        }
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Id", id);
        tag.setInteger("X", x);
        tag.setInteger("Y", y);
        tag.setBoolean("Locked", locked);
        tag.setBoolean("EndNode", endNode);
        tag.setString("Name", name == null ? "" : name);
        tag.setInteger("DurationTicks", durationTicks);
        tag.setLong("EUt", euPerTick);
        tag.setInteger("BaseDurationTicks", baseDurationTicks);
        tag.setLong("BaseEUt", baseEuPerTick);
        tag.setInteger("Overclocks", overclockCount);
        tag.setInteger("ParallelLimit", parallelLimit);
        tag.setInteger("InputPage", inputPage);
        tag.setInteger("OutputPage", outputPage);
        tag.setString("RecipeHandlerName", recipeHandlerName == null ? "" : recipeHandlerName);
        tag.setString("RecipeMapName", recipeMapName == null ? "" : recipeMapName);
        tag.setString("RecipeFingerprint", recipeFingerprint == null ? "" : recipeFingerprint);
        tag.setString("EstimatedOutputLine", estimatedOutputLine == null ? "" : estimatedOutputLine);
        tag.setBoolean("LastRecipeCheckPassed", lastRecipeCheckPassed);
        tag.setIntArray("OutputChances", outputChances);
        NBTTagList inputVariantList = new NBTTagList();
        for (int i = 0; i < inputVariants.length; i++) {
            inputVariantList.appendTag(inputVariants[i].writeToNBT(i));
        }
        tag.setTag("InputVariants", inputVariantList);
        tag.setTag("Machine", machineHandler.serializeNBT());
        tag.setTag("Inputs", inputHandler.serializeNBT());
        tag.setTag("Outputs", outputHandler.serializeNBT());
        tag.setTag("NonConsumables", nonConsumableHandler.serializeNBT());
        return tag;
    }

    public static ProcessNode readFromNBT(NBTTagCompound tag) {
        ProcessNode node = new ProcessNode(tag.getInteger("Id"), tag.getInteger("X"), tag.getInteger("Y"));
        node.locked = tag.getBoolean("Locked");
        node.endNode = tag.getBoolean("EndNode");
        node.name = tag.getString("Name");
        node.durationTicks = Math.max(0, tag.getInteger("DurationTicks"));
        node.euPerTick = Math.max(0L, tag.getLong("EUt"));
        node.baseDurationTicks = Math
            .max(0, tag.hasKey("BaseDurationTicks") ? tag.getInteger("BaseDurationTicks") : node.durationTicks);
        node.baseEuPerTick = Math.max(0L, tag.hasKey("BaseEUt") ? tag.getLong("BaseEUt") : node.euPerTick);
        node.overclockCount = Math.max(0, tag.getInteger("Overclocks"));
        node.parallelLimit = Math.max(1, tag.hasKey("ParallelLimit") ? tag.getInteger("ParallelLimit") : 1);
        node.inputPage = Math.max(0, tag.getInteger("InputPage"));
        node.outputPage = Math.max(0, tag.getInteger("OutputPage"));
        node.recipeHandlerName = tag.getString("RecipeHandlerName");
        node.recipeMapName = tag.getString("RecipeMapName");
        node.recipeFingerprint = tag.getString("RecipeFingerprint");
        node.estimatedOutputLine = tag.getString("EstimatedOutputLine");
        node.lastRecipeCheckPassed = tag.getBoolean("LastRecipeCheckPassed");
        if (tag.hasKey("OutputChances", Constants.NBT.TAG_INT_ARRAY)) {
            int[] chances = tag.getIntArray("OutputChances");
            for (int i = 0; i < node.outputChances.length && i < chances.length; i++) {
                node.outputChances[i] = clampChance(chances[i]);
            }
        }
        if (tag.hasKey("Machine")) {
            node.machineHandler.deserializeNBT(tag.getCompoundTag("Machine"));
        }
        if (tag.hasKey("Inputs")) {
            node.inputHandler.deserializeNBT(tag.getCompoundTag("Inputs"));
        }
        if (tag.hasKey("InputVariants", Constants.NBT.TAG_LIST)) {
            NBTTagList inputVariantList = tag.getTagList("InputVariants", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < inputVariantList.tagCount(); i++) {
                NBTTagCompound variantTag = inputVariantList.getCompoundTagAt(i);
                int slot = Math.max(0, Math.min(INPUT_SLOTS - 1, variantTag.getInteger("Slot")));
                node.inputVariants[slot].readFromNBT(variantTag);
            }
        }
        if (tag.hasKey("Outputs")) {
            node.outputHandler.deserializeNBT(tag.getCompoundTag("Outputs"));
        }
        if (tag.hasKey("NonConsumables")) {
            node.nonConsumableHandler.deserializeNBT(tag.getCompoundTag("NonConsumables"));
        }
        return node;
    }

    public int getOutputChance(int slot) {
        if (slot < 0 || slot >= outputChances.length) {
            return 10000;
        }
        return clampChance(outputChances[slot]);
    }

    public void setOutputChance(int slot, int chance) {
        if (slot < 0 || slot >= outputChances.length) {
            return;
        }
        outputChances[slot] = clampChance(chance);
    }

    public void resetOutputChances() {
        java.util.Arrays.fill(outputChances, 10000);
    }

    private static int clampChance(int chance) {
        return chance <= 0 ? 10000 : Math.max(0, Math.min(10000, chance));
    }

    public void clearInputVariants(int slot) {
        if (slot < 0 || slot >= inputVariants.length) {
            return;
        }
        inputVariants[slot].clear();
    }

    public void setInputVariants(int slot, List<ItemStack> variants, int selectedIndex) {
        if (slot < 0 || slot >= inputVariants.length) {
            return;
        }
        inputVariants[slot].setVariants(variants, selectedIndex);
    }

    public boolean hasInputVariants(int slot) {
        return slot >= 0 && slot < inputVariants.length && inputVariants[slot].hasVariants();
    }

    public List<ItemStack> getInputVariants(int slot) {
        if (slot < 0 || slot >= inputVariants.length) {
            return Collections.emptyList();
        }
        return inputVariants[slot].getVariants();
    }

    public ItemStack getInputDisplayStack(int slot) {
        if (slot < 0 || slot >= inputHandler.getSlots()) {
            return null;
        }
        return inputHandler.getStackInSlot(slot);
    }

    public int getInputVariantSelectedIndex(int slot) {
        if (slot < 0 || slot >= inputVariants.length) {
            return 0;
        }
        return inputVariants[slot].getSelectedIndex();
    }

    public void setInputVariantSelectedIndex(int slot, int selectedIndex) {
        if (slot < 0 || slot >= inputVariants.length) {
            return;
        }
        inputVariants[slot].setSelectedIndex(selectedIndex);
    }

    public String buildRecipeFingerprint() {
        return "t=" + durationTicks
            + ";e="
            + euPerTick
            + ";i="
            + handlerFingerprint(inputHandler, inputVariants)
            + ";o="
            + handlerFingerprint(outputHandler, null)
            + ";oc="
            + java.util.Arrays.toString(outputChances)
            + ";nc="
            + handlerFingerprint(nonConsumableHandler, null);
    }

    private void markRecipeDirty() {
        if (!locked) {
            lastRecipeCheckPassed = false;
            estimatedOutputLine = "";
        }
    }

    private static String handlerFingerprint(ItemStackHandler handler, InputVariantState[] variants) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (variants != null && i < variants.length && variants[i].hasVariants()) {
                parts.add(variants[i].fingerprint());
                continue;
            }
            if (stack != null) {
                parts.add(stackFingerprint(stack));
            }
        }
        parts.sort(Comparator.naturalOrder());
        return parts.toString();
    }

    public static String stackFingerprint(ItemStack stack) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null && fluid.getFluid() != null) {
            return "fluid:" + fluid.getFluid()
                .getName() + "@" + Math.max(1, fluid.amount);
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage() + "@" + Math.max(1, stack.stackSize);
    }

    public static final class InputVariantState {

        private final List<ItemStack> variants = new ArrayList<>();
        private int selectedIndex;

        public void clear() {
            variants.clear();
            selectedIndex = 0;
        }

        public void setVariants(List<ItemStack> stacks, int selectedIndex) {
            variants.clear();
            if (stacks != null) {
                for (ItemStack stack : stacks) {
                    if (stack != null) {
                        variants.add(stack.copy());
                    }
                }
            }
            if (variants.isEmpty()) {
                this.selectedIndex = 0;
            } else {
                this.selectedIndex = Math.max(0, Math.min(selectedIndex, variants.size() - 1));
            }
        }

        public boolean hasVariants() {
            return !variants.isEmpty();
        }

        public List<ItemStack> getVariants() {
            List<ItemStack> copy = new ArrayList<>();
            for (ItemStack stack : variants) {
                copy.add(stack.copy());
            }
            return copy;
        }

        public ItemStack getSelected() {
            if (variants.isEmpty()) {
                return null;
            }
            return variants.get(Math.max(0, Math.min(selectedIndex, variants.size() - 1)));
        }

        public int getSelectedIndex() {
            return Math.max(0, Math.min(selectedIndex, Math.max(0, variants.size() - 1)));
        }

        public void setSelectedIndex(int selectedIndex) {
            this.selectedIndex = variants.isEmpty() ? 0 : Math.max(0, Math.min(selectedIndex, variants.size() - 1));
        }

        public String fingerprint() {
            List<String> parts = new ArrayList<>();
            for (ItemStack stack : variants) {
                parts.add(stackFingerprint(stack));
            }
            parts.sort(Comparator.naturalOrder());
            return parts.toString();
        }

        public NBTTagCompound writeToNBT(int slot) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("Slot", slot);
            tag.setInteger("SelectedIndex", selectedIndex);
            NBTTagList list = new NBTTagList();
            for (ItemStack stack : variants) {
                NBTTagCompound stackTag = new NBTTagCompound();
                stack.writeToNBT(stackTag);
                list.appendTag(stackTag);
            }
            tag.setTag("Variants", list);
            return tag;
        }

        public void readFromNBT(NBTTagCompound tag) {
            clear();
            selectedIndex = Math.max(0, tag.getInteger("SelectedIndex"));
            NBTTagList list = tag.getTagList("Variants", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                ItemStack stack = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
                if (stack != null) {
                    variants.add(stack);
                }
            }
            if (!variants.isEmpty()) {
                selectedIndex = Math.max(0, Math.min(selectedIndex, variants.size() - 1));
            }
        }
    }
}
