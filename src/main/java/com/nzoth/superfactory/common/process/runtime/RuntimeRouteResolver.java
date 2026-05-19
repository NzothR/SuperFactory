package com.nzoth.superfactory.common.process.runtime;

import java.util.Collections;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.analysis.CycleInfo;
import com.nzoth.superfactory.common.process.analysis.GraphAnalysisResult;
import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class RuntimeRouteResolver {

    private final GraphAnalysisResult analysis;

    public RuntimeRouteResolver(GraphAnalysisResult analysis) {
        this.analysis = analysis;
    }

    public OutputRouteType resolve(int producerNodeId, MaterialKey material) {
        if (analysis == null || material == null) {
            return OutputRouteType.BYPRODUCT_OUTPUT;
        }
        if (isProducerInMaterialCycle(producerNodeId, material)) {
            return OutputRouteType.CYCLE_INTERNAL;
        }
        if (hasDirectConsumer(producerNodeId, material)) {
            return OutputRouteType.INTERNAL;
        }
        if (analysis.allTargetOutputs.contains(material)
            || analysis.targetOutputsByNode.getOrDefault(producerNodeId, Collections.emptySet())
                .contains(material)) {
            return OutputRouteType.TARGET_OUTPUT;
        }
        return OutputRouteType.BYPRODUCT_OUTPUT;
    }

    private boolean isProducerInMaterialCycle(int producerNodeId, MaterialKey material) {
        CycleInfo cycle = analysis.cycleByMaterial.get(material);
        return cycle != null && cycle.nodeIds.contains(producerNodeId);
    }

    private boolean hasDirectConsumer(int producerNodeId, MaterialKey material) {
        for (ProcessEdge edge : analysis.relationIndex.outgoingEdgesByNode
            .getOrDefault(producerNodeId, Collections.emptyList())) {
            if (analysis.nodesById.containsKey(edge.toNodeId)
                && analysis.relationIndex.consumerNodesByMaterial.getOrDefault(material, Collections.emptyList())
                    .contains(edge.toNodeId)) {
                return true;
            }
        }
        return false;
    }
}
