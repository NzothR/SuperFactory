package com.nzoth.superfactory.common.process.runtime;

import net.minecraftforge.fluids.FluidStack;

/**
 * Long-count fluid entry used by the integrated factory internal and output buffers.
 *
 * <p>
 * The template fluid is normalized to amount {@code 1}; the real amount lives in {@link #amount}.
 */
public final class BufferedFluidStack {

    public final FluidStack fluidStack;
    public long amount;

    public BufferedFluidStack(FluidStack fluidStack, long amount) {
        this.fluidStack = fluidStack == null ? null : fluidStack.copy();
        if (this.fluidStack != null) {
            this.fluidStack.amount = 1;
        }
        this.amount = Math.max(0L, amount);
    }
}
