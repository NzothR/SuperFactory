package com.nzoth.superfactory.common.process.analysis;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class NodeRelationIndex {

    public final Map<Integer, List<ProcessEdge>> outgoingEdgesByNode;
    public final Map<Integer, List<ProcessEdge>> incomingEdgesByNode;
    public final Map<Integer, List<Integer>> directConsumersByNode;
    public final Map<Integer, List<Integer>> directProducersByNode;
    public final Map<MaterialKey, List<Integer>> producerNodesByMaterial;
    public final Map<MaterialKey, List<Integer>> consumerNodesByMaterial;

    public NodeRelationIndex(Map<Integer, List<ProcessEdge>> outgoingEdgesByNode,
        Map<Integer, List<ProcessEdge>> incomingEdgesByNode, Map<Integer, List<Integer>> directConsumersByNode,
        Map<Integer, List<Integer>> directProducersByNode, Map<MaterialKey, List<Integer>> producerNodesByMaterial,
        Map<MaterialKey, List<Integer>> consumerNodesByMaterial) {
        this.outgoingEdgesByNode = Collections.unmodifiableMap(outgoingEdgesByNode);
        this.incomingEdgesByNode = Collections.unmodifiableMap(incomingEdgesByNode);
        this.directConsumersByNode = Collections.unmodifiableMap(directConsumersByNode);
        this.directProducersByNode = Collections.unmodifiableMap(directProducersByNode);
        this.producerNodesByMaterial = Collections.unmodifiableMap(producerNodesByMaterial);
        this.consumerNodesByMaterial = Collections.unmodifiableMap(consumerNodesByMaterial);
    }
}
