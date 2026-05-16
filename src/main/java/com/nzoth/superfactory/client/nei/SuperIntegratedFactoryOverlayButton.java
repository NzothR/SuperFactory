package com.nzoth.superfactory.client.nei;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.modularui.api.forge.ItemStackHandler;
import com.nzoth.superfactory.client.ui.GuiSuperIntegratedFactoryProcess;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;
import com.nzoth.superfactory.common.network.MessageSetProcessNodeRecipe;
import com.nzoth.superfactory.common.network.NetworkLoader;
import com.nzoth.superfactory.common.process.ProcessNode;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiRecipeButton;
import codechicken.nei.recipe.RecipeCatalysts;
import codechicken.nei.recipe.RecipeHandlerRef;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.ItemMachines;
import gregtech.nei.GTNEIDefaultHandler;
import tectech.recipe.EyeOfHarmonyRecipe;
import tectech.util.FluidStackLong;
import tectech.util.ItemStackLong;

public final class SuperIntegratedFactoryOverlayButton extends GuiOverlayButton {

    public SuperIntegratedFactoryOverlayButton(GuiContainer firstGui, RecipeHandlerRef handlerRef, int xPosition,
        int yPosition) {
        super(firstGui, handlerRef, xPosition, yPosition);
        this.enabled = true;
    }

    public SuperIntegratedFactoryOverlayButton(GuiOverlayButton button) {
        this(button.firstGui, button.handlerRef, button.xPosition, button.yPosition);
    }

    @Override
    public boolean canFillCraftingGrid() {
        return true;
    }

    @Override
    public boolean hasOverlay() {
        return true;
    }

    @Override
    public void setRequireShiftForOverlayRecipe(boolean require) {
        super.setRequireShiftForOverlayRecipe(false);
        this.enabled = true;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        MTESuperIntegratedFactory factory = MTESuperIntegratedFactory.getClientEditingFactory();
        if (factory != null && handlerRef.handler instanceof GTNEIDefaultHandler handler) {
            if (handler.getRecipeMap() != null && isUnsupportedRecipeMapName(handler.getRecipeMap().unlocalizedName)) {
                Minecraft.getMinecraft()
                    .displayGuiScreen(firstGui);
                return;
            }
            if (!(factory.getActiveProcessGui() instanceof GuiSuperIntegratedFactoryProcess processGui)
                || !processGui.canAcceptExternalRecipeFill()) {
                return;
            }
            int nodeId = factory.getSelectedProcessNodeId();
            NBTTagCompound recipeTag = buildRecipeTag(handler, handlerRef.recipeIndex);
            factory.applyRecipeToNode(nodeId, recipeTag);
            processGui.closeCandidateSelectorAfterExternalApply(nodeId);
            NetworkLoader.INSTANCE
                .sendToServer(new MessageSetProcessNodeRecipe(factory.getBaseMetaTileEntity(), nodeId, recipeTag));
            Minecraft.getMinecraft()
                .displayGuiScreen(firstGui);
            return;
        }
        super.mouseReleased(mouseX, mouseY);
    }

    private static boolean isUnsupportedRecipeMapName(String recipeMapName) {
        return false;
    }

    public static void updateRecipeButtons(GuiRecipe<?> guiRecipe, List<GuiRecipeButton> buttonList) {
        for (int i = 0; i < buttonList.size(); i++) {
            if (buttonList.get(i) instanceof GuiOverlayButton button
                && !(button instanceof SuperIntegratedFactoryOverlayButton)) {
                buttonList.set(i, new SuperIntegratedFactoryOverlayButton(button));
            }
        }
    }

