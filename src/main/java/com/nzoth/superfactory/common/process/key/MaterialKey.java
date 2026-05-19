package com.nzoth.superfactory.common.process.key;

import java.util.Objects;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTUtility;

public final class MaterialKey {

    public enum Type {
        ITEM,
        FLUID
    }

    public final Type type;
    public final String id;
    public final int meta;
    public final String nbtFingerprint;

    private MaterialKey(Type type, String id, int meta, String nbtFingerprint) {
        this.type = type;
        this.id = id == null ? "" : id;
        this.meta = meta;
        this.nbtFingerprint = nbtFingerprint == null ? "" : nbtFingerprint;
    }

    public static MaterialKey ofItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null) {
            return ofFluid(fluid);
        }
        String itemName = Item.itemRegistry.getNameForObject(stack.getItem());
        return new MaterialKey(Type.ITEM, itemName, stack.getItemDamage(), normalizedNbt(stack));
    }

    public static MaterialKey ofFluid(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) {
            return null;
        }
        return new MaterialKey(
            Type.FLUID,
            stack.getFluid()
                .getName(),
            0,
            "");
    }

    public static MaterialKey parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int amountSeparator = raw.indexOf('@');
        String key = amountSeparator < 0 ? raw : raw.substring(0, amountSeparator);
        if (key.startsWith("fluid:")) {
            return new MaterialKey(Type.FLUID, key.substring("fluid:".length()), 0, "");
        }
        if (key.startsWith("item:")) {
            String payload = key.substring("item:".length());
            int metaSeparator = payload.lastIndexOf(':');
            if (metaSeparator < 0) {
                return null;
            }
            int meta = 0;
            try {
                meta = Integer.parseInt(payload.substring(metaSeparator + 1));
            } catch (NumberFormatException ignored) {}
            return new MaterialKey(Type.ITEM, payload.substring(0, metaSeparator), meta, "");
        }
        return null;
    }

    private static String normalizedNbt(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return "";
        }
        NBTTagCompound tag = (NBTTagCompound) stack.getTagCompound()
            .copy();
        tag.removeTag("SuperFactoryDisplayAmount");
        return tag.hasNoTags() ? "" : tag.toString();
    }

    public String compact() {
        if (type == Type.FLUID) {
            return "fluid:" + id;
        }
        return "item:" + id + ":" + meta + (nbtFingerprint.isEmpty() ? "" : ":" + nbtFingerprint);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MaterialKey other)) {
            return false;
        }
        return type == other.type && meta == other.meta
            && Objects.equals(id, other.id)
            && Objects.equals(nbtFingerprint, other.nbtFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id, meta, nbtFingerprint);
    }

    @Override
    public String toString() {
        return compact();
    }
}
