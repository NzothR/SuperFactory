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

import net.minecraft.item.ItemStack;

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
            infos.add(cycleMaterialInfo(component, nodesById, material));
        }
        infos = selectCycleMaterials(
            component,
            infos,
            nodesById,
            targetOutputsByNode,
            relationIndex,
            componentSet,
            outputsByNode,
            inputsByNode);
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
                "环 " + cycleId
                    + " 循环物料数量不是 1: nodes="
                    + describeNodes(component, nodesById)
                    + ", materials="
                    + describeCycleInfoMaterials(component, nodesById, infos));
        } else if (!cycle.positiveNetOutput) {
            validation.error(
                "CYCLE_NON_POSITIVE_NET",
                "环 " + cycleId
                    + " 循环物料没有正净输出: material="
                    + describeMaterial(component, nodesById, cycle.cycleMaterial)
                    + ", net="
                    + cycle.netRate);
        }
        validateTargetCycle(
            cycle,
            component,
            nodesById,
            outputsByNode,
            inputsByNode,
            targetOutputsByNode,
            relationIndex,
            validation);
        if (!cycle.hasStartupPath && cycle.requiredStartupMaterials.isEmpty()) {
            validation.warning("CYCLE_STARTUP_UNKNOWN", "环 " + cycleId + " 未能推断启动路径。");
        }
        return cycle;
    }

    private static void validateTargetCycle(CycleInfo cycle, List<Integer> component,
        Map<Integer, ProcessNode> nodesById, Map<Integer, Set<MaterialKey>> outputsByNode,
        Map<Integer, Set<MaterialKey>> inputsByNode, Map<Integer, Set<MaterialKey>> targetOutputsByNode,
        NodeRelationIndex relationIndex, GraphValidationResult validation) {
        List<Integer> targetNodeIds = new ArrayList<>();
        for (Integer nodeId : component) {
            ProcessNode node = nodesById.get(nodeId);
            if (node != null && node.endNode) {
                targetNodeIds.add(nodeId);
            }
        }
        if (targetNodeIds.isEmpty()) {
            return;
        }
        if (targetNodeIds.size() != 1) {
            validation.error(
                "TARGET_CYCLE_MULTIPLE_TARGETS",
                "目标环 " + cycle.cycleId + " 只能包含一个目标节点: nodes=" + describeNodes(targetNodeIds, nodesById));
            return;
        }
        if (!cycle.validSingleMaterialCycle || cycle.cycleMaterial == null) {
            return;
        }
        Integer targetNodeId = targetNodeIds.get(0);
        ProcessNode targetNode = nodesById.get(targetNodeId);
        Set<Integer> componentSet = new HashSet<>(component);
        if (!targetHasInternalExport(
            targetNodeId,
            cycle.cycleMaterial,
            nodesById,
            relationIndex,
            componentSet,
            outputsByNode,
            inputsByNode)) {
            validation.error(
                "TARGET_CYCLE_TARGET_NOT_INTERNAL_OUTPUT",
                "目标环 " + cycle.cycleId
                    + " 的目标节点必须把循环物料输出给环内节点: node="
                    + describeNode(targetNode)
                    + ", material="
                    + describeMaterial(component, nodesById, cycle.cycleMaterial));
        }
        double producedRate = ProcessGraphAnalyzer.producedRate(targetNode, cycle.cycleMaterial);
        double consumedRate = ProcessGraphAnalyzer.consumedRate(targetNode, cycle.cycleMaterial);
        if (producedRate <= consumedRate) {
            validation.error(
                "TARGET_CYCLE_TARGET_NON_POSITIVE_NET",
                "目标环 " + cycle.cycleId
                    + " 的目标节点必须正净产出循环物料: node="
                    + describeNode(targetNode)
                    + ", material="
                    + describeMaterial(component, nodesById, cycle.cycleMaterial)
                    + ", producedRate="
                    + producedRate
                    + ", consumedRate="
                    + consumedRate);
        }
        if (targetCycleMaterialHasExternalConsumer(
            component,
            nodesById,
            outputsByNode,
            inputsByNode,
            relationIndex,
            cycle.cycleMaterial)) {
            validation.error(
                "TARGET_CYCLE_EXTERNAL_CONSUMER",
                "目标环 " + cycle.cycleId
                    + " 的循环物料不能被环外节点消耗: material="
                    + describeMaterial(component, nodesById, cycle.cycleMaterial));
        }
    }

    private static String describeNode(ProcessNode node) {
        if (node == null) {
            return "?";
        }
        String name = node.name == null || node.name.trim()
            .isEmpty() ? "节点" + node.id : node.name.trim();
        return name + "#" + node.id;
    }

    private static String describeNodes(List<Integer> nodeIds, Map<Integer, ProcessNode> nodesById) {
        List<String> names = new ArrayList<>();
        for (Integer nodeId : nodeIds) {
            names.add(describeNode(nodesById.get(nodeId)));
        }
        return names.toString();
    }

    private static String describeMaterials(List<Integer> component, Map<Integer, ProcessNode> nodesById,
        Iterable<MaterialKey> materials) {
        List<String> names = new ArrayList<>();
        for (MaterialKey material : materials) {
            names.add(describeMaterial(component, nodesById, material));
        }
        return names.toString();
    }

    private static String describeCycleInfoMaterials(List<Integer> component, Map<Integer, ProcessNode> nodesById,
        List<CycleMaterialInfo> infos) {
        List<MaterialKey> materials = new ArrayList<>();
        for (CycleMaterialInfo info : infos) {
            materials.add(info.material);
        }
        return describeMaterials(component, nodesById, materials);
    }

    private static String describeMaterial(List<Integer> component, Map<Integer, ProcessNode> nodesById,
        MaterialKey material) {
        String displayName = findMaterialDisplayName(component, nodesById, material);
        return displayName == null ? String.valueOf(material) : displayName + " (" + material + ")";
    }

    private static String findMaterialDisplayName(List<Integer> component, Map<Integer, ProcessNode> nodesById,
        MaterialKey material) {
        if (material == null) {
            return null;
        }
        for (Integer nodeId : component) {
            ProcessNode node = nodesById.get(nodeId);
            String name = findMaterialDisplayName(node, material, true);
            if (name != null) {
                return name;
            }
            name = findMaterialDisplayName(node, material, false);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private static String findMaterialDisplayName(ProcessNode node, MaterialKey material, boolean outputs) {
        if (node == null) {
            return null;
        }
        int slots = outputs ? node.outputHandler.getSlots() : node.inputHandler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = outputs ? node.outputHandler.getStackInSlot(slot)
                : node.inputHandler.getStackInSlot(slot);
            if (stack != null && material.equals(ProcessGraphAnalyzer.keyOf(stack))) {
                return stack.getDisplayName();
            }
        }
        return null;
    }

    private static boolean targetCycleMaterialHasExternalConsumer(List<Integer> component,
        Map<Integer, ProcessNode> nodesById, Map<Integer, Set<MaterialKey>> outputsByNode,
        Map<Integer, Set<MaterialKey>> inputsByNode, NodeRelationIndex relationIndex, MaterialKey material) {
        Set<Integer> componentSet = new HashSet<>(component);
        for (Integer nodeId : component) {
            if (!outputsByNode.getOrDefault(nodeId, Collections.emptySet())
                .contains(material)) {
                continue;
            }
            ProcessNode from = nodesById.get(nodeId);
            for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.getOrDefault(nodeId, Collections.emptyList())) {
                if (componentSet.contains(edge.toNodeId)) {
                    continue;
                }
                ProcessNode to = nodesById.get(edge.toNodeId);
                if (ProcessGraphAnalyzer.edgeCarriesMaterial(
                    edge,
                    from,
                    to,
                    material,
                    outputsByNode.getOrDefault(edge.fromNodeId, Collections.emptySet()),
                    inputsByNode.getOrDefault(edge.toNodeId, Collections.emptySet()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<CycleMaterialInfo> selectCycleMaterials(List<Integer> component, List<CycleMaterialInfo> infos,
        Map<Integer, ProcessNode> nodesById, Map<Integer, Set<MaterialKey>> targetOutputsByNode,
        NodeRelationIndex relationIndex, Set<Integer> componentSet, Map<Integer, Set<MaterialKey>> outputsByNode,
        Map<Integer, Set<MaterialKey>> inputsByNode) {
        List<CycleMaterialInfo> manualInfos = manuallySpecifiedCycleMaterials(component, infos, nodesById);
        if (!manualInfos.isEmpty()) {
            return manualInfos;
        }
        Set<MaterialKey> targetOutputs = new LinkedHashSet<>();
        for (Integer nodeId : component) {
            targetOutputs.addAll(targetOutputsByNode.getOrDefault(nodeId, Collections.emptySet()));
        }
        if (!targetOutputs.isEmpty()) {
            List<CycleMaterialInfo> selected = new ArrayList<>();
            for (CycleMaterialInfo info : infos) {
                if (targetOutputs.contains(info.material)) {
                    selected.add(info);
                }
            }
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        List<CycleMaterialInfo> targetInternalOutputs = targetInternalPositiveNetOutputs(
            component,
            infos,
            nodesById,
            targetOutputsByNode,
            relationIndex,
            componentSet,
            outputsByNode,
            inputsByNode);
        if (!targetInternalOutputs.isEmpty()) {
            return targetInternalOutputs;
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
        return infos;
    }

    private static CycleMaterialInfo cycleMaterialInfo(List<Integer> component, Map<Integer, ProcessNode> nodesById,
        MaterialKey material) {
        double produced = 0.0D;
        double consumed = 0.0D;
        for (Integer nodeId : component) {
            ProcessNode node = nodesById.get(nodeId);
            produced += ProcessGraphAnalyzer.producedRate(node, material);
            consumed += ProcessGraphAnalyzer.consumedRate(node, material);
        }
        return new CycleMaterialInfo(material, produced, consumed);
    }

    private static List<CycleMaterialInfo> manuallySpecifiedCycleMaterials(List<Integer> component,
        List<CycleMaterialInfo> infos, Map<Integer, ProcessNode> nodesById) {
        Set<MaterialKey> manualMaterials = new LinkedHashSet<>();
        for (Integer nodeId : component) {
            ProcessNode node = nodesById.get(nodeId);
            if (node == null) {
                continue;
            }
            MaterialKey material = ProcessGraphAnalyzer.keyOf(node.cycleMaterialHandler.getStackInSlot(0));
            if (material != null) {
                manualMaterials.add(material);
            }
        }
        if (manualMaterials.isEmpty()) {
            return Collections.emptyList();
        }
        List<CycleMaterialInfo> selected = new ArrayList<>();
        for (MaterialKey material : manualMaterials) {
            CycleMaterialInfo existing = findCycleMaterialInfo(infos, material);
            selected.add(existing == null ? cycleMaterialInfo(component, nodesById, material) : existing);
        }
        return selected;
    }

    private static CycleMaterialInfo findCycleMaterialInfo(List<CycleMaterialInfo> infos, MaterialKey material) {
        for (CycleMaterialInfo info : infos) {
            if (info.material.equals(material)) {
                return info;
            }
        }
        return null;
    }

    private static List<CycleMaterialInfo> targetInternalPositiveNetOutputs(List<Integer> component,
        List<CycleMaterialInfo> infos, Map<Integer, ProcessNode> nodesById,
        Map<Integer, Set<MaterialKey>> targetOutputsByNode, NodeRelationIndex relationIndex, Set<Integer> componentSet,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode) {
        List<Integer> targetNodeIds = new ArrayList<>();
        for (Integer nodeId : component) {
            ProcessNode node = nodesById.get(nodeId);
            if (node != null && node.endNode) {
                targetNodeIds.add(nodeId);
            }
        }
        if (targetNodeIds.size() != 1) {
            return Collections.emptyList();
        }
        int targetNodeId = targetNodeIds.get(0);
        ProcessNode target = nodesById.get(targetNodeId);
        List<CycleMaterialInfo> selected = new ArrayList<>();
        for (CycleMaterialInfo info : infos) {
            if (ProcessGraphAnalyzer.producedRate(target, info.material)
                <= ProcessGraphAnalyzer.consumedRate(target, info.material)) {
                continue;
            }
            if (targetHasInternalExport(
                targetNodeId,
                info.material,
                nodesById,
                relationIndex,
                componentSet,
                outputsByNode,
                inputsByNode)) {
                selected.add(info);
            }
        }
        return selected;
    }

    private static boolean targetHasInternalExport(int targetNodeId, MaterialKey material,
        Map<Integer, ProcessNode> nodesById, NodeRelationIndex relationIndex, Set<Integer> componentSet,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode) {
        ProcessNode target = nodesById.get(targetNodeId);
        for (ProcessEdge edge : relationIndex.outgoingEdgesByNode.getOrDefault(targetNodeId, Collections.emptyList())) {
            if (!componentSet.contains(edge.toNodeId)) {
                continue;
            }
            ProcessNode to = nodesById.get(edge.toNodeId);
            if (ProcessGraphAnalyzer.edgeCarriesMaterial(
                edge,
                target,
                to,
                material,
                outputsByNode.getOrDefault(edge.fromNodeId, Collections.emptySet()),
                inputsByNode.getOrDefault(edge.toNodeId, Collections.emptySet()))) {
                return true;
            }
        }
        return false;
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
