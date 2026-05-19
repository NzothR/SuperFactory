package com.nzoth.superfactory.common.process.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.key.MaterialKey;

import gregtech.api.util.GTUtility;

public final class ProcessGraphAnalyzer {

    public GraphAnalysisResult analyze(ProcessGraph graph) {
        GraphValidationResult validation = new GraphValidationResult();
        Map<Integer, ProcessNode> nodesById = buildNodeIndex(graph, validation);
        Map<Integer, Set<MaterialKey>> outputsByNode = buildNodeMaterialIndex(graph, true);
        Map<Integer, Set<MaterialKey>> inputsByNode = buildNodeMaterialIndex(graph, false);
        NodeRelationIndex relationIndex = buildRelationIndex(graph, nodesById, outputsByNode, inputsByNode, validation);
        Map<Integer, Set<MaterialKey>> targetOutputsByNode = TargetOutputResolver
            .resolve(graph, relationIndex, outputsByNode, inputsByNode, validation);
        Set<MaterialKey> allTargetOutputs = new LinkedHashSet<>();
        for (Set<MaterialKey> outputs : targetOutputsByNode.values()) {
            allTargetOutputs.addAll(outputs);
        }
        List<Integer> sourceNodeIds = findSourceNodes(graph, relationIndex);
        List<Integer> sinkNodeIds = findSinkNodes(graph, relationIndex);
        List<CycleInfo> cycles = SccCycleAnalyzer
            .analyze(graph, nodesById, outputsByNode, inputsByNode, targetOutputsByNode, relationIndex, validation);
        Map<Integer, CycleInfo> cycleByNodeId = new LinkedHashMap<>();
        Map<MaterialKey, CycleInfo> cycleByMaterial = new LinkedHashMap<>();
        for (CycleInfo cycle : cycles) {
            for (Integer nodeId : cycle.nodeIds) {
                cycleByNodeId.put(nodeId, cycle);
            }
            if (cycle.cycleMaterial != null) {
                CycleInfo existing = cycleByMaterial.put(cycle.cycleMaterial, cycle);
                if (existing != null && existing != cycle) {
                    validation.error("CYCLE_SHARED_MATERIAL", "多个环共享循环物料暂不支持: " + cycle.cycleMaterial);
                }
            }
        }
        return new GraphAnalysisResult(
            graph,
            nodesById,
            relationIndex,
            targetOutputsByNode,
            allTargetOutputs,
            sourceNodeIds,
            sinkNodeIds,
            cycles,
            cycleByNodeId,
            cycleByMaterial,
            validation);
    }

    private static Map<Integer, ProcessNode> buildNodeIndex(ProcessGraph graph, GraphValidationResult validation) {
        Map<Integer, ProcessNode> nodesById = new LinkedHashMap<>();
        for (ProcessNode node : graph.nodes) {
            ProcessNode previous = nodesById.put(node.id, node);
            if (previous != null) {
                validation.error("DUPLICATE_NODE_ID", "节点 ID 重复: " + node.id);
            }
            if (!node.locked || !node.lastRecipeCheckPassed) {
                validation.warning("NODE_NOT_READY", "节点未锁定或配方检查未通过: " + describeNode(node));
            }
        }
        return nodesById;
    }

