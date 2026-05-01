package com.nzoth.superfactory.common.recipe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.enums.GTValues;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;

/**
 * Input discovery, recipe matching, and consumption-plan helpers for proxy-style multiblocks.
 *
 * <p>
 * The machine owns the hatch inventories. This helper only builds views and demand plans so machine-specific rules
 * such as input separation and pattern-slot isolation stay outside the executor.
 */
public final class ProxyRecipeInputHandler {

    private ProxyRecipeInputHandler() {}

    public static List<ProxyRecipeInputGroup> collectInputGroups(List<MTEHatchInputBus> inputBusses,
        List<MTEHatchInput> inputHatches, boolean inputSeparationEnabled) {
        if (inputSeparationEnabled) {
            return collectSeparatedInputGroups(inputBusses, inputHatches);
        }

        short hatchColors = getHatchColors(inputBusses, inputHatches);
        boolean doColorChecking = hatchColors != 0;
        if (!doColorChecking) {
            hatchColors = 0b1;
        }

        ArrayList<ProxyRecipeInputGroup> groups = new ArrayList<>();
        for (byte color = 0; color < (doColorChecking ? 16 : 1); color++) {
            if (isColorAbsent(hatchColors, color)) {
                continue;
            }
            List<FluidStack> fluidInputs = collectLiveFluidInputs(inputHatches, doColorChecking, color);
            groups.add(createGroup(color, collectLiveItemInputs(inputBusses, doColorChecking, color), fluidInputs));
        }
        return groups;
    }

    public static short getHatchColors(List<MTEHatchInputBus> inputBusses, List<MTEHatchInput> inputHatches) {
        short hatchColors = 0;
        for (MTEHatchInputBus bus : inputBusses) {
            if (bus == null || !bus.isValid()) {
                continue;
            }
            byte color = bus.getColor();
            if (color >= 0 && color < 16) {
                hatchColors |= (short) (1 << color);
            }
        }
        for (MTEHatchInput hatch : inputHatches) {
            if (hatch == null || !hatch.isValid()) {
                continue;
            }
            byte color = hatch.getColor();
            if (color >= 0 && color < 16) {
                hatchColors |= (short) (1 << color);
            }
        }
        return hatchColors;
    }

    public static int computeInputBoundParallel(GTRecipe recipe, int maxParallel, ItemStack[] itemInputs,
        FluidStack[] fluidInputs) {
        if (recipe == null || maxParallel <= 0) {
            return 0;
        }
        if (!matchesRecipeInputs(recipe, itemInputs, fluidInputs)) {
            return 0;
        }
        return computeConsumableInputBoundParallel(recipe, maxParallel, itemInputs, fluidInputs);
    }

    /**
     * Computes the parallel bound from real consumable inputs only.
     *
     * <p>
     * Callers that already matched a recipe with a richer query view can use this to avoid treating non-consumed
     * markers, programmed circuits, or pattern metadata as material costs.
     */
    public static int computeConsumableInputBoundParallel(GTRecipe recipe, int maxParallel, ItemStack[] itemInputs,
        FluidStack[] fluidInputs) {
        if (recipe == null || maxParallel <= 0) {
            return 0;
        }
        if (!hasConsumableInputs(recipe)) {
            return maxParallel;
        }
        long itemBound = computeItemParallelBound(recipe, itemInputs);
        long fluidBound = computeFluidParallelBound(recipe, fluidInputs);
        long inputBound = Math.min(itemBound, fluidBound);
        if (inputBound <= 0L) {
            return 0;
        }
        return (int) Math.min(maxParallel, Math.min(Integer.MAX_VALUE, inputBound));
    }

    public static boolean matchesRecipeInputs(GTRecipe recipe, ItemStack[] itemInputs, FluidStack[] fluidInputs) {
        if (recipe == null) {
            return false;
        }
        ItemStack[] safeItems = itemInputs == null ? GTValues.emptyItemStackArray : itemInputs;
        FluidStack[] safeFluids = fluidInputs == null ? GTValues.emptyFluidStackArray : fluidInputs;
        return recipe.isRecipeInputEqual(false, true, 1, safeFluids, safeItems);
    }

