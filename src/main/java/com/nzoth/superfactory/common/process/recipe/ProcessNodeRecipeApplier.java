package com.nzoth.superfactory.common.process.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.modularui.api.forge.ItemStackHandler;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;

public final class ProcessNodeRecipeApplier {

    private ProcessNodeRecipeApplier() {}

    public static void apply(ProcessGraph graph, int nodeId, NBTTagCompound recipeTag) {
        ProcessNode node = graph.findNode(nodeId);
        if (node == null || node.locked) {
            return;
        }
        loadHandlerItems(node.inputHandler, recipeTag.getCompoundTag("Inputs"));
        loadInputVariants(node, recipeTag);
        loadHandlerItems(node.outputHandler, recipeTag.getCompoundTag("Outputs"));
        loadOutputChances(node, recipeTag);
        if (recipeTag.hasKey("NonConsumables")) {
            loadHandlerItems(node.nonConsumableHandler, recipeTag.getCompoundTag("NonConsumables"));
        }
        if (recipeTag.hasKey("Machine")) {
            node.machineHandler.setStackInSlot(0, ItemStack.loadItemStackFromNBT(recipeTag.getCompoundTag("Machine")));
        } else {
            node.machineHandler.setStackInSlot(0, null);
        }
        node.durationTicks = Math.max(0, recipeTag.getInteger("DurationTicks"));
        node.euPerTick = Math.max(0L, recipeTag.getLong("EUt"));
        node.baseDurationTicks = Math.max(
            0,
            recipeTag.hasKey("BaseDurationTicks") ? recipeTag.getInteger("BaseDurationTicks") : node.durationTicks);
        node.baseEuPerTick = Math.max(0L, recipeTag.hasKey("BaseEUt") ? recipeTag.getLong("BaseEUt") : node.euPerTick);
        node.recipeHandlerName = recipeTag.getString("RecipeHandlerName");
        node.recipeMapName = recipeTag.getString("RecipeMapName");
        node.fakeRecipeSnapshot = recipeTag.getBoolean("FakeRecipeSnapshot");
        node.virtualRecipeSnapshot = node.fakeRecipeSnapshot
            ? recipeTag.hasKey("VirtualRecipeSnapshot", Constants.NBT.TAG_COMPOUND)
                ? recipeTag.getCompoundTag("VirtualRecipeSnapshot")
                : (NBTTagCompound) recipeTag.copy()
            : null;
        node.recipeFingerprint = recipeTag.getString("RecipeFingerprint");
        node.lastRecipeCheckPassed = node.recipeFingerprint != null
            && node.recipeFingerprint.equals(node.buildRecipeFingerprint());
        node.locked = false;
    }

    public static String buildRecipeFingerprint(GTRecipe recipe) {
        if (recipe == null) {
            return "";
        }
        ItemStackHandler inputs = new ItemStackHandler(ProcessNode.INPUT_SLOTS);
        ItemStackHandler outputs = new ItemStackHandler(ProcessNode.OUTPUT_SLOTS);
        ItemStackHandler nonConsumables = new ItemStackHandler(ProcessNode.NON_CONSUMABLE_SLOTS);
        fillHandler(inputs, recipe.mInputs);
        fillHandlerWithFluids(inputs, recipe.mFluidInputs);
        fillHandler(outputs, recipe.mOutputs);
        fillHandlerWithFluids(outputs, recipe.mFluidOutputs);
        applyRecipeOutputChances(outputs, recipe, null);
        if (recipe.mSpecialItems instanceof ItemStack stack) {
            nonConsumables.setStackInSlot(0, stack.copy());
        } else if (recipe.mSpecialItems instanceof ItemStack[]stacks) {
            fillHandler(nonConsumables, stacks);
        }
        return buildRecipeFingerprint(
            inputs,
            outputs,
            nonConsumables,
            buildOutputChanceArray(outputs, recipe),
            recipe.mDuration,
            recipe.mEUt);
    }

    public static String buildRecipeFingerprint(ItemStackHandler inputs, ItemStackHandler outputs,
        ItemStackHandler nonConsumables, int[] outputChances, int duration, long euPerTick) {
        return "t=" + duration
            + ";e="
            + euPerTick
            + ";i="
            + handlerFingerprint(inputs)
            + ";o="
            + handlerFingerprint(outputs)
            + ";oc="
            + outputChanceFingerprint(outputs, outputChances == null ? defaultOutputChances() : outputChances)
            + ";nc="
            + handlerFingerprint(nonConsumables);
    }

    public static String buildRecipeFingerprint(ItemStackHandler inputs, ItemStackHandler outputs,
        ItemStackHandler nonConsumables, int duration, long euPerTick) {
        return buildRecipeFingerprint(inputs, outputs, nonConsumables, defaultOutputChances(), duration, euPerTick);
    }

    public static void applyRecipeOutputChances(ItemStackHandler outputs, GTRecipe recipe, ProcessNode node) {
        if (node != null) {
            node.resetOutputChances();
        }
        if (outputs == null || recipe == null) {
            return;
        }
        boolean[] usedSlots = new boolean[outputs.getSlots()];
        for (int recipeOutput = 0; recipeOutput < recipe.mOutputs.length; recipeOutput++) {
            ItemStack stack = recipe.mOutputs[recipeOutput];
            if (stack == null) {
                continue;
            }
            int slot = findMatchingOutputSlot(outputs, stack, usedSlots);
            if (slot >= 0 && node != null) {
                node.setOutputChance(slot, normalizeRecipeChance(recipe.getOutputChance(recipeOutput)));
                usedSlots[slot] = true;
            }
        }
    }

