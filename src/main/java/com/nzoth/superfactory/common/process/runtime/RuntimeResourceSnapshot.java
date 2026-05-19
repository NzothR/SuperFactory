package com.nzoth.superfactory.common.process.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.nzoth.superfactory.Config;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.key.MaterialKey;

import gregtech.api.enums.GTValues;
import gregtech.api.util.GTUtility;
import gregtech.api.util.ParallelHelper;
import gregtech.common.tileentities.machines.IDualInputHatch;
import gregtech.common.tileentities.machines.IDualInputInventory;

public final class RuntimeResourceSnapshot {

    private final Context context;
    private final List<BufferedItemStack> internalItemView = new ArrayList<>();
    private final List<BufferedFluidStack> internalFluidView = new ArrayList<>();
    private final List<BufferedItemStack> liveItemView = new ArrayList<>();
    private final List<BufferedFluidStack> liveFluidView = new ArrayList<>();
    private final List<BufferedItemStack> dualItemView = new ArrayList<>();
    private final List<BufferedFluidStack> dualFluidView = new ArrayList<>();
    private final List<BufferedItemStack> incomingItemWithinLookahead = new ArrayList<>();
    private final List<BufferedFluidStack> incomingFluidWithinLookahead = new ArrayList<>();
    private final Map<String, int[]> oreIdCache = new LinkedHashMap<>();
    private final Map<String, Boolean> itemMatchCache = new LinkedHashMap<>();

    public RuntimeResourceSnapshot(Context context) {
        this.context = context;
    }

    public void captureInternalItems(List<BufferedItemStack> source) {
        for (BufferedItemStack entry : source) {
            if (entry != null && entry.stack != null && entry.amount > 0L) {
                addItemToBuffer(internalItemView, entry.stack, entry.amount);
            }
        }
    }

    public void captureInternalFluids(List<BufferedFluidStack> source) {
        for (BufferedFluidStack entry : source) {
            if (entry != null && entry.fluidStack != null && entry.amount > 0L) {
                addFluidToBuffer(internalFluidView, entry.fluidStack, entry.amount);
            }
        }
    }