    public static boolean hasConsumableInputs(GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        if (recipe.mInputs != null) {
            for (ItemStack input : recipe.mInputs) {
                if (input != null && input.stackSize > 0 && !isNonConsumableMarker(input)) {
                    return true;
                }
            }
        }
        if (recipe.mFluidInputs != null) {
            for (FluidStack input : recipe.mFluidInputs) {
                if (input != null && input.amount > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public static ProxyRecipeConsumptionPlan buildConsumptionPlan(GTRecipe recipe, int parallel) {
        ArrayList<ProxyRecipeConsumptionPlan.ItemDemand> itemDemands = new ArrayList<>();
        for (ItemDemand demand : buildItemDemands(recipe)) {
            long amount = safeMultiply(demand.amountPerCraft, parallel);
            if (amount > 0L) {
                itemDemands
                    .add(new ProxyRecipeConsumptionPlan.ItemDemand(GTUtility.copyOrNull(demand.template), amount));
            }
        }

        ArrayList<ProxyRecipeConsumptionPlan.FluidDemand> fluidDemands = new ArrayList<>();
        for (FluidDemand demand : buildFluidDemands(recipe)) {
            long amount = safeMultiply(demand.amountPerCraft, parallel);
            if (amount > 0L) {
                fluidDemands.add(new ProxyRecipeConsumptionPlan.FluidDemand(demand.template.copy(), amount));
            }
        }

        return new ProxyRecipeConsumptionPlan(
            recipe,
            parallel,
            itemDemands.toArray(new ProxyRecipeConsumptionPlan.ItemDemand[0]),
            fluidDemands.toArray(new ProxyRecipeConsumptionPlan.FluidDemand[0]));
    }

    public static boolean canSatisfyConsumptionPlan(ProxyRecipeConsumptionPlan plan, ItemStack[] itemInputs,
        FluidStack[] fluidInputs) {
        if (plan == null || plan.parallel <= 0) {
            return false;
        }
        return canSatisfyItemDemands(plan, itemInputs) && canSatisfyFluidDemands(plan, fluidInputs);
    }

    public static boolean matchesItemDemand(ProxyRecipeConsumptionPlan plan,
        ProxyRecipeConsumptionPlan.ItemDemand demand, ItemStack available) {
        return plan != null && demand != null
            && available != null
            && matchesRecipeInput(plan.recipe, demand.template, available);
    }

    public static boolean matchesFluidDemand(ProxyRecipeConsumptionPlan.FluidDemand demand, FluidStack available) {
        return demand != null && available != null && GTUtility.areFluidsEqual(available, demand.template);
    }

    public static boolean sharesAnyInput(GTRecipe recipe, ItemStack[] liveItems, FluidStack[] liveFluids) {
        if (recipe == null) {
            return false;
        }
        if (recipe.mInputs != null) {
            for (ItemStack required : recipe.mInputs) {
                if (required == null || required.stackSize <= 0 || isNonConsumableMarker(required)) {
                    continue;
                }
                for (ItemStack liveItem : liveItems) {
                    if (liveItem != null && liveItem.stackSize > 0 && matchesRecipeInput(recipe, required, liveItem)) {
                        return true;
                    }
                }
            }
        }
        if (recipe.mFluidInputs != null) {
            for (FluidStack required : recipe.mFluidInputs) {
                if (required == null || required.amount <= 0) {
                    continue;
                }
                for (FluidStack liveFluid : liveFluids) {
                    if (liveFluid != null && liveFluid.amount > 0 && GTUtility.areFluidsEqual(required, liveFluid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static long computeItemParallelBound(GTRecipe recipe, ItemStack[] itemInputs) {
        List<ItemDemand> demands = buildItemDemands(recipe);
        if (demands.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long[] availableCounts = new long[demands.size()];
        for (ItemStack available : itemInputs) {
            if (available == null || available.stackSize <= 0) {
                continue;
            }
            for (int i = 0; i < demands.size(); i++) {
                if (matchesRecipeInput(recipe, demands.get(i).template, available)) {
                    availableCounts[i] += available.stackSize;
                    break;
                }
            }
        }
        long bound = Long.MAX_VALUE;
        for (int i = 0; i < demands.size(); i++) {
            ItemDemand demand = demands.get(i);
            if (demand.amountPerCraft <= 0L) {
                continue;
            }
            bound = Math.min(bound, availableCounts[i] / demand.amountPerCraft);
        }
        return bound == Long.MAX_VALUE ? 0L : bound;
    }

    public static long computeFluidParallelBound(GTRecipe recipe, FluidStack[] fluidInputs) {
        List<FluidDemand> demands = buildFluidDemands(recipe);
        if (demands.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long[] availableCounts = new long[demands.size()];
        for (FluidStack available : fluidInputs) {
            if (available == null || available.amount <= 0) {
                continue;
            }
            for (int i = 0; i < demands.size(); i++) {
                if (GTUtility.areFluidsEqual(available, demands.get(i).template)) {
                    availableCounts[i] += available.amount;
                    break;
                }
            }
        }
        long bound = Long.MAX_VALUE;
        for (int i = 0; i < demands.size(); i++) {
            FluidDemand demand = demands.get(i);
            if (demand.amountPerCraft <= 0L) {
                continue;
            }
            bound = Math.min(bound, availableCounts[i] / demand.amountPerCraft);
        }
        return bound == Long.MAX_VALUE ? 0L : bound;
    }

    public static String formatParallelBound(long bound) {
        return bound == Long.MAX_VALUE ? "\u65e0\u9650" : String.valueOf(bound);
    }

    private static List<ProxyRecipeInputGroup> collectSeparatedInputGroups(List<MTEHatchInputBus> inputBusses,
        List<MTEHatchInput> inputHatches) {
        ArrayList<ProxyRecipeInputGroup> groups = new ArrayList<>();
        collectUncoloredSeparatedGroups(inputBusses, inputHatches, groups);

        short hatchColors = getHatchColors(inputBusses, inputHatches);
        for (byte color = 0; color < 16; color++) {
            if (isColorAbsent(hatchColors, color)) {
                continue;
            }
            collectSeparatedGroups(inputBusses, groups, collectLiveFluidInputsExact(inputHatches, color), color);
        }
        return groups;
    }

    private static void collectUncoloredSeparatedGroups(List<MTEHatchInputBus> inputBusses,
        List<MTEHatchInput> inputHatches, List<ProxyRecipeInputGroup> groups) {
        for (MTEHatchInputBus bus : inputBusses) {
            if (bus == null || !bus.isValid() || bus.getColor() != -1) {
                continue;
            }
            groups.add(createGroup((byte) -1, collectLiveItemsFromBus(bus), Collections.emptyList()));
        }
        for (MTEHatchInput hatch : inputHatches) {
            if (hatch == null || !hatch.isValid() || hatch.getColor() != -1) {
                continue;
            }
            FluidStack fluid = hatch.getFluid();
            if (fluid != null && fluid.amount > 0) {
                groups.add(createGroup((byte) -1, Collections.emptyList(), Collections.singletonList(fluid)));
            }
        }
    }

    private static void collectSeparatedGroups(List<MTEHatchInputBus> inputBusses, List<ProxyRecipeInputGroup> groups,
        List<FluidStack> fluidInputs, byte color) {
        if (inputBusses.isEmpty()) {
            groups.add(createGroup(color, Collections.emptyList(), fluidInputs));
            return;
        }
        boolean handledColoredGroup = false;
        boolean addedGroup = false;
        for (MTEHatchInputBus bus : inputBusses) {
            if (bus == null || !bus.isValid()) {
                continue;
            }
            byte busColor = bus.getColor();
            if (busColor != color) {
                continue;
            }
            if (busColor != -1) {
                if (handledColoredGroup) {
                    continue;
                }
                handledColoredGroup = true;
                groups.add(createGroup(color, collectLiveItemInputsExact(inputBusses, color), fluidInputs));
                addedGroup = true;
                continue;
            }
            groups.add(createGroup(color, collectLiveItemsFromBus(bus), fluidInputs));
            addedGroup = true;
        }
        if (!addedGroup && !fluidInputs.isEmpty()) {
            groups.add(createGroup(color, Collections.emptyList(), fluidInputs));
        }
    }

    private static ProxyRecipeInputGroup createGroup(byte color, List<ItemStack> liveItems,
        List<FluidStack> liveFluids) {
        ItemStack[] liveItemArray = normalizeLiveItemRefs(liveItems);
        FluidStack[] liveFluidArray = normalizeLiveFluidRefs(liveFluids);
        return new ProxyRecipeInputGroup(
            color,
            liveItemArray,
            liveFluidArray,
            buildQueryItems(liveItemArray),
            buildQueryFluids(liveFluidArray));
    }

    private static List<ItemStack> collectLiveItemInputs(List<MTEHatchInputBus> inputBusses, boolean doColorChecking,
        byte color) {
        ArrayList<ItemStack> liveItems = new ArrayList<>();
        for (MTEHatchInputBus bus : inputBusses) {
            if (bus == null || !bus.isValid()) {
                continue;
            }
            byte busColor = bus.getColor();
            if (doColorChecking && busColor != -1 && busColor != color) {
                continue;
            }
            liveItems.addAll(collectLiveItemsFromBus(bus));
        }
        return liveItems;
    }

    private static List<ItemStack> collectLiveItemInputsExact(List<MTEHatchInputBus> inputBusses, byte color) {
        ArrayList<ItemStack> liveItems = new ArrayList<>();
        for (MTEHatchInputBus bus : inputBusses) {
            if (bus == null || !bus.isValid() || bus.getColor() != color) {
                continue;
            }
            liveItems.addAll(collectLiveItemsFromBus(bus));
        }
        return liveItems;
    }

    private static List<ItemStack> collectLiveItemsFromBus(MTEHatchInputBus bus) {
        ArrayList<ItemStack> liveItems = new ArrayList<>();
        if (bus == null || !bus.isValid()) {
            return liveItems;
        }
        int circuitSlot = bus.getCircuitSlot();
        for (int i = bus.getSizeInventory() - 1; i >= 0; i--) {
            if (i == circuitSlot) {
                continue;
            }
            ItemStack stored = bus.getStackInSlot(i);
            if (stored != null && stored.stackSize > 0) {
                liveItems.add(stored);
            }
        }
        ItemStack circuit = getConfiguredCircuit(bus);
        if (circuit != null) {
            liveItems.add(circuit);
        }
        return liveItems;
    }

    private static ItemStack getConfiguredCircuit(MTEHatchInputBus bus) {
        if (bus == null || !bus.allowSelectCircuit()) {
            return null;
        }
        int circuitSlot = bus.getCircuitSlot();
        if (circuitSlot < 0 || circuitSlot >= bus.getSizeInventory()) {
            return null;
        }
        ItemStack circuit = bus.getStackInSlot(circuitSlot);
        if (circuit == null || !isNonConsumableMarker(circuit)) {
            return null;
        }
        ItemStack copy = GTUtility.copyOrNull(circuit);
        if (copy != null) {
            copy.stackSize = 1;
        }
        return copy;
    }

    private static List<FluidStack> collectLiveFluidInputs(List<MTEHatchInput> inputHatches, boolean doColorChecking,
        byte color) {
        ArrayList<FluidStack> liveFluids = new ArrayList<>();
        for (MTEHatchInput hatch : inputHatches) {
            if (hatch == null || !hatch.isValid()) {
                continue;
            }
            byte hatchColor = hatch.getColor();
            if (doColorChecking && hatchColor != -1 && hatchColor != color) {
                continue;
            }
            FluidStack fluid = hatch.getFluid();
            if (fluid != null && fluid.amount > 0) {
                liveFluids.add(fluid);
            }
        }
        return liveFluids;
    }

    private static List<FluidStack> collectLiveFluidInputsExact(List<MTEHatchInput> inputHatches, byte color) {
        ArrayList<FluidStack> liveFluids = new ArrayList<>();
        for (MTEHatchInput hatch : inputHatches) {
            if (hatch == null || !hatch.isValid() || hatch.getColor() != color) {
                continue;
            }
            FluidStack fluid = hatch.getFluid();
            if (fluid != null && fluid.amount > 0) {
                liveFluids.add(fluid);
            }
        }
        return liveFluids;
    }

    private static boolean canSatisfyFluidDemands(ProxyRecipeConsumptionPlan plan, FluidStack[] fluidInputs) {
        if (plan.fluidDemands.length == 0) {
            return true;
        }
        for (ProxyRecipeConsumptionPlan.FluidDemand demand : plan.fluidDemands) {
            long remaining = demand.amount;
            for (FluidStack available : fluidInputs) {
                if (available == null || available.amount <= 0
                    || !GTUtility.areFluidsEqual(available, demand.template)) {
                    continue;
                }
                remaining -= Math.min(remaining, available.amount);
                if (remaining <= 0L) {
                    break;
                }
            }
            if (remaining > 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean canSatisfyItemDemands(ProxyRecipeConsumptionPlan plan, ItemStack[] itemInputs) {
        if (plan.itemDemands.length == 0) {
            return true;
        }
        for (ProxyRecipeConsumptionPlan.ItemDemand demand : plan.itemDemands) {
            long remaining = demand.amount;
            for (ItemStack available : itemInputs) {
                if (available == null || available.stackSize <= 0
                    || !matchesRecipeInput(plan.recipe, demand.template, available)) {
                    continue;
                }
                remaining -= Math.min(remaining, available.stackSize);
                if (remaining <= 0L) {
                    break;
                }
            }
            if (remaining > 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRecipeInput(GTRecipe recipe, ItemStack required, ItemStack available) {
        if (recipe.isNBTSensitive) {
            return GTUtility.areStacksEqual(required, available, false);
        }
        return GTOreDictUnificator.isInputStackEqual(available, required);
    }

    private static List<ItemDemand> buildItemDemands(GTRecipe recipe) {
        ArrayList<ItemDemand> demands = new ArrayList<>();
        if (recipe.mInputs == null) {
            return demands;
        }
        for (ItemStack required : recipe.mInputs) {
            if (required == null || required.stackSize <= 0 || isNonConsumableMarker(required)) {
                continue;
            }
            ItemDemand existing = null;
            for (ItemDemand demand : demands) {
                if (matchesRecipeInput(recipe, demand.template, required)
                    && matchesRecipeInput(recipe, required, demand.template)) {
                    existing = demand;
                    break;
                }
            }
            if (existing == null) {
                demands.add(new ItemDemand(GTUtility.copyOrNull(required), required.stackSize));
            } else {
                existing.amountPerCraft += required.stackSize;
            }
        }
        return demands;
    }

    /**
     * GT programmed circuits select recipe variants but regular machines do not consume them. Input buses store their
     * configured circuit as a zero-sized ghost stack, so this must compare the item directly instead of using
     * ItemList#isStackEqual, which treats stackSize <= 0 as invalid.
     */
    private static boolean isNonConsumableMarker(ItemStack stack) {
        ItemStack integratedCircuit = GTUtility.getIntegratedCircuit(0);
        return stack != null && integratedCircuit != null && stack.getItem() == integratedCircuit.getItem();
    }

    private static List<FluidDemand> buildFluidDemands(GTRecipe recipe) {
        ArrayList<FluidDemand> demands = new ArrayList<>();
        if (recipe.mFluidInputs == null) {
            return demands;
        }
        for (FluidStack required : recipe.mFluidInputs) {
            if (required == null || required.amount <= 0) {
                continue;
            }
            FluidDemand existing = null;
            for (FluidDemand demand : demands) {
                if (GTUtility.areFluidsEqual(demand.template, required)) {
                    existing = demand;
                    break;
                }
            }
            if (existing == null) {
                demands.add(new FluidDemand(required.copy(), required.amount));
            } else {
                existing.amountPerCraft += required.amount;
            }
        }
        return demands;
    }

    private static ItemStack[] normalizeLiveItemRefs(List<ItemStack> liveItems) {
        if (liveItems == null || liveItems.isEmpty()) {
            return GTValues.emptyItemStackArray;
        }
        ArrayList<ItemStack> normalized = new ArrayList<>();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : liveItems) {
            if (stack == null || stack.stackSize <= 0 || !seen.add(stack)) {
                continue;
            }
            normalized.add(stack);
        }
        return normalized.toArray(new ItemStack[0]);
    }

    private static FluidStack[] normalizeLiveFluidRefs(List<FluidStack> liveFluids) {
        if (liveFluids == null || liveFluids.isEmpty()) {
            return GTValues.emptyFluidStackArray;
        }
        ArrayList<FluidStack> normalized = new ArrayList<>();
        Set<FluidStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FluidStack stack : liveFluids) {
            if (stack == null || stack.amount <= 0 || !seen.add(stack)) {
                continue;
            }
            normalized.add(stack);
        }
        return normalized.toArray(new FluidStack[0]);
    }

    private static ItemStack[] buildQueryItems(ItemStack[] liveItemArray) {
        if (liveItemArray == null || liveItemArray.length == 0) {
            return GTValues.emptyItemStackArray;
        }
        ArrayList<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : liveItemArray) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            ItemStack copy = GTUtility.copyOrNull(stack);
            if (copy == null || copy.stackSize <= 0) {
                continue;
            }
            boolean mergedExisting = false;
            for (ItemStack existing : merged) {
                if (GTUtility.areStacksEqual(existing, copy, false)) {
                    existing.stackSize += copy.stackSize;
                    mergedExisting = true;
                    break;
                }
            }
            if (!mergedExisting) {
                merged.add(copy);
            }
        }
        return merged.isEmpty() ? GTValues.emptyItemStackArray : merged.toArray(new ItemStack[0]);
    }

    private static FluidStack[] buildQueryFluids(FluidStack[] liveFluidArray) {
        if (liveFluidArray == null || liveFluidArray.length == 0) {
            return GTValues.emptyFluidStackArray;
        }
        ArrayList<FluidStack> merged = new ArrayList<>();
        for (FluidStack stack : liveFluidArray) {
            if (stack == null || stack.amount <= 0) {
                continue;
            }
            FluidStack copy = stack.copy();
            boolean mergedExisting = false;
            for (FluidStack existing : merged) {
                if (GTUtility.areFluidsEqual(existing, copy)) {
                    existing.amount += copy.amount;
                    mergedExisting = true;
                    break;
                }
            }
            if (!mergedExisting) {
                merged.add(copy);
            }
        }
        return merged.isEmpty() ? GTValues.emptyFluidStackArray : merged.toArray(new FluidStack[0]);
    }

    private static boolean isColorAbsent(short hatchColors, byte color) {
        return (hatchColors & (1 << color)) == 0;
    }

    private static long safeMultiply(long left, int right) {
        return safeMultiply(left, (long) right);
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        BigInteger multiplied = BigInteger.valueOf(left)
            .multiply(BigInteger.valueOf(right));
        return multiplied.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private static final class ItemDemand {

        private final ItemStack template;
        private long amountPerCraft;

        private ItemDemand(ItemStack template, long amountPerCraft) {
            this.template = template;
            this.amountPerCraft = amountPerCraft;
        }
    }

    private static final class FluidDemand {

        private final FluidStack template;
        private long amountPerCraft;

        private FluidDemand(FluidStack template, long amountPerCraft) {
            this.template = template;
            this.amountPerCraft = amountPerCraft;
        }
    }
}
