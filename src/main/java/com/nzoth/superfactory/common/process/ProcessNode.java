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
    public static final int SLOTS_PER_PAGE = 18;
    public static final int PATTERN_PAGE_COUNT = 100;
    public static final int INPUT_SLOTS = SLOTS_PER_PAGE * PATTERN_PAGE_COUNT;
    public static final int OUTPUT_SLOTS = SLOTS_PER_PAGE * PATTERN_PAGE_COUNT;
    public static final int NON_CONSUMABLE_SLOTS = 9;
    public static final String DISPLAY_AMOUNT_KEY = "SuperFactoryDisplayAmount";
    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_RECYCLER = 1;
    public static final String FAKE_RECIPE_PROXY_HOST = "superfactory.machine.super_proxy_factory";

    public final int id;
    public int x;
    public int y;
    public boolean locked;
    public boolean endNode;
    public int nodeType = TYPE_NORMAL;
    public boolean recyclerOutputsScrapbox;
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
    public boolean fakeRecipeSnapshot;
    public NBTTagCompound virtualRecipeSnapshot;
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
        tag.setInteger("NodeType", nodeType);
        tag.setBoolean("RecyclerOutputsScrapbox", recyclerOutputsScrapbox);
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
        tag.setBoolean("FakeRecipeSnapshot", fakeRecipeSnapshot);
        if (virtualRecipeSnapshot != null) {
            tag.setTag("VirtualRecipeSnapshot", virtualRecipeSnapshot.copy());
        }
        tag.setBoolean("LastRecipeCheckPassed", lastRecipeCheckPassed);
        NBTTagList outputChanceList = new NBTTagList();
        for (int i = 0; i < outputChances.length; i++) {
            if (outputChances[i] != 10000) {
                NBTTagCompound chanceTag = new NBTTagCompound();
                chanceTag.setInteger("Slot", i);
                chanceTag.setInteger("Chance", outputChances[i]);
                outputChanceList.appendTag(chanceTag);
            }
        }
        tag.setTag("OutputChanceOverrides", outputChanceList);
        NBTTagList inputVariantList = new NBTTagList();
        for (int i = 0; i < inputVariants.length; i++) {
            if (inputVariants[i].hasVariants()) {
                inputVariantList.appendTag(inputVariants[i].writeToNBT(i));
            }
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
        node.nodeType = tag.hasKey("NodeType") ? Math.max(TYPE_NORMAL, tag.getInteger("NodeType")) : TYPE_NORMAL;
        node.recyclerOutputsScrapbox = tag.getBoolean("RecyclerOutputsScrapbox");
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
        node.fakeRecipeSnapshot = tag.getBoolean("FakeRecipeSnapshot");
        if (tag.hasKey("VirtualRecipeSnapshot", Constants.NBT.TAG_COMPOUND)) {
            node.virtualRecipeSnapshot = tag.getCompoundTag("VirtualRecipeSnapshot");
        }
        node.lastRecipeCheckPassed = tag.getBoolean("LastRecipeCheckPassed");
        if (tag.hasKey("OutputChances", Constants.NBT.TAG_INT_ARRAY)) {
            int[] chances = tag.getIntArray("OutputChances");
            for (int i = 0; i < node.outputChances.length && i < chances.length; i++) {
                node.outputChances[i] = clampChance(chances[i]);
            }
        }
        if (tag.hasKey("OutputChanceOverrides", Constants.NBT.TAG_LIST)) {
            NBTTagList chanceList = tag.getTagList("OutputChanceOverrides", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < chanceList.tagCount(); i++) {
                NBTTagCompound chanceTag = chanceList.getCompoundTagAt(i);
                int slot = chanceTag.getInteger("Slot");
                if (slot >= 0 && slot < node.outputChances.length) {
                    node.outputChances[slot] = clampChance(chanceTag.getInteger("Chance"));
                }
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
        return "type=" + nodeType
            + ";scrapbox="
            + recyclerOutputsScrapbox
            + ";fake="
            + fakeRecipeSnapshot
            + ";t="
            + durationTicks
            + ";e="
            + euPerTick
            + ";i="
            + handlerFingerprint(inputHandler, inputVariants)
            + ";o="
            + handlerFingerprint(outputHandler, null)
            + ";oc="
            + outputChanceFingerprint()
            + ";nc="
            + handlerFingerprint(nonConsumableHandler, null);
    }

    private String outputChanceFingerprint() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < outputHandler.getSlots() && i < outputChances.length; i++) {
            if (outputHandler.getStackInSlot(i) != null && outputChances[i] != 10000) {
                parts.add(i + ":" + outputChances[i]);
            }
        }
        return parts.toString();
    }

    private void markRecipeDirty() {
        if (!locked) {
            lastRecipeCheckPassed = isRecyclerNode();
            estimatedOutputLine = "";
        }
    }

    public boolean isRecyclerNode() {
        return nodeType == TYPE_RECYCLER;
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
                .getName() + "@" + Math.max(1L, getDisplayAmount(stack));
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage() + "@" + Math.max(1L, getDisplayAmount(stack));
    }

    public static long getDisplayAmount(ItemStack stack) {
        if (stack == null) {
            return 0L;
        }
        if (stack.hasTagCompound() && stack.getTagCompound()
            .hasKey(DISPLAY_AMOUNT_KEY, Constants.NBT.TAG_LONG)) {
            return Math.max(
                0L,
                stack.getTagCompound()
                    .getLong(DISPLAY_AMOUNT_KEY));
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null) {
            return Math.max(0L, fluid.amount);
        }
        return Math.max(0L, stack.stackSize);
    }

    public static ItemStack withDisplayAmount(ItemStack stack, long amount) {
        if (stack == null) {
            return null;
        }
        ItemStack copy = stack.copy();
        long clamped = Math.max(1L, amount);
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(copy);
        if (fluid != null && fluid.getFluid() != null) {
            fluid.amount = (int) Math.min(Integer.MAX_VALUE, clamped);
            ItemStack display = GTUtility.getFluidDisplayStack(fluid, true);
            copy = display == null ? copy : display;
            copy.stackSize = 1;
        } else {
            copy.stackSize = (int) Math.min(Integer.MAX_VALUE, clamped);
        }
        if (clamped > Integer.MAX_VALUE || fluid != null) {
            if (!copy.hasTagCompound()) {
                copy.setTagCompound(new NBTTagCompound());
            }
            copy.getTagCompound()
                .setLong(DISPLAY_AMOUNT_KEY, clamped);
        } else if (copy.hasTagCompound()) {
            copy.getTagCompound()
                .removeTag(DISPLAY_AMOUNT_KEY);
        }
        return copy;
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