    private static NBTTagCompound buildRecipeTag(GTNEIDefaultHandler handler, int recipeIndex) {
        GTNEIDefaultHandler.CachedDefaultRecipe cached = (GTNEIDefaultHandler.CachedDefaultRecipe) handler.arecipes
            .get(recipeIndex);
        ItemStackHandler inputHandler = new ItemStackHandler(ProcessNode.INPUT_SLOTS);
        ItemStackHandler outputHandler = new ItemStackHandler(ProcessNode.OUTPUT_SLOTS);
        ItemStackHandler nonConsumableHandler = new ItemStackHandler(ProcessNode.NON_CONSUMABLE_SLOTS);
        fillConsumableInputsFromPositionedStacks(
            inputHandler,
            nonConsumableHandler,
            cached.mInputs,
            cached.mRecipe.mInputs,
            cached.mRecipe.mFakeRecipe);
        fillHandlerWithFluids(inputHandler, effectiveFluidInputs(cached.mRecipe));
        ItemStack[] itemOutputs = effectiveItemOutputs(cached.mRecipe);
        FluidStack[] fluidOutputs = effectiveFluidOutputs(cached.mRecipe);
        fillHandler(outputHandler, itemOutputs);
        fillHandlerWithFluids(outputHandler, fluidOutputs);
        fillNonConsumables(nonConsumableHandler, cached, cached.mRecipe.mFakeRecipe);
        NBTTagList inputVariants = buildInputVariantsTag(
            cached.mInputs,
            cached.mRecipe.mInputs,
            cached.mRecipe.mFakeRecipe);
        int[] outputChances = buildOutputChances(outputHandler, cached.mOutputs, cached.mRecipe, itemOutputs);
        int duration = effectiveDuration(cached.mRecipe);
        long euPerTick = effectiveEuPerTick(cached.mRecipe, duration);

        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Inputs", inputHandler.serializeNBT());
        tag.setTag("InputVariants", inputVariants);
        tag.setTag("Outputs", outputHandler.serializeNBT());
        tag.setIntArray("OutputChances", outputChances);
        tag.setTag("NonConsumables", nonConsumableHandler.serializeNBT());
        tag.setInteger("DurationTicks", duration);
        tag.setLong("EUt", euPerTick);
        tag.setString(
            "RecipeHandlerName",
            cached.mRecipe.mFakeRecipe
                ? net.minecraft.util.StatCollector.translateToLocal(ProcessNode.FAKE_RECIPE_PROXY_HOST + ".name")
                : handler.getRecipeName());
        tag.setString(
            "RecipeMapName",
            cached.mRecipe.mFakeRecipe ? ProcessNode.FAKE_RECIPE_PROXY_HOST : handler.getRecipeMap().unlocalizedName);
        tag.setBoolean("FakeRecipeSnapshot", cached.mRecipe.mFakeRecipe);
        tag.setString(
            "RecipeFingerprint",
            buildRecipeFingerprint(
                inputHandler,
                outputHandler,
                nonConsumableHandler,
                outputChances,
                inputVariants,
                duration,
                euPerTick,
                cached.mRecipe.mFakeRecipe));
        if (cached.mRecipe.mFakeRecipe) {
            tag.setTag("VirtualRecipeSnapshot", tag.copy());
        }

        List<PositionedStack> catalysts = RecipeCatalysts.getRecipeCatalysts(handler);
        if (!cached.mRecipe.mFakeRecipe && !catalysts.isEmpty() && catalysts.get(0).item != null) {
            tag.setTag("Machine", catalysts.get(0).item.writeToNBT(new NBTTagCompound()));
        }
        return tag;
    }

    private static int fillHandlerFromPositionedStacks(ItemStackHandler handler, List<PositionedStack> stacks,
        int slot) {
        for (PositionedStack positionedStack : stacks) {
            if (slot >= handler.getSlots()) {
                return slot;
            }
            if (positionedStack == null || positionedStack.item == null) {
                continue;
            }
            handler.setStackInSlot(slot++, positionedStack.item.copy());
        }
        return slot;
    }

    private static void fillConsumableInputsFromPositionedStacks(ItemStackHandler handler, List<PositionedStack> stacks,
        ItemStack[] recipeInputs) {
        fillConsumableInputsFromPositionedStacks(handler, null, stacks, recipeInputs, false);
    }

