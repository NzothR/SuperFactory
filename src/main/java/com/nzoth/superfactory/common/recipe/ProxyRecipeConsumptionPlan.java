package com.nzoth.superfactory.common.recipe;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTRecipe;

/**
 * Concrete material demands for a selected recipe and parallel count.
 *
 * <p>
 * Only inputs with positive stack size or fluid amount are included. Marker items, circuits, molds, and other
 * zero-consume recipe inputs stay available for matching but never appear in this plan.
 */
public final class ProxyRecipeConsumptionPlan {

    public final GTRecipe recipe;
    public final int parallel;
    public final ItemDemand[] itemDemands;
    public final FluidDemand[] fluidDemands;

    ProxyRecipeConsumptionPlan(GTRecipe recipe, int parallel, ItemDemand[] itemDemands, FluidDemand[] fluidDemands) {
        this.recipe = recipe;
        this.parallel = parallel;
        this.itemDemands = itemDemands;
        this.fluidDemands = fluidDemands;
    }

    public static final class ItemDemand {

        /** Recipe input template used for ore-dict/item matching against live stacks. */
        public final ItemStack template;
        public final long amount;

        ItemDemand(ItemStack template, long amount) {
            this.template = template;
            this.amount = amount;
        }
    }

    public static final class FluidDemand {

        /** Fluid template used for equality checks against live fluid stacks. */
        public final FluidStack template;
        public final long amount;

        FluidDemand(FluidStack template, long amount) {
            this.template = template;
            this.amount = amount;
        }
    }
}
