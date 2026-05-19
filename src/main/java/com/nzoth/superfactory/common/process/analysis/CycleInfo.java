package com.nzoth.superfactory.common.process.analysis;

import java.util.Collections;
import java.util.List;

import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class CycleInfo {

    public final int cycleId;
    public final List<Integer> nodeIds;
    public final List<CycleMaterialInfo> candidateMaterials;
    public final MaterialKey cycleMaterial;
    public final boolean validSingleMaterialCycle;
    public final double producedRate;
    public final double consumedRate;
    public final double netRate;
    public final boolean positiveNetOutput;
    public final boolean hasStartupPath;
    public final List<MaterialKey> requiredStartupMaterials;

    public CycleInfo(int cycleId, List<Integer> nodeIds, List<CycleMaterialInfo> candidateMaterials,
        boolean hasStartupPath, List<MaterialKey> requiredStartupMaterials) {
        this.cycleId = cycleId;
        this.nodeIds = Collections.unmodifiableList(nodeIds);
        this.candidateMaterials = Collections.unmodifiableList(candidateMaterials);
        this.validSingleMaterialCycle = candidateMaterials.size() == 1;
        CycleMaterialInfo selected = validSingleMaterialCycle ? candidateMaterials.get(0) : null;
        this.cycleMaterial = selected == null ? null : selected.material;
        this.producedRate = selected == null ? 0.0D : selected.producedRate;
        this.consumedRate = selected == null ? 0.0D : selected.consumedRate;
        this.netRate = selected == null ? 0.0D : selected.netRate;
        this.positiveNetOutput = netRate > 0.0D;
        this.hasStartupPath = hasStartupPath;
        this.requiredStartupMaterials = Collections.unmodifiableList(requiredStartupMaterials);
    }
}
