package com.nzoth.superfactory.common.recipe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.ParallelHelper;

public final class ProxyRecipeExecutor {

    /** GT batch mode keeps batched jobs below this many ticks before final runtime overrides are applied. */
    private static final double MAX_BATCH_MODE_TICK_TIME = 128.0D;

    private ProxyRecipeExecutor() {}

    public static ProxyRecipeExecutionPlan buildPlan(Settings settings) {
        if (settings == null || settings.recipe == null || settings.baseParallel <= 0 || settings.inputBound <= 0) {
            return null;
        }
        OverclockResult overclock = calculateOverclockedRecipe(settings);
        if (overclock == null) {
            return null;
        }
        int executionParallel = computeBatchedParallel(settings, overclock.duration);
        double batchMultiplier = settings.baseParallel > 0 ? (double) executionParallel / settings.baseParallel : 1.0D;
        int rawDuration = scaleIntCeil(overclock.duration, executionParallel, settings.baseParallel);
        long rawEut = overclock.euPerTick;
        long transformedDuration = RuntimeTransform
            .clampFinalDuration(rawDuration, settings.minimumRuntime, settings.maximumRuntime);
        long transformedTotalEnergy = safeMultiply(rawEut, transformedDuration);
        if (transformedTotalEnergy <= 0L) {
            return null;
        }

        ItemStack[] rawItemOutputs = buildRawItemOutputs(settings.recipe, executionParallel);
        FluidStack[] rawFluidOutputs = buildRawFluidOutputs(settings.recipe, executionParallel);
        ItemStack[] transformedItems = settings.itemTransformer.apply(rawItemOutputs);
        FluidStack[] transformedFluids = settings.fluidTransformer.apply(rawFluidOutputs);

        return new ProxyRecipeExecutionPlan(
            settings.recipe,
            executionParallel,
            rawDuration,
            (int) transformedDuration,
            transformedTotalEnergy,
            rawEut,
            transformedItems,
            transformedFluids,
            settings.baseParallel,
            batchMultiplier,
            overclock.overclocks);
    }

    public static int computeOverclocksToOneTick(int duration) {
        int overclocks = 0;
        double currentDuration = Math.max(1, duration);
        while (currentDuration >= 2.0D && overclocks < 64) {
            currentDuration /= 4.0D;
            overclocks++;
        }
        return overclocks;
    }

    public static long computePowerForPerfectOverclocks(GTRecipe recipe, int parallel, int overclocks) {
        long recipeEut = Math.max(1L, Math.abs((long) recipe.mEUt));
        long basePower = safeMultiply(recipeEut, parallel);
        long multiplier = 1L;
        for (int i = 0; i < overclocks; i++) {
            multiplier = safeMultiply(multiplier, 4L);
        }
        return Math.max(1L, safeMultiply(basePower, multiplier));
    }

    private static OverclockResult calculateOverclockedRecipe(Settings settings) {
        if (settings.manualOverclocks > 0) {
            return calculateManualOverclockedRecipe(settings);
        }

        int maxOverclocks = computeOverclocksToOneTick(Math.max(1, settings.recipe.mDuration));
        if (settings.disableOverclocking) {
            maxOverclocks = 0;
        }
        long availablePower = settings.wirelessMode
            ? computePowerForPerfectOverclocks(settings.recipe, settings.baseParallel, maxOverclocks)
            : Math.max(1L, settings.availablePower);
        OverclockCalculator calculator = new OverclockCalculator()
            .setRecipeEUt(Math.max(1L, Math.abs((long) settings.recipe.mEUt)))
            .setDuration(Math.max(1, settings.recipe.mDuration))
            .setEUt(availablePower)
            .setAmperage(1)
            .enablePerfectOC()
            .setMaxOverclocks(maxOverclocks)
            .setParallel(settings.baseParallel)
            .setCurrentParallel(settings.baseParallel);
        if (settings.wirelessMode) {
            calculator.setUnlimitedTierSkips();
        }
        if (settings.enableHeatOverclocking) {
            int recipeHeat = Math.max(0, settings.recipe.mSpecialValue);
            calculator.setRecipeHeat(recipeHeat)
                .setMachineHeat(Math.max(0, recipeHeat))
                .setHeatOC(true)
                .setHeatDiscount(true);
        }
        try {
            calculator.calculate();
        } catch (RuntimeException ignored) {
            return null;
        }
        long euPerTick = calculator.getConsumption();
        int duration = calculator.getDuration();
        if (euPerTick <= 0L || euPerTick == Long.MAX_VALUE || duration <= 0 || duration == Integer.MAX_VALUE) {
            return null;
        }
        return new OverclockResult(duration, euPerTick, calculator.getPerformedOverclocks());
    }