    private static void fillConsumableInputsFromPositionedStacks(ItemStackHandler handler,
        ItemStackHandler nonConsumables, List<PositionedStack> stacks, ItemStack[] recipeInputs, boolean fakeRecipe) {
        int slot = firstEmptySlot(handler);
        for (PositionedStack positionedStack : stacks) {
            if (slot >= handler.getSlots()) {
                return;
            }
            if (positionedStack == null || positionedStack.item == null
                || isFluidDisplay(positionedStack.item)
                || isLikelyNonConsumable(positionedStack.item, recipeInputs)
                || fakeRecipe && isFakeRecipeNonConsumable(positionedStack, recipeInputs)) {
                if (fakeRecipe && nonConsumables != null
                    && positionedStack != null
                    && positionedStack.item != null
                    && !isFluidDisplay(positionedStack.item)
                    && isFakeRecipeNonConsumable(positionedStack, recipeInputs)) {
                    addUniqueStack(nonConsumables, positionedStack.item);
                }
                continue;
            }
            ItemStack copy = positionedStack.item.copy();
            if (copy.stackSize > 0) {
                handler.setStackInSlot(slot++, copy);
            }
        }
    }

    private static void fillHandler(ItemStackHandler handler, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        int slot = firstEmptySlot(handler);
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0 && slot < handler.getSlots()) {
                handler
                    .setStackInSlot(slot++, ProcessNode.withDisplayAmount(stack, ProcessNode.getDisplayAmount(stack)));
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
                    handler.setStackInSlot(slot++, ProcessNode.withDisplayAmount(display, Math.max(1L, stack.amount)));
                }
            }
        }
    }

    private static EyeOfHarmonyRecipe getEyeOfHarmonyRecipe(GTRecipe recipe) {
        return recipe != null && recipe.mSpecialItems instanceof EyeOfHarmonyRecipe
            ? (EyeOfHarmonyRecipe) recipe.mSpecialItems
            : null;
    }

    private static ItemStack[] effectiveItemOutputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = getEyeOfHarmonyRecipe(recipe);
        if (eyeRecipe == null) {
            return recipe == null || recipe.mOutputs == null ? new ItemStack[0] : recipe.mOutputs;
        }
        List<ItemStack> outputs = new java.util.ArrayList<>();
        for (ItemStackLong stackLong : eyeRecipe.getOutputItems()) {
            if (stackLong != null && stackLong.itemStack != null && stackLong.stackSize > 0L) {
                outputs.add(ProcessNode.withDisplayAmount(stackLong.itemStack, stackLong.stackSize));
            }
        }
        return outputs.toArray(new ItemStack[0]);
    }

    private static FluidStack[] effectiveFluidOutputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = getEyeOfHarmonyRecipe(recipe);
        if (eyeRecipe == null) {
            return recipe == null || recipe.mFluidOutputs == null ? new FluidStack[0] : recipe.mFluidOutputs;
        }
        List<FluidStack> outputs = new java.util.ArrayList<>();
        for (FluidStackLong stackLong : eyeRecipe.getOutputFluids()) {
            if (stackLong != null && stackLong.fluidStack != null && stackLong.amount > 0L) {
                FluidStack copy = stackLong.fluidStack.copy();
                copy.amount = (int) Math.min(Integer.MAX_VALUE, stackLong.amount);
                outputs.add(copy);
            }
        }
        return outputs.toArray(new FluidStack[0]);
    }

    private static FluidStack[] effectiveFluidInputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = getEyeOfHarmonyRecipe(recipe);
        if (eyeRecipe == null) {
            return recipe == null || recipe.mFluidInputs == null ? new FluidStack[0] : recipe.mFluidInputs;
        }
        List<FluidStack> inputs = new java.util.ArrayList<>();
        addEyeFluidInput(inputs, Materials.Hydrogen.getGas(1), eyeRecipe.getHydrogenRequirement());
        addEyeFluidInput(inputs, Materials.Helium.getGas(1), eyeRecipe.getHeliumRequirement());
        return inputs.toArray(new FluidStack[0]);
    }

    private static void addEyeFluidInput(List<FluidStack> inputs, FluidStack template, long amount) {
        if (template == null || template.getFluid() == null || amount <= 0L) {
            return;
        }
        FluidStack copy = template.copy();
        copy.amount = (int) Math.min(Integer.MAX_VALUE, amount);
        inputs.add(copy);
    }

    private static int effectiveDuration(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = getEyeOfHarmonyRecipe(recipe);
        long duration = eyeRecipe == null ? recipe == null ? 0L : recipe.mDuration : eyeRecipe.getRecipeTimeInTicks();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, duration));
    }

    private static long effectiveEuPerTick(GTRecipe recipe, int durationTicks) {
        EyeOfHarmonyRecipe eyeRecipe = getEyeOfHarmonyRecipe(recipe);
        if (eyeRecipe == null) {
            return Math.max(0L, recipe == null ? 0L : recipe.mEUt);
        }
        long duration = Math.max(1L, durationTicks);
        long total = effectiveEyeInputEu(eyeRecipe);
        return total / duration + (total % duration == 0L ? 0L : 1L);
    }

    private static long effectiveEyeInputEu(EyeOfHarmonyRecipe eyeRecipe) {
        long total = Math.max(0L, eyeRecipe.getEUStartCost());
        double efficiency = eyeRecipe.getRecipeEnergyEfficiency();
        if (efficiency > 0.0D) {
            double fromOutput = Math.ceil(Math.max(0L, eyeRecipe.getEUOutput()) / efficiency);
            total = Math.max(total, fromOutput >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) fromOutput);
        }
        return total;
    }

    private static void fillNonConsumables(ItemStackHandler handler, GTNEIDefaultHandler.CachedDefaultRecipe cached) {
        fillNonConsumables(handler, cached, false);
    }

    private static void fillNonConsumables(ItemStackHandler handler, GTNEIDefaultHandler.CachedDefaultRecipe cached,
        boolean fakeRecipe) {
        Object specialItems = cached.mRecipe.mSpecialItems;
        if (specialItems instanceof ItemStack stack) {
            addUniqueStack(handler, stack);
        }
        if (specialItems instanceof ItemStack[]stacks) {
            fillHandler(handler, stacks);
        }
        fillZeroAmountInputs(handler, cached.mRecipe.mInputs);
        fillNonConsumablesFromPositionedStacks(handler, cached.mInputs, cached.mRecipe.mInputs, fakeRecipe);
    }

    private static ItemStack[] consumableInputs(ItemStack[] stacks) {
        if (stacks == null) {
            return new ItemStack[0];
        }
        List<ItemStack> consumables = new java.util.ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0) {
                consumables.add(stack);
            }
        }
        return consumables.toArray(new ItemStack[0]);
    }

    private static void fillZeroAmountInputs(ItemStackHandler handler, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize <= 0) {
                ItemStack copy = stack.copy();
                copy.stackSize = Math.max(1, copy.stackSize);
                addUniqueStack(handler, copy);
            }
        }
    }

    private static int firstEmptySlot(ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i) == null) {
                return i;
            }
        }
        return handler.getSlots();
    }

    private static int fillHandlerFromPositionedStacks(ItemStackHandler handler, ItemStackHandler nonConsumables,
        List<PositionedStack> stacks, ItemStack[] recipeInputs, int slot) {
        int nonConsumableSlot = 0;
        for (PositionedStack positionedStack : stacks) {
            if (positionedStack == null || positionedStack.item == null) {
                continue;
            }
            if (isLikelyNonConsumable(positionedStack.item, recipeInputs)) {
                if (nonConsumableSlot < nonConsumables.getSlots()) {
                    nonConsumables.setStackInSlot(nonConsumableSlot++, positionedStack.item.copy());
                }
                continue;
            }
            if (slot >= handler.getSlots()) {
                return slot;
            }
            handler.setStackInSlot(slot++, positionedStack.item.copy());
        }
        return slot;
    }

    private static NBTTagList buildInputVariantsTag(List<PositionedStack> stacks, ItemStack[] recipeInputs) {
        return buildInputVariantsTag(stacks, recipeInputs, false);
    }

    private static NBTTagList buildInputVariantsTag(List<PositionedStack> stacks, ItemStack[] recipeInputs,
        boolean fakeRecipe) {
        NBTTagList list = new NBTTagList();
        if (stacks == null) {
            return list;
        }
        int slot = 0;
        for (PositionedStack positionedStack : stacks) {
            if (positionedStack == null || positionedStack.item == null) {
                continue;
            }
            if (isFluidDisplay(positionedStack.item) || isLikelyNonConsumable(positionedStack.item, recipeInputs)
                || fakeRecipe && isFakeRecipeNonConsumable(positionedStack, recipeInputs)) {
                continue;
            }
            int inputSlot = slot++;
            if (!hasMeaningfulVariants(positionedStack)) {
                continue;
            }
            NBTTagCompound variantTag = new NBTTagCompound();
            variantTag.setInteger("Slot", inputSlot);
            variantTag.setInteger("SelectedIndex", selectedIndexOf(positionedStack));
            NBTTagList variants = new NBTTagList();
            if (positionedStack.items != null) {
                for (ItemStack variant : positionedStack.items) {
                    if (variant != null) {
                        NBTTagCompound stackTag = new NBTTagCompound();
                        variant.writeToNBT(stackTag);
                        variants.appendTag(stackTag);
                    }
                }
            }
            variantTag.setTag("Variants", variants);
            list.appendTag(variantTag);
        }
        return list;
    }

    private static boolean hasMeaningfulVariants(PositionedStack positionedStack) {
        if (positionedStack == null || positionedStack.items == null || positionedStack.items.length <= 1) {
            return false;
        }
        ItemStack first = null;
        for (ItemStack variant : positionedStack.items) {
            if (variant == null) {
                continue;
            }
            if (first == null) {
                first = variant;
                continue;
            }
            if (!GTUtility.areStacksEqual(first, variant, true)) {
                return true;
            }
        }
        return false;
    }

    private static int selectedIndexOf(PositionedStack positionedStack) {
        if (positionedStack == null || positionedStack.items == null || positionedStack.item == null) {
            return 0;
        }
        for (int i = 0; i < positionedStack.items.length; i++) {
            if (positionedStack.items[i] != null
                && GTUtility.areStacksEqual(positionedStack.items[i], positionedStack.item, true)) {
                return i;
            }
        }
        return 0;
    }

    private static int[] buildOutputChances(ItemStackHandler outputs, List<PositionedStack> neiOutputs, GTRecipe recipe,
        ItemStack[] itemOutputs) {
        int[] chances = new int[ProcessNode.OUTPUT_SLOTS];
        java.util.Arrays.fill(chances, 10000);
        if (getEyeOfHarmonyRecipe(recipe) != null || recipe == null) {
            return chances;
        }
        boolean[] usedSlots = new boolean[outputs.getSlots()];
        for (int recipeOutput = 0; recipeOutput < itemOutputs.length; recipeOutput++) {
            ItemStack stack = itemOutputs[recipeOutput];
            if (stack == null) {
                continue;
            }
            int slot = findMatchingOutputSlot(outputs, stack, usedSlots);
            if (slot >= 0) {
                chances[slot] = normalizeRecipeChance(outputChanceFromNei(neiOutputs, recipeOutput, recipe));
                usedSlots[slot] = true;
            }
        }
        return chances;
    }

    private static int outputChanceFromNei(List<PositionedStack> neiOutputs, int outputIndex, GTRecipe recipe) {
        if (neiOutputs != null && outputIndex >= 0 && outputIndex < neiOutputs.size()) {
            Integer chance = positionedStackChance(neiOutputs.get(outputIndex));
            if (chance != null && chance > 0) {
                return chance;
            }
        }
        return recipe.getOutputChance(outputIndex);
    }

    private static Integer positionedStackChance(PositionedStack positionedStack) {
        if (positionedStack == null) {
            return null;
        }
        try {
            Field field = positionedStack.getClass()
                .getDeclaredField("mChance");
            field.setAccessible(true);
            return field.getInt(positionedStack);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static int normalizeRecipeChance(int chance) {
        return chance <= 0 ? 10000 : Math.max(0, Math.min(10000, chance));
    }

    private static int findMatchingOutputSlot(ItemStackHandler outputs, ItemStack stack) {
        return findMatchingOutputSlot(outputs, stack, null);
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

    private static String buildRecipeFingerprint(ItemStackHandler inputs, ItemStackHandler outputs,
        ItemStackHandler nonConsumables, int[] outputChances, NBTTagList inputVariants, int duration, long euPerTick,
        boolean fakeRecipe) {
        ProcessNode node = new ProcessNode(0, 0, 0);
        node.fakeRecipeSnapshot = fakeRecipe;
        node.inputHandler.deserializeNBT(inputs.serializeNBT());
        node.outputHandler.deserializeNBT(outputs.serializeNBT());
        node.nonConsumableHandler.deserializeNBT(nonConsumables.serializeNBT());
        node.durationTicks = duration;
        node.euPerTick = euPerTick;
        node.baseDurationTicks = duration;
        node.baseEuPerTick = euPerTick;
        if (outputChances != null) {
            for (int i = 0; i < outputChances.length && i < ProcessNode.OUTPUT_SLOTS; i++) {
                node.setOutputChance(i, outputChances[i]);
            }
        }
        for (int i = 0; i < inputVariants.tagCount(); i++) {
            NBTTagCompound variantTag = inputVariants.getCompoundTagAt(i);
            int slot = Math.max(0, Math.min(ProcessNode.INPUT_SLOTS - 1, variantTag.getInteger("Slot")));
            node.inputVariants[slot].readFromNBT(variantTag);
        }
        return node.buildRecipeFingerprint();
    }

    private static void fillNonConsumablesFromPositionedStacks(ItemStackHandler handler, List<PositionedStack> stacks,
        ItemStack[] recipeInputs) {
        fillNonConsumablesFromPositionedStacks(handler, stacks, recipeInputs, false);
    }

    private static void fillNonConsumablesFromPositionedStacks(ItemStackHandler handler, List<PositionedStack> stacks,
        ItemStack[] recipeInputs, boolean fakeRecipe) {
        for (PositionedStack positionedStack : stacks) {
            if (positionedStack == null || positionedStack.item == null) {
                continue;
            }
            if (isLikelyNonConsumable(positionedStack.item, recipeInputs)
                || fakeRecipe && isFakeRecipeNonConsumable(positionedStack, recipeInputs)) {
                addUniqueStack(handler, positionedStack.item);
            }
        }
    }

    private static boolean isFakeRecipeNonConsumable(PositionedStack positionedStack, ItemStack[] recipeInputs) {
        if (positionedStack == null || positionedStack.item == null || isFluidDisplay(positionedStack.item)) {
            return false;
        }
        if (isMachineStack(positionedStack.item)) {
            return true;
        }
        return positionedStack.rely < 18 && matchesAnyInputType(positionedStack.item, recipeInputs);
    }

    private static boolean isMachineStack(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMachines;
    }

    private static void addUniqueStack(ItemStackHandler handler, ItemStack stack) {
        if (stack == null) {
            return;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack existing = handler.getStackInSlot(i);
            if (existing != null && matchesConsumableType(existing, stack)) {
                return;
            }
        }
        int slot = firstEmptySlot(handler);
        if (slot < handler.getSlots()) {
            handler.setStackInSlot(slot, stack.copy());
        }
    }

    private static boolean isLikelyNonConsumable(ItemStack stack, ItemStack[] recipeInputs) {
        if (stack == null || isFluidDisplay(stack)) {
            return false;
        }
        if (recipeInputs == null) {
            return false;
        }
        for (ItemStack input : recipeInputs) {
            if (input != null && input.stackSize > 0 && matchesConsumableType(input, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAnyInputType(ItemStack stack, ItemStack[] recipeInputs) {
        if (stack == null || recipeInputs == null) {
            return false;
        }
        for (ItemStack input : recipeInputs) {
            if (input != null && matchesConsumableType(input, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFluidDisplay(ItemStack stack) {
        return stack != null && stack.getItem() == ItemList.Display_Fluid.getItem();
    }

    private static boolean matchesConsumableType(ItemStack input, ItemStack stack) {
        return GTUtility.areStacksEqual(input, stack, false) || GTOreDictUnificator.isInputStackEqual(input, stack)
            || GTOreDictUnificator.isInputStackEqual(stack, input);
    }

}
