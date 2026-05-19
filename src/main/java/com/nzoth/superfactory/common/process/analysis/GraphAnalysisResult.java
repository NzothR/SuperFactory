package com.nzoth.superfactory.common.process.analysis;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class GraphAnalysisResult {

    public final ProcessGraph graph;
    public final Map<Integer, ProcessNode> nodesById;
    public final NodeRelationIndex relationIndex;
    public final Map<Integer, Set<MaterialKey>> targetOutputsByNode;
    public final Set<MaterialKey> allTargetOutputs;
    public final List<Integer> sourceNodeIds;
    public final List<Integer> sinkNodeIds;
    public final List<CycleInfo> cycles;
    public final Map<Integer, CycleInfo> cycleByNodeId;
    public final Map<MaterialKey, CycleInfo> cycleByMaterial;
    public final GraphValidationResult validation;

    public GraphAnalysisResult(ProcessGraph graph, Map<Integer, ProcessNode> nodesById, NodeRelationIndex relationIndex,
        Map<Integer, Set<MaterialKey>> targetOutputsByNode, Set<MaterialKey> allTargetOutputs,
        List<Integer> sourceNodeIds, List<Integer> sinkNodeIds, List<CycleInfo> cycles,
        Map<Integer, CycleInfo> cycleByNodeId, Map<MaterialKey, CycleInfo> cycleByMaterial,
        GraphValidationResult validation) {
        this.graph = graph;
        this.nodesById = Collections.unmodifiableMap(nodesById);
        this.relationIndex = relationIndex;
        this.targetOutputsByNode = Collections.unmodifiableMap(targetOutputsByNode);
        this.allTargetOutputs = Collections.unmodifiableSet(allTargetOutputs);
        this.sourceNodeIds = Collections.unmodifiableList(sourceNodeIds);
        this.sinkNodeIds = Collections.unmodifiableList(sinkNodeIds);
        this.cycles = Collections.unmodifiableList(cycles);
        this.cycleByNodeId = Collections.unmodifiableMap(cycleByNodeId);
        this.cycleByMaterial = Collections.unmodifiableMap(cycleByMaterial);
        this.validation = validation;
    }
}
