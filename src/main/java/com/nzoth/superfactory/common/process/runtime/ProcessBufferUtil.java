package com.nzoth.superfactory.common.process.runtime;

import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Shared long-count buffer operations for virtual process execution.
 *
 * <p>
 * Matching is supplied by the caller so a machine can decide whether a buffer should use exact item identity,
 * ore-dictionary matching, or another local rule.
 */
public final class ProcessBufferUtil {

    private ProcessBufferUtil() {}

    public static void addItem(List<BufferedItemStack> buffer, ItemStack stack, long amount,
        BiPredicate<ItemStack, ItemStack> matcher) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedItemStack existing : buffer) {
            if (existing != null && existing.stack != null && matcher.test(existing.stack, stack)) {
                existing.amount = ProcessRuntimeMath.safeAdd(existing.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedItemStack(stack, amount));
    }

    public static void addFluid(List<BufferedFluidStack> buffer, FluidStack stack, long amount) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedFluidStack existing : buffer) {
            if (existing != null && existing.fluidStack != null && existing.fluidStack.isFluidEqual(stack)) {
                existing.amount = ProcessRuntimeMath.safeAdd(existing.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedFluidStack(stack, amount));
    }

    public static long countItem(List<BufferedItemStack> buffer, ItemStack template,
        BiPredicate<ItemStack, ItemStack> matcher) {
        long amount = 0L;
        for (BufferedItemStack entry : buffer) {
            if (entry != null && entry.stack != null && matcher.test(template, entry.stack)) {
                amount = ProcessRuntimeMath.safeAdd(amount, entry.amount);
            }
        }
        return amount;
    }

    public static long countFluid(List<BufferedFluidStack> buffer, FluidStack template) {
        if (template == null) {
            return 0L;
        }
        long amount = 0L;
        for (BufferedFluidStack stack : buffer) {
            if (stack != null && stack.fluidStack != null && stack.fluidStack.isFluidEqual(template)) {
                amount = ProcessRuntimeMath.safeAdd(amount, stack.amount);
            }
        }
        return amount;
    }

    public static long removeItem(List<BufferedItemStack> buffer, ItemStack template, long amount,
        BiPredicate<ItemStack, ItemStack> matcher, RemovedItemConsumer removedConsumer) {
        long remaining = amount;
        Iterator<BufferedItemStack> iterator = buffer.iterator();
        while (iterator.hasNext() && remaining > 0L) {
            BufferedItemStack entry = iterator.next();
            if (entry == null || entry.stack == null || !matcher.test(template, entry.stack)) {
                continue;
            }
            long removed = Math.min(remaining, entry.amount);
            entry.amount -= removed;
            remaining -= removed;
            if (removedConsumer != null && removed > 0L) {
                removedConsumer.accept(entry.stack, removed);
            }
            if (entry.amount <= 0L) {
                iterator.remove();
            }
        }
        return remaining;
    }

    public static long removeFluid(List<BufferedFluidStack> buffer, FluidStack template, long amount) {
        long remaining = amount;
        Iterator<BufferedFluidStack> iterator = buffer.iterator();
        while (iterator.hasNext() && remaining > 0L) {
            BufferedFluidStack stack = iterator.next();
            if (stack == null || stack.fluidStack == null
                || template == null
                || !stack.fluidStack.isFluidEqual(template)) {
                continue;
            }
            long removed = Math.min(remaining, stack.amount);
            stack.amount -= removed;
            remaining -= removed;
            if (stack.amount <= 0L) {
                iterator.remove();
            }
        }
        return remaining;
    }

    @FunctionalInterface
    public interface RemovedItemConsumer {

        void accept(ItemStack stack, long amount);
    }
}
