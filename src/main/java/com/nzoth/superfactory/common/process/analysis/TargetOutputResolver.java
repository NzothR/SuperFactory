package com.nzoth.superfactory.common.process.analysis;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.key.MaterialKey;

final class TargetOutputResolver {

    private TargetOutputResolver() {}

    static Map<Integer, Set<MaterialKey>> resolve(ProcessGraph graph, NodeRelationIndex relationIndex,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode,
        GraphValidationResult validation) {
        Set<Integer> cycleNodeIds = findCycleNodeIds(graph, relationIndex);
        Map<Integer, Set<MaterialKey>> result = new LinkedHashMap<>();
        for (ProcessNode node : graph.nodes) {
            if (!node.endNode) {
                continue;
            }
            Set<MaterialKey> outputs = outputsByNode.get(node.id);
            Set<MaterialKey> targets = new LinkedHashSet<>();
            if (relationIndex.outgoingEdgesByNode.get(node.id)
                .isEmpty()) {
                targets.addAll(outputs);
            } else {
                for (MaterialKey output : outputs) {
                    if (!isConsumedByDirectDownstream(node, output, graph, relationIndex, inputsByNode, outputs)) {
                        targets.add(output);
                    }
                }
            }
            if (targets.isEmpty() && cycleNodeIds.contains(node.id)) {
                for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.get(node.id)) {
                    if (!cycleNodeIds.contains(edge.toNodeId)) {
                        continue;
                    }
                    MaterialKey edgeKey = MaterialKey.parse(edge.resourceKey);
                    if (edgeKey != null && outputs.contains(edgeKey)) {
                        targets.add(edgeKey);
                        continue;
                    }
                    Set<MaterialKey> downstreamInputs = inputsByNode.get(edge.toNodeId);
                    for (MaterialKey output : outputs) {
                        if (downstreamInputs != null && downstreamInputs.contains(output)) {
                            targets.add(output);
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                validation.warning(
                    "TARGET_NODE_WITHOUT_OUTPUT",
                    "目标产物节点无法自动推断出目标产物: " + ProcessGraphAnalyzer.describeNode(node));
            }
            result.put(node.id, targets);
        }
        if (result.isEmpty() && !graph.nodes.isEmpty()) {
            validation.warning("NO_TARGET_NODE", "工序图没有目标产物节点。");
        }
        return result;
    }

    private static Set<Integer> findCycleNodeIds(ProcessGraph graph, NodeRelationIndex relationIndex) {
        Set<Integer> cycleNodeIds = new LinkedHashSet<>();
        for (ProcessNode node : graph.nodes) {
            if (isInCycle(node.id, relationIndex)) {
                cycleNodeIds.add(node.id);
            }
        }
        return cycleNodeIds;
    }

    private static boolean isInCycle(int nodeId, NodeRelationIndex relationIndex) {
        for (Integer next : relationIndex.directConsumersByNode.get(nodeId)) {
            if (next == nodeId || canReach(next, nodeId, relationIndex, new LinkedHashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean canReach(int current, int target, NodeRelationIndex relationIndex, Set<Integer> visited) {
        if (!visited.add(current)) {
            return false;
        }
        if (current == target) {
            return true;
        }
        for (Integer next : relationIndex.directConsumersByNode.get(current)) {
            if (canReach(next, target, relationIndex, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConsumedByDirectDownstream(ProcessNode node, MaterialKey output, ProcessGraph graph,
        NodeRelationIndex relationIndex, Map<Integer, Set<MaterialKey>> inputsByNode, Set<MaterialKey> outputs) {
        for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.get(node.id)) {
            Set<MaterialKey> downstreamInputs = inputsByNode.get(edge.toNodeId);
            if (downstreamInputs == null || !downstreamInputs.contains(output)) {
                continue;
            }
            MaterialKey edgeKey = MaterialKey.parse(edge.resourceKey);
            if (edgeKey == null || edgeKey.equals(output)) {
                return true;
            }
        }
        return false;
    }
}
