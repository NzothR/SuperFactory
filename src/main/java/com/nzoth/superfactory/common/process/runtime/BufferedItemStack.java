package com.nzoth.superfactory.common.process.runtime;

import net.minecraft.item.ItemStack;

/**
 * Long-count item entry used by the integrated factory internal and output buffers.
 *
 * <p>
 * The template stack is normalized to stack size {@code 1}; the real amount lives in {@link #amount}.
 */
public final class BufferedItemStack {

    public final ItemStack stack;
    public long amount;

    public BufferedItemStack(ItemStack stack, long amount) {
        this.stack = stack == null ? null : stack.copy();
        if (this.stack != null) {
            this.stack.stackSize = 1;
        }
        this.amount = Math.max(0L, amount);
    }
}
