package com.nzoth.superfactory.common.process.watermark;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.runtime.ProcessRuntimeMath;

import gregtech.api.util.GTUtility;

public final class IntegratedFactoryWatermarks {

    private IntegratedFactoryWatermarks() {}

    /**
     * Pre-compiled watermark info that is rebuilt when the graph or node parameters
     * change, and read on every tick for O(1) low-water queries.
     */
    public static final class CompiledWatermarkInfo {

        /** key: (producerNodeId << 32) | materialHash, value: lowWater */
        final Map<Long, Long> internalItemLowWaterCache = new LinkedHashMap<>();

        final Map<Long, Long> internalFluidLowWaterCache = new LinkedHashMap<>();
        final Map<Long, Long> externalItemLowWaterCache = new LinkedHashMap<>();
        final Map<Long, Long> externalFluidLowWaterCache = new LinkedHashMap<>();

        long compoundKey(int nodeId, Object material) {
            return ((long) nodeId << 32) | (material.hashCode() & 0xFFFFFFFFL);
        }

        public long internalItemLowWater(int producerId, ItemStack output) {
            Long cached = internalItemLowWaterCache.get(compoundKey(producerId, output));
            return cached == null ? 1L : cached;
        }

        public long internalFluidLowWater(int producerId, FluidStack output) {
            Long cached = internalFluidLowWaterCache.get(compoundKey(producerId, output));
            return cached == null ? 1L : cached;
        }

        public long externalItemLowWater(int producerId, ItemStack output) {
            Long cached = externalItemLowWaterCache.get(compoundKey(producerId, output));
            return cached == null ? 1L : cached;
        }

        public long externalFluidLowWater(int producerId, FluidStack output) {
            Long cached = externalFluidLowWaterCache.get(compoundKey(producerId, output));
            return cached == null ? 1L : cached;
        }

        public void clear() {
            internalItemLowWaterCache.clear();
            internalFluidLowWaterCache.clear();
            externalItemLowWaterCache.clear();
            externalFluidLowWaterCache.clear();
        }
    }

    // ---- runtime computation (called when rebuilding compiled info) ----

    public static long internalItemLowWater(Context context, ProcessNode producer, ItemStack output, long batchAmount) {
        long lowWater = Math
            .max(outputThroughputPerSecond(context, producer, batchAmount), outputBatchAmount(batchAmount));
        for (ProcessEdge edge : context.runtimeEdges()) {
            if (edge.fromNodeId != producer.id) {
                continue;
            }
            ProcessNode consumer = context.findRuntimeNode(edge.toNodeId);
            if (consumer == null) {
                continue;
            }
            for (int slot = 0; slot < consumer.inputHandler.getSlots(); slot++) {
                ItemStack input = consumer.inputHandler.getStackInSlot(slot);
                if (input != null && !isFluidDisplay(input) && context.itemMatches(input, output)) {
                    lowWater = Math.max(
                        lowWater,
                        ProcessRuntimeMath
                            .safeMultiply(context.stackAmount(input), context.effectiveParallelLimit(consumer)));
                }
            }
        }
        return Math.max(1L, lowWater);
    }

    public static long internalFluidLowWater(Context context, ProcessNode producer, FluidStack output,
        long batchAmount) {
        long lowWater = Math
            .max(outputThroughputPerSecond(context, producer, batchAmount), outputBatchAmount(batchAmount));
        for (ProcessEdge edge : context.runtimeEdges()) {
            if (edge.fromNodeId != producer.id) {
                continue;
            }
            ProcessNode consumer = context.findRuntimeNode(edge.toNodeId);
            if (consumer == null) {
                continue;
            }
            for (int slot = 0; slot < consumer.inputHandler.getSlots(); slot++) {
                FluidStack input = GTUtility.getFluidFromDisplayStack(consumer.inputHandler.getStackInSlot(slot));
                if (input != null && input.isFluidEqual(output)) {
                    lowWater = Math.max(
                        lowWater,
                        ProcessRuntimeMath
                            .safeMultiply(Math.max(1L, input.amount), context.effectiveParallelLimit(consumer)));
                }
            }
        }
        return Math.max(1L, lowWater);
    }

    public static long externalLowWater(Context context, ProcessNode producer, long batchAmount) {
        return Math.max(outputThroughputPerSecond(context, producer, batchAmount), outputBatchAmount(batchAmount));
    }

    public static long highWater(long lowWater) {
        long minimumHigh = lowWater == Long.MAX_VALUE ? Long.MAX_VALUE : lowWater + 1L;
        return Math.max(minimumHigh, ProcessRuntimeMath.safeCeilMultiply(lowWater, 3L, 1L));
    }

    public static long outputThroughputPerSecond(Context context, ProcessNode producer, long batchAmount) {
        long duration = waterlineDuration(context, producer);
        long perTick = outputBatchAmount(batchAmount);
        long perSecond = ProcessRuntimeMath.safeCeilMultiply(perTick, 20L, duration);
        return Math.max(1L, perSecond);
    }

    public static long outputBatchAmount(long batchAmount) {
        return Math.max(1L, batchAmount);
    }

    public static long waterlineDuration(Context context, ProcessNode producer) {
        return Math.max(1L, context.effectiveDurationTicks(producer));
    }

    private static boolean isFluidDisplay(ItemStack stack) {
        return GTUtility.getFluidFromDisplayStack(stack) != null;
    }

    public interface Context {

        Iterable<ProcessEdge> runtimeEdges();

        ProcessNode findRuntimeNode(int nodeId);

        boolean itemMatches(ItemStack recipeInput, ItemStack provided);

        long stackAmount(ItemStack stack);

        int effectiveParallelLimit(ProcessNode node);

        int effectiveDurationTicks(ProcessNode node);
    }
}
