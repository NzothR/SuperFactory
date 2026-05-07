package com.nzoth.superfactory.common.process;

public final class TrackingItemStackHandler extends com.gtnewhorizons.modularui.api.forge.ItemStackHandler {

    private final Runnable changeAction;

    public TrackingItemStackHandler(int slots, Runnable changeAction) {
        super(slots);
        this.changeAction = changeAction;
    }

    @Override
    protected void onContentsChanged(int slot) {
        if (changeAction != null) {
            changeAction.run();
        }
    }
}