    private static OverclockResult calculateManualOverclockedRecipe(Settings settings) {
        int overclocks = Math.max(0, Math.min(64, settings.manualOverclocks));
        long euPerTick = computePowerForPerfectOverclocks(settings.recipe, settings.baseParallel, overclocks);
        if (!settings.wirelessMode && euPerTick > Math.max(1L, settings.availablePower)) {
            return null;
        }
        int duration = Math.max(1, settings.recipe.mDuration);
        for (int i = 0; i < overclocks; i++) {
            duration = Math.max(1, duration / 4);
        }
        return new OverclockResult(duration, euPerTick, overclocks);
    }

    private static int computeBatchedParallel(Settings settings, int overclockedDuration) {
        if (!settings.batchEnabled || settings.baseParallel <= 0
            || settings.inputBound <= settings.baseParallel
            || overclockedDuration <= 0
            || overclockedDuration >= MAX_BATCH_MODE_TICK_TIME) {
            return settings.baseParallel;
        }
        int batchModifier = Math.max(1, settings.maxBatchSize);
        long maxByBatchModifier = safeMultiply(settings.baseParallel, batchModifier);
        long maxByTickLimit = (long) Math
            .floor(settings.baseParallel * (MAX_BATCH_MODE_TICK_TIME / overclockedDuration));
        long maxExecutionParallel = Math.min(settings.inputBound, Math.min(maxByBatchModifier, maxByTickLimit));
        return (int) Math.max(settings.baseParallel, Math.min(Integer.MAX_VALUE, maxExecutionParallel));
    }

    public static ItemStack[] buildRawItemOutputs(GTRecipe recipe, int parallel) {
        if (recipe.mOutputs == null || recipe.mOutputs.length == 0 || parallel <= 0) {
            return null;
        }
        ArrayList<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < recipe.mOutputs.length; i++) {
            ItemStack output = recipe.mOutputs[i];
            if (output == null || output.stackSize <= 0) {
                continue;
            }
            int chance = recipe.mChances != null && i < recipe.mChances.length ? recipe.mChances[i] : 10000;
            long multiplier = ParallelHelper.calculateIntegralChancedOutputMultiplier(chance, parallel);
            ParallelHelper.addItemsLong(out, output, (long) output.stackSize * multiplier);
        }
        return out.isEmpty() ? null : out.toArray(new ItemStack[0]);
    }

    public static FluidStack[] buildRawFluidOutputs(GTRecipe recipe, int parallel) {
        if (recipe.mFluidOutputs == null || recipe.mFluidOutputs.length == 0 || parallel <= 0) {
            return null;
        }
        ArrayList<FluidStack> out = new ArrayList<>();
        for (FluidStack output : recipe.mFluidOutputs) {
            if (output == null || output.amount <= 0) {
                continue;
            }
            ParallelHelper.addFluidsLong(out, output, (long) output.amount * parallel);
        }
        return out.isEmpty() ? null : out.toArray(new FluidStack[0]);
    }

    private static int scaleIntCeil(int value, int numerator, int denominator) {
        if (value <= 0 || numerator <= 0 || denominator <= 0) {
            return value;
        }
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, scaleLongCeil(value, numerator, denominator)));
    }

    private static long scaleLongCeil(long value, int numerator, int denominator) {
        if (value <= 0L || numerator <= 0 || denominator <= 0) {
            return value;
        }
        BigInteger scaled = BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(numerator))
            .add(BigInteger.valueOf(denominator - 1L))
            .divide(BigInteger.valueOf(denominator));
        return scaled.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
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

    public static final class Settings {

        public GTRecipe recipe;
        /** Parallel allowed by voltage/power before batch expansion. */
        public int baseParallel;
        /** Parallel allowed by available inputs; batch expansion must not exceed this value. */
        public int inputBound;
        public boolean batchEnabled;
        public int maxBatchSize = 1;
        public boolean wirelessMode;
        public long availablePower;
        /** Final lower duration clamp. Applied after overclocking and batch planning. */
        public long minimumRuntime = 1L;
        /** Final upper duration clamp. This is a cheat-like override and never affects power checks. */
        public long maximumRuntime;
        public boolean enableHeatOverclocking;
        /** Used for marker-only recipes: they may parallelize, but automatic overclocking is disabled. */
        public boolean disableOverclocking;
        /** Exact perfect-overclock count. Zero keeps the automatic GT-style planner. */
        public int manualOverclocks;
        public Function<ItemStack[], ItemStack[]> itemTransformer = outputs -> outputs;
        public Function<FluidStack[], FluidStack[]> fluidTransformer = outputs -> outputs;
    }

    private static final class OverclockResult {

        private final int duration;
        private final long euPerTick;
        private final int overclocks;

        private OverclockResult(int duration, long euPerTick, int overclocks) {
            this.duration = duration;
            this.euPerTick = euPerTick;
            this.overclocks = overclocks;
        }
    }
}