    public void captureLiveItems(ItemStack[] stacks) {
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0) {
                addItemToBuffer(liveItemView, stack, stack.stackSize);
            }
        }
    }

    public void captureLiveFluids(FluidStack[] fluids) {
        for (FluidStack stack : fluids) {
            if (stack != null && stack.amount > 0) {
                addFluidToBuffer(liveFluidView, stack, stack.amount);
            }
        }
    }

    public void captureDualInputs() {
        for (IDualInputHatch hatch : context.dualInputHatches()) {
            if (hatch == null) {
                continue;
            }
            for (Iterator<? extends IDualInputInventory> iterator = hatch.inventories(); iterator.hasNext();) {
                IDualInputInventory inventory = iterator.next();
                if (inventory == null || inventory.isEmpty()) {
                    continue;
                }
                ItemStack[] items = inventory.getItemInputs();
                if (items != null) {
                    for (ItemStack stack : items) {
                        if (stack != null && stack.stackSize > 0) {
                            addItemToBuffer(dualItemView, stack, stack.stackSize);
                        }
                    }
                }
                if (!hatch.supportsFluids()) {
                    continue;
                }
                FluidStack[] fluids = inventory.getFluidInputs();
                if (fluids != null) {
                    for (FluidStack stack : fluids) {
                        if (stack != null && stack.amount > 0) {
                            addFluidToBuffer(dualFluidView, stack, stack.amount);
                        }
                    }
                }
            }
        }
    }

    public long internalItemAmount(ItemStack template) {
        return countItemInBuffer(internalItemView, template);
    }

    public long internalFluidAmount(FluidStack template) {
        return countFluidInBuffer(internalFluidView, template);
    }

    public void captureIncomingWithinLookahead() {
        int lookahead = Math.max(0, Config.superIntegratedFactoryLookaheadTicks);
        if (lookahead <= 0) {
            return;
        }
        for (RunningJob job : context.runningJobs()) {
            if (job.remainingTicks > lookahead) {
                continue;
            }
            ProcessNode node = context.findRuntimeNode(job.nodeId);
            if (node == null) {
                continue;
            }
            captureProjectedOutputs(node, job.parallel);
        }
    }

    public long projectedItemAmount(ItemStack template) {
        return safeAddLong(internalItemAmount(template), countItemInBuffer(incomingItemWithinLookahead, template));
    }

    public long projectedFluidAmount(FluidStack template) {
        return safeAddLong(internalFluidAmount(template), countFluidInBuffer(incomingFluidWithinLookahead, template));
    }

    public long itemAmount(ProcessNode consumer, ItemStack template) {
        return safeAddLong(
            safeAddLong(consumableInternalItemAmount(consumer, template), countItemInBuffer(liveItemView, template)),
            countItemInBuffer(dualItemView, template));
    }

    public long fluidAmount(ProcessNode consumer, FluidStack template) {
        return safeAddLong(
            safeAddLong(consumableInternalFluidAmount(consumer, template), countFluidInBuffer(liveFluidView, template)),
            countFluidInBuffer(dualFluidView, template));
    }

    public List<BufferedItemStack> liveItemView() {
        return liveItemView;
    }

    public List<BufferedFluidStack> liveFluidView() {
        return liveFluidView;
    }

    public int[] oreIds(ItemStack stack) {
        if (stack == null) {
            return GTValues.emptyIntArray;
        }
        String key = itemBufferKey(stack);
        int[] cached = oreIdCache.get(key);
        if (cached == null) {
            cached = OreDictionary.getOreIDs(stack);
            if (cached == null) {
                cached = GTValues.emptyIntArray;
            }
            oreIdCache.put(key, cached);
        }
        return cached;
    }

    public boolean itemMatchesCached(ItemStack recipeInput, ItemStack provided) {
        String key = itemBufferKey(recipeInput) + "=>" + itemBufferKey(provided);
        Boolean cached = itemMatchCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean matched = context.itemMatchesUncached(recipeInput, provided);
        itemMatchCache.put(key, matched);
        return matched;
    }

    private void captureProjectedOutputs(ProcessNode node, int parallel) {
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            if (fluid != null) {
                addFluidToBuffer(
                    incomingFluidWithinLookahead,
                    fluid,
                    safeMultiply(Math.max(1L, context.getStackAmount(output)), Math.max(1L, parallel)));
            } else {
                long rolls = ParallelHelper
                    .calculateIntegralChancedOutputMultiplier(node.getOutputChance(slot), Math.max(1, parallel));
                if (rolls > 0L) {
                    addItemToBuffer(
                        incomingItemWithinLookahead,
                        output,
                        safeMultiply(context.getStackAmount(output), rolls));
                }
            }
        }
    }

    private long consumableInternalItemAmount(ProcessNode consumer, ItemStack template) {
        long stored = internalItemAmount(template);
        CycleRuntimeState state = context.cycleRuntimeState(context.materialKeyOf(template));
        return state == null || consumer != null && state.containsNode(consumer.id) ? stored
            : Math.max(0L, stored - state.reserve);
    }

    private long consumableInternalFluidAmount(ProcessNode consumer, FluidStack template) {
        long stored = internalFluidAmount(template);
        CycleRuntimeState state = context.cycleRuntimeState(MaterialKey.ofFluid(template));
        return state == null || consumer != null && state.containsNode(consumer.id) ? stored
            : Math.max(0L, stored - state.reserve);
    }

    private static void addItemToBuffer(List<BufferedItemStack> buffer, ItemStack stack, long amount) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedItemStack entry : buffer) {
            if (entry != null && entry.stack != null && GTUtility.areStacksEqual(entry.stack, stack, true)) {
                entry.amount = safeAddLong(entry.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedItemStack(stack, amount));
    }

    private static void addFluidToBuffer(List<BufferedFluidStack> buffer, FluidStack stack, long amount) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedFluidStack entry : buffer) {
            if (entry != null && entry.fluidStack != null && entry.fluidStack.isFluidEqual(stack)) {
                entry.amount = safeAddLong(entry.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedFluidStack(stack, amount));
    }

    private static long countItemInBuffer(List<BufferedItemStack> buffer, ItemStack template) {
        long total = 0L;
        for (BufferedItemStack entry : buffer) {
            if (entry != null && entry.stack != null && GTUtility.areStacksEqual(entry.stack, template, true)) {
                total = safeAddLong(total, entry.amount);
            }
        }
        return total;
    }

    private static long countFluidInBuffer(List<BufferedFluidStack> buffer, FluidStack template) {
        if (template == null) {
            return 0L;
        }
        long total = 0L;
        for (BufferedFluidStack entry : buffer) {
            if (entry != null && entry.fluidStack != null && entry.fluidStack.isFluidEqual(template)) {
                total = safeAddLong(total, entry.amount);
            }
        }
        return total;
    }

    private static String itemBufferKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "item:null";
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage();
    }

    private static long safeAddLong(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    public interface Context {

        Iterable<IDualInputHatch> dualInputHatches();

        Iterable<RunningJob> runningJobs();

        ProcessNode findRuntimeNode(int nodeId);

        long getStackAmount(ItemStack stack);

        MaterialKey materialKeyOf(ItemStack stack);

        CycleRuntimeState cycleRuntimeState(MaterialKey key);

        boolean itemMatchesUncached(ItemStack recipeInput, ItemStack provided);
    }
}
