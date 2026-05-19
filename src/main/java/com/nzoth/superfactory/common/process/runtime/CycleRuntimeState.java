package com.nzoth.superfactory.common.process.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class CycleRuntimeState {

    public final int cycleId;
    public final MaterialKey cycleMaterial;
    public final Set<Integer> nodeIds;
    public long reserve;
    public long lowWater;
    public long highWater;

    public CycleRuntimeState(int cycleId, MaterialKey cycleMaterial, Set<Integer> nodeIds) {
        this.cycleId = cycleId;
        this.cycleMaterial = cycleMaterial;
        this.nodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(nodeIds));
    }

    public boolean containsNode(int nodeId) {
        return nodeIds.contains(nodeId);
    }
}
