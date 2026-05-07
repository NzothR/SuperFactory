package com.nzoth.superfactory.common.ui.widget;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import com.gtnewhorizons.modularui.api.forge.IItemHandlerModifiable;
import com.gtnewhorizons.modularui.common.widget.SlotWidget;

public class RecipePatternSlotWidget extends SlotWidget {

    private static final int ACTION_EDIT_AMOUNT = 20;

    private final Runnable middleClickAction;
    private final int amountWindowId;
    private BooleanSupplier lockedSupplier = () -> false;
    private Runnable changeAction;

    public RecipePatternSlotWidget(IItemHandlerModifiable handler, int index, Runnable middleClickAction,
        int amountWindowId) {
        super(com.gtnewhorizons.modularui.common.internal.wrapper.BaseSlot.phantom(handler, index));
        this.middleClickAction = middleClickAction;
        this.amountWindowId = amountWindowId;
    }

    public RecipePatternSlotWidget setLockedSupplier(BooleanSupplier lockedSupplier) {
        this.lockedSupplier = lockedSupplier == null ? () -> false : lockedSupplier;
        return this;
    }

    public RecipePatternSlotWidget setChangeAction(Runnable changeAction) {
        this.changeAction = changeAction;
        return this;
    }

    @Override
    public boolean onMouseScroll(int direction) {
        return false;
    }

    @Override
    public void draw(float partialTicks) {
        super.draw(partialTicks);
        if (lockedSupplier.getAsBoolean()) {
            drawLockedOverlay();
        }
    }

    @Override
    protected void phantomClick(ClickData clickData, ItemStack cursorStack) {
        if (lockedSupplier.getAsBoolean()) {
            return;
        }
        if (clickData.mouseButton == 2) {
            if (isClient()) {
                syncToServer(ACTION_EDIT_AMOUNT, buffer -> {});
            } else if (middleClickAction != null) {
                middleClickAction.run();
            }
            return;
        }
        if (clickData.mouseButton == 1 && cursorStack == null) {
            getMcSlot().putStack(null);
            onChanged();
            return;
        }
        if (cursorStack != null) {
            putClickedStack(cursorStack.copy(), clickData.mouseButton);
            onChanged();
        }
    }

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        if (lockedSupplier.getAsBoolean()) {
            return;
        }
        if (id == ACTION_EDIT_AMOUNT) {
            if (middleClickAction != null) {
                middleClickAction.run();
            }
            if (amountWindowId >= 0) {
                getContext().openSyncedWindow(amountWindowId);
            }
            return;
        }
        super.readOnServer(id, buf);
    }

    private void onChanged() {
        if (changeAction != null) {
            changeAction.run();
        }
    }

    private void drawLockedOverlay() {
        com.gtnewhorizons.modularui.api.drawable.GuiHelper
            .drawGradientRect(0, 0, 0, getSize().width, getSize().height, 0xAA111111, 0xAA111111);
        net.minecraft.client.Minecraft.getMinecraft().fontRenderer.drawString(
            "\u00a7fL",
            Math.max(1, getSize().width / 2 - 3),
            Math.max(1, getSize().height / 2 - 4),
            0xFFFFFFFF);
    }
}
