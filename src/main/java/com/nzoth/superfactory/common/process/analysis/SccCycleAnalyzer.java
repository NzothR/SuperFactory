package com.nzoth.superfactory.common.process.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.key.MaterialKey;

final class SccCycleAnalyzer {

    private SccCycleAnalyzer() {}

    static List<CycleInfo> analyze(ProcessGraph graph, Map<Integer, ProcessNode> nodesById,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode,
        Map<Integer, Set<MaterialKey>> targetOutputsByNode, NodeRelationIndex relationIndex,
        GraphValidationResult validation) {
        Tarjan tarjan = new Tarjan(graph, relationIndex);
        List<List<Integer>> components = tarjan.components();
        List<CycleInfo> cycles = new ArrayList<>();
        int cycleId = 1;
        for (List<Integer> component : components) {
            if (!isCycleComponent(component, relationIndex)) {
                continue;
            }
            CycleInfo cycle = analyzeCycle(
                cycleId++,
                component,
                nodesById,
                outputsByNode,
                inputsByNode,
                targetOutputsByNode,
                relationIndex,
                validation);
            cycles.add(cycle);
        }
        return cycles;
    }

    private static boolean isCycleComponent(List<Integer> component, NodeRelationIndex relationIndex) {
        if (component.size() > 1) {
            return true;
        }
        int nodeId = component.get(0);
        for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.getOrDefault(nodeId, Collections.emptyList())) {
            if (edge.toNodeId == nodeId) {
                return true;
            }
        }
        return false;
    }

    private static CycleInfo analyzeCycle(int cycleId, List<Integer> component, Map<Integer, ProcessNode> nodesById,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode,
        Map<Integer, Set<MaterialKey>> targetOutputsByNode, NodeRelationIndex relationIndex,
        GraphValidationResult validation) {
        Set<Integer> componentSet = new LinkedHashSet<>(component);
        Set<MaterialKey> candidateMaterials = new LinkedHashSet<>();
        for (Integer nodeId : component) {
            ProcessNode from = nodesById.get(nodeId);
            for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.getOrDefault(nodeId, Collections.emptyList())) {
                if (!componentSet.contains(edge.toNodeId)) {
                    continue;
                }
                ProcessNode to = nodesById.get(edge.toNodeId);
                for (MaterialKey output : outputsByNode.getOrDefault(edge.fromNodeId, Collections.emptySet())) {
                    if (ProcessGraphAnalyzer.edgeCarriesMaterial(
                        edge,
                        from,
                        to,
                        output,
                        outputsByNode.getOrDefault(edge.fromNodeId, Collections.emptySet()),
                        inputsByNode.getOrDefault(edge.toNodeId, Collections.emptySet()))) {
                        candidateMaterials.add(output);
                    }
                }
            }
        }
        List<CycleMaterialInfo> infos = new ArrayList<>();
        for (MaterialKey material : candidateMaterials) {
            double produced = 0.0D;
            double consumed = 0.0D;
            for (Integer nodeId : component) {
                ProcessNode node = nodesById.get(nodeId);
                produced += ProcessGraphAnalyzer.producedRate(node, material);
                consumed += ProcessGraphAnalyzer.consumedRate(node, material);
            }
            infos.add(new CycleMaterialInfo(material, produced, consumed));
        }
        infos = selectCycleMaterials(component, infos, targetOutputsByNode, relationIndex, componentSet, outputsByNode);
        Set<MaterialKey> startupCandidateMaterials = new LinkedHashSet<>();
        for (CycleMaterialInfo info : infos) {
            startupCandidateMaterials.add(info.material);
        }
        boolean hasStartupPath = hasStartupPath(component, startupCandidateMaterials, inputsByNode);
        List<MaterialKey> requiredStartup = collectRequiredStartupMaterials(
            component,
            startupCandidateMaterials,
            inputsByNode,
            outputsByNode,
            relationIndex);
        CycleInfo cycle = new CycleInfo(cycleId, new ArrayList<>(component), infos, hasStartupPath, requiredStartup);
        if (!cycle.validSingleMaterialCycle) {
            validation.error(
                "CYCLE_MULTI_MATERIAL",
                "环 " + cycleId + " 循环物料数量不是 1: nodes=" + component + ", materials=" + candidateMaterials);
        } else if (!cycle.positiveNetOutput) {
            validation.error(
                "CYCLE_NON_POSITIVE_NET",
                "环 " + cycleId + " 循环物料没有正净输出: material=" + cycle.cycleMaterial + ", net=" + cycle.netRate);
        }
        if (!cycle.hasStartupPath && cycle.requiredStartupMaterials.isEmpty()) {
            validation.warning("CYCLE_STARTUP_UNKNOWN", "环 " + cycleId + " 未能推断启动路径。");
        }
        return cycle;
    }

    private static List<CycleMaterialInfo> selectCycleMaterials(List<Integer> component, List<CycleMaterialInfo> infos,
        Map<Integer, Set<MaterialKey>> targetOutputsByNode, NodeRelationIndex relationIndex, Set<Integer> componentSet,
        Map<Integer, Set<MaterialKey>> outputsByNode) {
        Set<MaterialKey> targetOutputs = new LinkedHashSet<>();
        for (Integer nodeId : component) {
            targetOutputs.addAll(targetOutputsByNode.getOrDefault(nodeId, Collections.emptySet()));
        }
        if (targetOutputs.isEmpty()) {
            List<CycleMaterialInfo> exportedPositiveNetMaterials = new ArrayList<>();
            for (CycleMaterialInfo info : infos) {
                if (info.netRate > 0.0D
                    && hasExternalExport(info.material, componentSet, relationIndex, outputsByNode)) {
                    exportedPositiveNetMaterials.add(info);
                }
            }
            if (!exportedPositiveNetMaterials.isEmpty()) {
                return exportedPositiveNetMaterials;
            }
            List<CycleMaterialInfo> positiveNetMaterials = new ArrayList<>();
            for (CycleMaterialInfo info : infos) {
                if (info.netRate > 0.0D) {
                    positiveNetMaterials.add(info);
                }
            }
            return positiveNetMaterials.isEmpty() ? infos : positiveNetMaterials;
        }
        List<CycleMaterialInfo> selected = new ArrayList<>();
        for (CycleMaterialInfo info : infos) {
            if (targetOutputs.contains(info.material)) {
                selected.add(info);
            }
        }
        return selected.isEmpty() ? infos : selected;
    }

    private static boolean hasExternalExport(MaterialKey material, Set<Integer> componentSet,
        NodeRelationIndex relationIndex, Map<Integer, Set<MaterialKey>> outputsByNode) {
        for (Integer nodeId : componentSet) {
            if (!outputsByNode.getOrDefault(nodeId, Collections.emptySet())
                .contains(material)) {
                continue;
            }
            for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.getOrDefault(nodeId, Collections.emptyList())) {
                if (!componentSet.contains(edge.toNodeId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasStartupPath(List<Integer> component, Set<MaterialKey> candidateMaterials,
        Map<Integer, Set<MaterialKey>> inputsByNode) {
        if (candidateMaterials.isEmpty()) {
            return false;
        }
        for (Integer nodeId : component) {
            Set<MaterialKey> inputs = inputsByNode.getOrDefault(nodeId, Collections.emptySet());
            boolean requiresCycleMaterial = false;
            for (MaterialKey material : candidateMaterials) {
                if (inputs.contains(material)) {
                    requiresCycleMaterial = true;
                    break;
                }
            }
            if (!requiresCycleMaterial) {
                return true;
            }
        }
        return false;
    }

    private static List<MaterialKey> collectRequiredStartupMaterials(List<Integer> component,
        Set<MaterialKey> candidateMaterials, Map<Integer, Set<MaterialKey>> inputsByNode,
        Map<Integer, Set<MaterialKey>> outputsByNode, NodeRelationIndex relationIndex) {
        List<MaterialKey> result = new ArrayList<>();
        if (candidateMaterials.size() != 1) {
            return result;
        }
        MaterialKey material = candidateMaterials.iterator()
            .next();
        Set<Integer> componentSet = new HashSet<>(component);
        for (Integer nodeId : component) {
            if (!inputsByNode.getOrDefault(nodeId, Collections.emptySet())
                .contains(material)) {
                continue;
            }
            if (hasExternalProducer(nodeId, material, componentSet, outputsByNode, relationIndex)) {
                addUniqueMaterial(result, material);
                continue;
            }
            addUniqueMaterial(result, material);
        }
        return result;
    }

    private static boolean hasExternalProducer(int nodeId, MaterialKey material, Set<Integer> componentSet,
        Map<Integer, Set<MaterialKey>> outputsByNode, NodeRelationIndex relationIndex) {
        for (ProcessEdge edge : relationIndex.incomingEdgesByNode.getOrDefault(nodeId, Collections.emptyList())) {
            if (componentSet.contains(edge.fromNodeId)) {
                continue;
            }
            if (outputsByNode.getOrDefault(edge.fromNodeId, Collections.emptySet())
                .contains(material)) {
                return true;
            }
        }
        return false;
    }

    private static void addUniqueMaterial(List<MaterialKey> result, MaterialKey material) {
        if (!result.contains(material)) {
            result.add(material);
        }
    }

    private static final class Tarjan {

        private final NodeRelationIndex relationIndex;
        private final Map<Integer, Integer> indexByNode = new HashMap<>();
        private final Map<Integer, Integer> lowLinkByNode = new HashMap<>();
        private final ArrayDeque<Integer> stack = new ArrayDeque<>();
        private final Set<Integer> onStack = new HashSet<>();
        private final List<List<Integer>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(ProcessGraph graph, NodeRelationIndex relationIndex) {
            this.relationIndex = relationIndex;
            for (ProcessNode node : graph.nodes) {
                if (!indexByNode.containsKey(node.id)) {
                    strongConnect(node.id);
                }
            }
        }

        private List<List<Integer>> components() {
            return components;
        }

        private void strongConnect(int nodeId) {
            indexByNode.put(nodeId, nextIndex);
            lowLinkByNode.put(nodeId, nextIndex);
            nextIndex++;
            stack.push(nodeId);
            onStack.add(nodeId);
            for (Integer next : relationIndex.directConsumersByNode.getOrDefault(nodeId, Collections.emptyList())) {
                if (!indexByNode.containsKey(next)) {
                    strongConnect(next);
                    lowLinkByNode.put(nodeId, Math.min(lowLinkByNode.get(nodeId), lowLinkByNode.get(next)));
                } else if (onStack.contains(next)) {
                    lowLinkByNode.put(nodeId, Math.min(lowLinkByNode.get(nodeId), indexByNode.get(next)));
                }
            }
            if (!lowLinkByNode.get(nodeId)
                .equals(indexByNode.get(nodeId))) {
                return;
            }
            List<Integer> component = new ArrayList<>();
            int current;
            do {
                current = stack.pop();
                onStack.remove(current);
                component.add(current);
            } while (current != nodeId);
            Collections.sort(component);
            components.add(component);
        }
    }
}
