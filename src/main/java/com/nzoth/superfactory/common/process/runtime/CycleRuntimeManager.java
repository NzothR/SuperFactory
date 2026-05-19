package com.nzoth.superfactory.common.process.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import com.nzoth.superfactory.common.process.analysis.CycleInfo;
import com.nzoth.superfactory.common.process.analysis.GraphAnalysisResult;
import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class CycleRuntimeManager {

    public interface CycleMaterialNeedCalculator {

        long maxConsumerNeed(CycleInfo cycle);
    }

    private final Map<MaterialKey, CycleRuntimeState> statesByMaterial = new LinkedHashMap<>();

    public void rebuild(GraphAnalysisResult analysis, CycleMaterialNeedCalculator calculator) {
        statesByMaterial.clear();
        if (analysis == null || calculator == null) {
            return;
        }
        for (CycleInfo cycle : analysis.cycles) {
            if (cycle.cycleMaterial == null || !cycle.validSingleMaterialCycle || !cycle.positiveNetOutput) {
                continue;
            }
            CycleRuntimeState state = new CycleRuntimeState(
                cycle.cycleId,
                cycle.cycleMaterial,
                new LinkedHashSet<>(cycle.nodeIds));
            state.reserve = Math.max(1L, calculator.maxConsumerNeed(cycle));
            state.lowWater = state.reserve;
            state.highWater = Math.max(state.reserve + 1L, saturatingMultiply(state.reserve, 3L));
            statesByMaterial.put(cycle.cycleMaterial, state);
        }
    }

    public CycleRuntimeState get(MaterialKey material) {
        return material == null ? null : statesByMaterial.get(material);
    }

    public boolean isCycleMaterial(MaterialKey material) {
        return get(material) != null;
    }

    public long consumableAmountForNode(int consumerNodeId, MaterialKey material, long stored) {
        CycleRuntimeState state = get(material);
        if (state == null || state.containsNode(consumerNodeId)) {
            return stored;
        }
        return Math.max(0L, stored - state.reserve);
    }

    public long overflowAmount(MaterialKey material, long stored) {
        CycleRuntimeState state = get(material);
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, stored - state.highWater);
    }

    private static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }
}