    private static void loadOutputChances(ProcessNode node, NBTTagCompound recipeTag) {
        node.resetOutputChances();
        if (!recipeTag.hasKey("OutputChances", Constants.NBT.TAG_INT_ARRAY)) {
            return;
        }
        int[] chances = recipeTag.getIntArray("OutputChances");
        for (int slot = 0; slot < chances.length && slot < ProcessNode.OUTPUT_SLOTS; slot++) {
            node.setOutputChance(slot, chances[slot]);
        }
    }

    private static void loadHandlerItems(ItemStackHandler handler, NBTTagCompound handlerTag) {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, null);
        }
        handler.deserializeNBT(handlerTag);
    }

    private static void loadInputVariants(ProcessNode node, NBTTagCompound recipeTag) {
        for (int i = 0; i < ProcessNode.INPUT_SLOTS; i++) {
            node.clearInputVariants(i);
        }
        if (!recipeTag.hasKey("InputVariants", Constants.NBT.TAG_LIST)) {
            return;
        }
        NBTTagList inputVariantList = recipeTag.getTagList("InputVariants", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < inputVariantList.tagCount(); i++) {
            NBTTagCompound variantTag = inputVariantList.getCompoundTagAt(i);
            int slot = Math.max(0, Math.min(ProcessNode.INPUT_SLOTS - 1, variantTag.getInteger("Slot")));
            node.inputVariants[slot].readFromNBT(variantTag);
        }
    }

    private static int[] buildOutputChanceArray(ItemStackHandler outputs, GTRecipe recipe) {
        int[] chances = defaultOutputChances();
        if (outputs == null || recipe == null) {
            return chances;
        }
        boolean[] usedSlots = new boolean[outputs.getSlots()];
        for (int recipeOutput = 0; recipeOutput < recipe.mOutputs.length; recipeOutput++) {
            ItemStack stack = recipe.mOutputs[recipeOutput];
            if (stack == null) {
                continue;
            }
            int slot = findMatchingOutputSlot(outputs, stack, usedSlots);
            if (slot >= 0) {
                chances[slot] = normalizeRecipeChance(recipe.getOutputChance(recipeOutput));
                usedSlots[slot] = true;
            }
        }
        return chances;
    }

    private static void fillHandler(ItemStackHandler handler, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        int slot = firstEmptySlot(handler);
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0 && slot < handler.getSlots()) {
                handler.setStackInSlot(slot++, stack.copy());
            }
        }
    }

    private static void fillHandlerWithFluids(ItemStackHandler handler, FluidStack[] stacks) {
        if (stacks == null) {
            return;
        }
        int slot = firstEmptySlot(handler);
        for (FluidStack stack : stacks) {
            if (stack != null && stack.amount > 0 && slot < handler.getSlots()) {
                ItemStack display = GTUtility.getFluidDisplayStack(stack, true);
                if (display != null) {
                    handler.setStackInSlot(slot++, display);
                }
            }
        }
    }

    private static int normalizeRecipeChance(int chance) {
        return chance <= 0 ? 10000 : Math.max(0, Math.min(10000, chance));
    }

    private static int[] defaultOutputChances() {
        int[] chances = new int[ProcessNode.OUTPUT_SLOTS];
        java.util.Arrays.fill(chances, 10000);
        return chances;
    }

    private static int findMatchingOutputSlot(ItemStackHandler outputs, ItemStack stack, boolean[] usedSlots) {
        for (int slot = 0; slot < outputs.getSlots(); slot++) {
            if (usedSlots != null && slot < usedSlots.length && usedSlots[slot]) {
                continue;
            }
            ItemStack existing = outputs.getStackInSlot(slot);
            if (existing != null && GTUtility.areStacksEqual(existing, stack, true)) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstEmptySlot(ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i) == null) {
                return i;
            }
        }
        return handler.getSlots();
    }

    private static String handlerFingerprint(ItemStackHandler handler) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack != null) {
                parts.add(stackFingerprint(stack));
            }
        }
        parts.sort(Comparator.naturalOrder());
        return parts.toString();
    }

    private static String outputChanceFingerprint(ItemStackHandler outputs, int[] outputChances) {
        List<String> parts = new ArrayList<>();
        if (outputs == null || outputChances == null) {
            return parts.toString();
        }
        for (int i = 0; i < outputs.getSlots() && i < outputChances.length; i++) {
            if (outputs.getStackInSlot(i) != null && outputChances[i] != 10000) {
                parts.add(i + ":" + outputChances[i]);
            }
        }
        return parts.toString();
    }

    private static String stackFingerprint(ItemStack stack) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null && fluid.getFluid() != null) {
            return "fluid:" + fluid.getFluid()
                .getName() + "@" + Math.max(1, fluid.amount);
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage() + "@" + Math.max(1, stack.stackSize);
    }
}
