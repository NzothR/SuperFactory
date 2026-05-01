package com.nzoth.superfactory.common.recipe;

public final class RuntimeTransform {

    private RuntimeTransform() {}

    /**
     * Applies user-facing runtime clamps after overclocking, parallel, and batch planning.
     *
     * <p>
     * The maximum runtime is intentionally a final override only; it must not take part in power validation or batch
     * size selection.
     */
    public static int clampFinalDuration(int duration, long minimumRuntime, long maximumRuntime) {
        long transformed = Math.max(1L, duration);
        if (minimumRuntime > 0L) {
            transformed = Math.max(transformed, minimumRuntime);
        }
        if (maximumRuntime > 0L) {
            transformed = Math.min(transformed, maximumRuntime);
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, transformed));
    }
}
