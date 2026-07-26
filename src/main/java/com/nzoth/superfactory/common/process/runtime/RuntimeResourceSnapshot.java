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
    // Primary indexed storage for internal/incoming buffers (O(1) lookup via MaterialKey)
    private final Map<MaterialKey, Long> internalItemAmountByKey = new LinkedHashMap<>();
    private final Map<MaterialKey, Long> internalFluidAmountByKey = new LinkedHashMap<>();
    private final Map<MaterialKey, Long> incomingItemAmountByKey = new LinkedHashMap<>();
    private final Map<MaterialKey, Long> incomingFluidAmountByKey = new LinkedHashMap<>();
    // List-based views retained only for domains that need item-level iteration (OreDict/match fallbacks)
    private final List<BufferedItemStack> liveItemView = new ArrayList<>();
    private final List<BufferedFluidStack> liveFluidView = new ArrayList<>();
    private final List<BufferedItemStack> dualItemView = new ArrayList<>();
    private final List<BufferedFluidStack> dualFluidView = new ArrayList<>();
    private final Map<String, int[]> oreIdCache = new LinkedHashMap<>();
    private final Map<String, Boolean> itemMatchCache = new LinkedHashMap<>();

    public RuntimeResourceSnapshot(Context context) {
        this.context = context;
    }

    // ---- capture (indexed storage built incrementally — no explicit buildIndexes needed) ----

    public void captureInternalItems(List<BufferedItemStack> source) {
        for (BufferedItemStack entry : source) {
            if (entry != null && entry.stack != null && entry.amount > 0L) {
                MaterialKey key = context.materialKeyOf(entry.stack);
                if (key != null) {
                    addToIndex(internalItemAmountByKey, key, entry.amount);
                }
            }
        }
    }

    public void captureInternalFluids(List<BufferedFluidStack> source) {
        for (BufferedFluidStack entry : source) {
            if (entry != null && entry.fluidStack != null && entry.amount > 0L) {
                MaterialKey key = MaterialKey.ofFluid(entry.fluidStack);
                if (key != null) {
                    addToIndex(internalFluidAmountByKey, key, entry.amount);
                }
            }
        }
    }

    public void captureLiveItems(ItemStack[] stacks) {
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0) {
                ProcessBufferUtil.addItem(liveItemView, stack, stack.stackSize, GTUtility::areStacksEqual);
            }
        }
    }

    public void captureLiveFluids(FluidStack[] fluids) {
        for (FluidStack stack : fluids) {
            if (stack != null && stack.amount > 0) {
                ProcessBufferUtil.addFluid(liveFluidView, stack, stack.amount);
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
                            ProcessBufferUtil.addItem(dualItemView, stack, stack.stackSize, GTUtility::areStacksEqual);
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
                            ProcessBufferUtil.addFluid(dualFluidView, stack, stack.amount);
                        }
                    }
                }
            }
        }
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

    // ---- query ----

    public long internalItemAmount(ItemStack template) {
        MaterialKey key = context.materialKeyOf(template);
        if (key != null) {
            Long cached = internalItemAmountByKey.get(key);
            if (cached != null) return cached;
        }
        return 0L;
    }

    public long internalFluidAmount(FluidStack template) {
        MaterialKey key = MaterialKey.ofFluid(template);
        if (key != null) {
            Long cached = internalFluidAmountByKey.get(key);
            if (cached != null) return cached;
        }
        return 0L;
    }

    public long projectedItemAmount(ItemStack template) {
        return ProcessRuntimeMath.safeAdd(internalItemAmount(template), incomingItemAmount(template));
    }

    public long projectedFluidAmount(FluidStack template) {
        return ProcessRuntimeMath.safeAdd(internalFluidAmount(template), incomingFluidAmount(template));
    }

    public long itemAmount(ProcessNode consumer, ItemStack template) {
        return ProcessRuntimeMath.safeAdd(
            ProcessRuntimeMath.safeAdd(consumableInternalItemAmount(consumer, template), liveItemAmount(template)),
            dualItemAmount(template));
    }

    public long fluidAmount(ProcessNode consumer, FluidStack template) {
        return ProcessRuntimeMath.safeAdd(
            ProcessRuntimeMath.safeAdd(consumableInternalFluidAmount(consumer, template), liveFluidAmount(template)),
            dualFluidAmount(template));
    }

    // ---- incoming index queries ----

    private long incomingItemAmount(ItemStack template) {
        MaterialKey key = context.materialKeyOf(template);
        if (key != null) {
            Long cached = incomingItemAmountByKey.get(key);
            if (cached != null) return cached;
        }
        return 0L;
    }

    private long incomingFluidAmount(FluidStack template) {
        MaterialKey key = MaterialKey.ofFluid(template);
        if (key != null) {
            Long cached = incomingFluidAmountByKey.get(key);
            if (cached != null) return cached;
        }
        return 0L;
    }

    // ---- live / dual list views (needed for itemMatches/oreDict lookups) ----

    public List<BufferedItemStack> liveItemView() {
        return liveItemView;
    }

    public List<BufferedFluidStack> liveFluidView() {
        return liveFluidView;
    }

    private long liveItemAmount(ItemStack template) {
        return ProcessBufferUtil.countItem(liveItemView, template, GTUtility::areStacksEqual);
    }

    private long liveFluidAmount(FluidStack template) {
        return ProcessBufferUtil.countFluid(liveFluidView, template);
    }

    private long dualItemAmount(ItemStack template) {
        return ProcessBufferUtil.countItem(dualItemView, template, GTUtility::areStacksEqual);
    }

    private long dualFluidAmount(FluidStack template) {
        return ProcessBufferUtil.countFluid(dualFluidView, template);
    }

    // ---- ore-dict / item-match caches ----

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

    // ---- internal helpers ----

    private void captureProjectedOutputs(ProcessNode node, int parallel) {
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            if (fluid != null) {
                long amount = ProcessRuntimeMath
                    .safeMultiply(Math.max(1L, context.getStackAmount(output)), Math.max(1L, parallel));
                MaterialKey key = MaterialKey.ofFluid(fluid);
                if (key != null) {
                    addToIndex(incomingFluidAmountByKey, key, amount);
                }
            } else {
                long rolls = ParallelHelper
                    .calculateIntegralChancedOutputMultiplier(node.getOutputChance(slot), Math.max(1, parallel));
                if (rolls > 0L) {
                    long amount = ProcessRuntimeMath.safeMultiply(context.getStackAmount(output), rolls);
                    MaterialKey key = context.materialKeyOf(output);
                    if (key != null) {
                        addToIndex(incomingItemAmountByKey, key, amount);
                    }
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

    private static void addToIndex(Map<MaterialKey, Long> index, MaterialKey key, long amount) {
        Long existing = index.get(key);
        index.put(key, existing == null ? amount : ProcessRuntimeMath.safeAdd(existing, amount));
    }

    // ---- string keys (used for oreDict/itemMatch caches) ----

    static String itemBufferKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "item:null";
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage();
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
