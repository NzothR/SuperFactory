package com.nzoth.superfactory.common.recipe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.Materials;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.ParallelHelper;
import tectech.recipe.EyeOfHarmonyRecipe;
import tectech.util.FluidStackLong;
import tectech.util.ItemStackLong;

/** Normalizes special recipe formats into the simple input/output/energy view used by proxy executors. */
public final class ProxyRecipeEffectiveValues {

    private ProxyRecipeEffectiveValues() {}

    public static EyeOfHarmonyRecipe eyeOfHarmony(GTRecipe recipe) {
        return recipe != null && recipe.mSpecialItems instanceof EyeOfHarmonyRecipe
            ? (EyeOfHarmonyRecipe) recipe.mSpecialItems
            : null;
    }

    public static boolean isEyeOfHarmony(GTRecipe recipe) {
        return eyeOfHarmony(recipe) != null;
    }

    public static ItemStack[] itemInputs(GTRecipe recipe) {
        return safeItems(recipe == null ? null : recipe.mInputs);
    }

    public static FluidStack[] fluidInputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = eyeOfHarmony(recipe);
        if (eyeRecipe == null) {
            return safeFluids(recipe == null ? null : recipe.mFluidInputs);
        }
        ArrayList<FluidStack> inputs = new ArrayList<>();
        addEyeFluidInput(inputs, Materials.Hydrogen.getGas(1), eyeRecipe.getHydrogenRequirement());
        addEyeFluidInput(inputs, Materials.Helium.getGas(1), eyeRecipe.getHeliumRequirement());
        return inputs.toArray(new FluidStack[0]);
    }

    public static ItemStack[] itemOutputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = eyeOfHarmony(recipe);
        if (eyeRecipe == null) {
            return safeItems(recipe == null ? null : recipe.mOutputs);
        }
        ArrayList<ItemStack> outputs = new ArrayList<>();
        for (ItemStackLong stackLong : eyeRecipe.getOutputItems()) {
            if (stackLong == null || stackLong.itemStack == null || stackLong.stackSize <= 0L) {
                continue;
            }
            ParallelHelper.addItemsLong(outputs, stackLong.itemStack, stackLong.stackSize);
        }
        return outputs.toArray(new ItemStack[0]);
    }

    public static FluidStack[] fluidOutputs(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = eyeOfHarmony(recipe);
        if (eyeRecipe == null) {
            return safeFluids(recipe == null ? null : recipe.mFluidOutputs);
        }
        ArrayList<FluidStack> outputs = new ArrayList<>();
        for (FluidStackLong stackLong : eyeRecipe.getOutputFluids()) {
            if (stackLong == null || stackLong.fluidStack == null || stackLong.amount <= 0L) {
                continue;
            }
            ParallelHelper.addFluidsLong(outputs, stackLong.fluidStack, stackLong.amount);
        }
        return outputs.toArray(new FluidStack[0]);
    }

    public static int[] itemOutputChances(GTRecipe recipe, int itemOutputCount) {
        int[] chances = new int[Math.max(0, itemOutputCount)];
        Arrays.fill(chances, 10000);
        if (recipe == null || recipe.mChances == null || isEyeOfHarmony(recipe)) {
            return chances;
        }
        for (int i = 0; i < chances.length && i < recipe.mChances.length; i++) {
            chances[i] = Math.max(0, Math.min(10000, recipe.mChances[i]));
        }
        return chances;
    }

    public static int duration(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = eyeOfHarmony(recipe);
        long duration = eyeRecipe == null ? recipe == null ? 0L : recipe.mDuration : eyeRecipe.getRecipeTimeInTicks();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, duration));
    }

    public static long inputEuPerTick(GTRecipe recipe) {
        EyeOfHarmonyRecipe eyeRecipe = eyeOfHarmony(recipe);
        if (eyeRecipe == null) {
            return recipe == null ? 0L : Math.max(0L, recipe.mEUt);
        }
        return divideCeil(eyeInputEnergy(eyeRecipe), duration(recipe));
    }

    public static long generatedEuPerTick(GTRecipe recipe) {
        return recipe == null ? 0L : Math.max(0L, -(long) recipe.mEUt);
    }

    public static long eyeInputEnergy(EyeOfHarmonyRecipe eyeRecipe) {
        if (eyeRecipe == null) {
            return 0L;
        }
        long total = Math.max(0L, eyeRecipe.getEUStartCost());
        double efficiency = eyeRecipe.getRecipeEnergyEfficiency();
        if (efficiency > 0.0D) {
            double fromOutput = Math.ceil(Math.max(0L, eyeRecipe.getEUOutput()) / efficiency);
            total = Math.max(total, fromOutput >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) fromOutput);
        }
        return total;
    }

    private static void addEyeFluidInput(ArrayList<FluidStack> inputs, FluidStack template, long amount) {
        if (template == null || template.getFluid() == null || amount <= 0L) {
            return;
        }
        FluidStack copy = template.copy();
        copy.amount = 1;
        ParallelHelper.addFluidsLong(inputs, copy, amount);
    }

    private static ItemStack[] safeItems(ItemStack[] stacks) {
        return stacks == null ? GTValues.emptyItemStackArray : stacks;
    }

    private static FluidStack[] safeFluids(FluidStack[] stacks) {
        return stacks == null ? GTValues.emptyFluidStackArray : stacks;
    }

    private static long divideCeil(long numerator, long denominator) {
        if (numerator <= 0L) {
            return 0L;
        }
        if (denominator <= 1L) {
            return numerator;
        }
        return BigInteger.valueOf(numerator)
            .add(BigInteger.valueOf(denominator - 1L))
            .divide(BigInteger.valueOf(denominator))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }
}
