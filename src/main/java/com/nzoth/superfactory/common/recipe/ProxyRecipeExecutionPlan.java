package com.nzoth.superfactory.common.recipe;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTRecipe;

/**
 * Immutable result of the proxy recipe planner.
 *
 * <p>
 * The plan contains already-transformed outputs and final duration, so the machine can apply it without consulting
 * GregTech's ProcessingLogic.
 */
public final class ProxyRecipeExecutionPlan {

    public final GTRecipe recipe;
    /** Parallel after batch expansion. */
    public final int actualParallel;
    /** Duration after overclocking and batch expansion, before user runtime clamps. */
    public final int rawDuration;
    /** Final duration after the proxy's minimum/maximum runtime overrides. */
    public final int transformedDuration;
    public final long totalEnergy;
    public final long euPerTick;
    public final ItemStack[] outputItems;
    public final FluidStack[] outputFluids;
    /** Parallel selected from voltage/power before batch expansion. */
    public final int baseParallel;
    public final double batchMultiplier;
    public final int overclocks;

    public ProxyRecipeExecutionPlan(GTRecipe recipe, int actualParallel, int rawDuration, int transformedDuration,
        long totalEnergy, long euPerTick, ItemStack[] outputItems, FluidStack[] outputFluids, int baseParallel,
        double batchMultiplier, int overclocks) {
        this.recipe = recipe;
        this.actualParallel = actualParallel;
        this.rawDuration = rawDuration;
        this.transformedDuration = transformedDuration;
        this.totalEnergy = totalEnergy;
        this.euPerTick = euPerTick;
        this.outputItems = outputItems;
        this.outputFluids = outputFluids;
        this.baseParallel = baseParallel;
        this.batchMultiplier = batchMultiplier;
        this.overclocks = overclocks;
    }
}