    private static Map<Integer, Set<MaterialKey>> buildNodeMaterialIndex(ProcessGraph graph, boolean outputs) {
        Map<Integer, Set<MaterialKey>> index = new LinkedHashMap<>();
        for (ProcessNode node : graph.nodes) {
            Set<MaterialKey> materials = new LinkedHashSet<>();
            int slots = outputs ? node.outputHandler.getSlots() : node.inputHandler.getSlots();
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = outputs ? node.outputHandler.getStackInSlot(slot)
                    : node.inputHandler.getStackInSlot(slot);
                MaterialKey key = keyOf(stack);
                if (key != null) {
                    materials.add(key);
                }
            }
            index.put(node.id, materials);
        }
        return index;
    }

    private static NodeRelationIndex buildRelationIndex(ProcessGraph graph, Map<Integer, ProcessNode> nodesById,
        Map<Integer, Set<MaterialKey>> outputsByNode, Map<Integer, Set<MaterialKey>> inputsByNode,
        GraphValidationResult validation) {
        Map<Integer, List<ProcessEdge>> outgoingEdgesByNode = new LinkedHashMap<>();
        Map<Integer, List<ProcessEdge>> incomingEdgesByNode = new LinkedHashMap<>();
        Map<Integer, List<Integer>> directConsumersByNode = new LinkedHashMap<>();
        Map<Integer, List<Integer>> directProducersByNode = new LinkedHashMap<>();
        for (ProcessNode node : graph.nodes) {
            outgoingEdgesByNode.put(node.id, new ArrayList<>());
            incomingEdgesByNode.put(node.id, new ArrayList<>());
            directConsumersByNode.put(node.id, new ArrayList<>());
            directProducersByNode.put(node.id, new ArrayList<>());
        }
        for (ProcessEdge edge : graph.edges) {
            ProcessNode from = nodesById.get(edge.fromNodeId);
            ProcessNode to = nodesById.get(edge.toNodeId);
            if (from == null || to == null) {
                validation.error(
                    "EDGE_MISSING_NODE",
                    "边连接的节点不存在: edge=" + edge.id + ", from=" + edge.fromNodeId + ", to=" + edge.toNodeId);
                continue;
            }
            outgoingEdgesByNode.get(edge.fromNodeId)
                .add(edge);
            incomingEdgesByNode.get(edge.toNodeId)
                .add(edge);
            addUnique(directConsumersByNode.get(edge.fromNodeId), edge.toNodeId);
            addUnique(directProducersByNode.get(edge.toNodeId), edge.fromNodeId);
            MaterialKey edgeKey = MaterialKey.parse(edge.resourceKey);
            if (edgeKey != null && (!outputsByNode.get(edge.fromNodeId)
                .contains(edgeKey)
                || !inputsByNode.get(edge.toNodeId)
                    .contains(edgeKey))) {
                validation.warning("EDGE_MATERIAL_UNMATCHED", "边物料未同时出现在源输出和目标输入: edge=" + edge.id);
            }
        }
        Map<MaterialKey, List<Integer>> producerNodesByMaterial = buildMaterialNodeIndex(outputsByNode);
        Map<MaterialKey, List<Integer>> consumerNodesByMaterial = buildMaterialNodeIndex(inputsByNode);
        return new NodeRelationIndex(
            outgoingEdgesByNode,
            incomingEdgesByNode,
            directConsumersByNode,
            directProducersByNode,
            producerNodesByMaterial,
            consumerNodesByMaterial);
    }

    private static Map<MaterialKey, List<Integer>> buildMaterialNodeIndex(Map<Integer, Set<MaterialKey>> byNode) {
        Map<MaterialKey, List<Integer>> index = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<MaterialKey>> entry : byNode.entrySet()) {
            for (MaterialKey key : entry.getValue()) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }
        return index;
    }

    private static List<Integer> findSourceNodes(ProcessGraph graph, NodeRelationIndex relationIndex) {
        List<Integer> result = new ArrayList<>();
        for (ProcessNode node : graph.nodes) {
            if (relationIndex.incomingEdgesByNode.getOrDefault(node.id, Collections.emptyList())
                .isEmpty()) {
                result.add(node.id);
            }
        }
        return result;
    }

    private static List<Integer> findSinkNodes(ProcessGraph graph, NodeRelationIndex relationIndex) {
        List<Integer> result = new ArrayList<>();
        for (ProcessNode node : graph.nodes) {
            if (relationIndex.outgoingEdgesByNode.getOrDefault(node.id, Collections.emptyList())
                .isEmpty()) {
                result.add(node.id);
            }
        }
        return result;
    }

    static boolean edgeCarriesMaterial(ProcessEdge edge, ProcessNode from, ProcessNode to, MaterialKey material,
        Set<MaterialKey> fromOutputs, Set<MaterialKey> toInputs) {
        MaterialKey edgeKey = MaterialKey.parse(edge.resourceKey);
        if (edgeKey != null) {
            return edgeKey.equals(material);
        }
        return fromOutputs.contains(material) && toInputs.contains(material);
    }

    static double producedRate(ProcessNode node, MaterialKey material) {
        return materialRate(node, material, true);
    }

    static double consumedRate(ProcessNode node, MaterialKey material) {
        return materialRate(node, material, false);
    }

    private static double materialRate(ProcessNode node, MaterialKey material, boolean outputs) {
        double amount = 0.0D;
        int slots = outputs ? node.outputHandler.getSlots() : node.inputHandler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = outputs ? node.outputHandler.getStackInSlot(slot)
                : node.inputHandler.getStackInSlot(slot);
            MaterialKey key = keyOf(stack);
            if (material.equals(key)) {
                amount += Math.max(0L, ProcessNode.getDisplayAmount(stack));
            }
        }
        int duration = Math.max(1, node.baseDurationTicks > 0 ? node.baseDurationTicks : node.durationTicks);
        int parallel = Math.max(1, node.parallelLimit);
        return amount * parallel / duration;
    }

    static MaterialKey keyOf(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null) {
            return MaterialKey.ofFluid(fluid);
        }
        return MaterialKey.ofItem(stack);
    }

    static String describeNode(ProcessNode node) {
        return node == null ? "null" : node.id + "/" + (node.name == null ? "" : node.name);
    }

    private static void addUnique(List<Integer> list, int value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    static boolean canReachAny(Set<Integer> starts, Set<Integer> targets, NodeRelationIndex relationIndex) {
        ArrayDeque<Integer> queue = new ArrayDeque<>(starts);
        Set<Integer> visited = new HashSet<>(starts);
        while (!queue.isEmpty()) {
            int nodeId = queue.removeFirst();
            if (targets.contains(nodeId)) {
                return true;
            }
            for (Integer next : relationIndex.directConsumersByNode.getOrDefault(nodeId, Collections.emptyList())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }
}
