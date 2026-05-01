package com.nzoth.superfactory.common.recipe;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * One isolated recipe input source.
 *
 * <p>
 * Live arrays point at real hatch stacks and are used for consumption. Query arrays are safe copies used only for
 * recipe lookup, so virtual/non-consumable inputs can participate in matching without being consumed.
 */
public final class ProxyRecipeInputGroup {

    public final byte color;
    /** Real inventory references; mutating these consumes the recipe inputs. */
    public final ItemStack[] liveItems;
    public final FluidStack[] liveFluids;
    /** Matching-only view passed to recipe search. */
    public final ItemStack[] queryItems;
    public final FluidStack[] queryFluids;

    public ProxyRecipeInputGroup(byte color, ItemStack[] liveItems, FluidStack[] liveFluids, ItemStack[] queryItems,
        FluidStack[] queryFluids) {
        this.color = color;
        this.liveItems = liveItems;
        this.liveFluids = liveFluids;
        this.queryItems = queryItems;
        this.queryFluids = queryFluids;
    }

    public boolean isEmpty() {
        return liveItems.length == 0 && liveFluids.length == 0;
    }
}
