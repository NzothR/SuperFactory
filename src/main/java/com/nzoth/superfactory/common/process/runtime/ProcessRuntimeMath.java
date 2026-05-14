package com.nzoth.superfactory.common.process.runtime;

/**
 * Saturating arithmetic helpers for process runtime counters.
 *
 * <p>
 * The integrated factory may aggregate many virtual jobs before a stack is physically inserted into a hatch. These
 * helpers keep those counters in {@code long} space and clamp overflow to {@link Long#MAX_VALUE} instead of wrapping.
 */
public final class ProcessRuntimeMath {

    private ProcessRuntimeMath() {}

    public static long safeMultiply(long a, long b) {
        if (a > 0L && b > Long.MAX_VALUE / a) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    public static long safeAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    public static long safeCeilMultiply(long value, long numerator, long denominator) {
        if (denominator <= 0L) {
            return Long.MAX_VALUE;
        }
        long product = safeMultiply(Math.max(0L, value), Math.max(0L, numerator));
        if (product == Long.MAX_VALUE) {
            return product;
        }
        long roundingOffset = denominator - 1L;
        if (roundingOffset > 0L && product > Long.MAX_VALUE - roundingOffset) {
            return Long.MAX_VALUE;
        }
        return (product + roundingOffset) / denominator;
    }
}
