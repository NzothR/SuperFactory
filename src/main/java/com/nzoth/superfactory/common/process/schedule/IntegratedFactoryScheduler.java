package com.nzoth.superfactory.common.process.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

import com.nzoth.superfactory.common.process.ProcessNode;

public final class IntegratedFactoryScheduler {

    private IntegratedFactoryScheduler() {}

    /**
     * Per-tick scheduling entry point.
     * <p>
     * Optimized: builds all candidate buckets in ONE pass over the scheduling order,
     * then tries to start nodes layer by layer. Previously each CandidateLayer
     * triggered a full node scan (5× for 5 layers); now it's 1×.
     */
    public static int schedule(Context context, boolean debugRuntime) {
        context.updateRunCredits();

        // Phase 1: one scan, classify each node once, bucket by layer.
        EnumMap<CandidateLayer, ArrayList<NodeCandidate>> buckets = buildCandidateBuckets(context, debugRuntime);

        // Phase 2: try to start candidates layer-by-layer.
        int starts = 0;
        int maxStarts = context.maxNodeStartsPerTick();
        for (CandidateLayer layer : CandidateLayer.values()) {
            List<NodeCandidate> candidates = buckets.get(layer);
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            candidates.sort(CANDIDATE_ORDER);
            for (NodeCandidate candidate : candidates) {
                if (starts >= maxStarts) {
                    return starts;
                }
                if (context.tryStartNodeCandidate(candidate, debugRuntime)) {
                    starts++;
                    context.subtractRunCredit(candidate.node.id, 1.0D);
                }
            }
        }
        return starts;
    }

    private static EnumMap<CandidateLayer, ArrayList<NodeCandidate>> buildCandidateBuckets(Context context,
        boolean debugRuntime) {
        EnumMap<CandidateLayer, ArrayList<NodeCandidate>> buckets = new EnumMap<>(CandidateLayer.class);
        for (CandidateLayer layer : CandidateLayer.values()) {
            buckets.put(layer, new ArrayList<>());
        }

        for (ProcessNode node : context.schedulingOrder()) {
            if (context.runningJobsForNode(node.id) > 0) {
                continue;
            }
            if (!node.locked) {
                continue;
            }
            int effectiveParallelLimit = context.effectiveParallelLimit(node);
            int effectiveDurationTicks = context.effectiveDurationTicks(node);
            if (effectiveParallelLimit <= 0 || effectiveDurationTicks <= 0) {
                continue;
            }
            CandidateLayer layer = classifyCandidateLayer(context, node);
            if (context.isExternalOutputThrottled(node, effectiveParallelLimit, debugRuntime)) {
                continue;
            }
            int parallel = context.runnableParallel(node, effectiveParallelLimit, debugRuntime);
            if (parallel <= 0) {
                continue;
            }
            int distance = context.distanceToTerminal(node);
            double credit = context.runCredit(node.id);
            buckets.get(layer)
                .add(new NodeCandidate(node, parallel, layer, credit, distance));
        }
        return buckets;
    }

    private static CandidateLayer classifyCandidateLayer(Context context, ProcessNode node) {
        if (context.consumesAvailableInternalInput(node)) {
            return CandidateLayer.INTERNAL_CONSUME;
        }
        if (context.suppliesLowWater(node)) {
            return CandidateLayer.LOW_WATER_SUPPLY;
        }
        if (context.producesTargetOutput(node)) {
            return CandidateLayer.TARGET_PROGRESS;
        }
        return CandidateLayer.SOURCE_PRODUCTION;
    }

    private static final Comparator<NodeCandidate> CANDIDATE_ORDER = Comparator
        .comparingDouble((NodeCandidate c) -> -c.runCredit)
        .thenComparingInt(c -> c.targetDistance)
        .thenComparingInt(c -> c.node.id);

    public interface Context {

        List<ProcessNode> schedulingOrder();

        int runningJobsForNode(int nodeId);

        int effectiveParallelLimit(ProcessNode node);

        int effectiveDurationTicks(ProcessNode node);

        boolean isExternalOutputThrottled(ProcessNode node, int parallel, boolean debugRuntime);

        int runnableParallel(ProcessNode node, int parallelLimit, boolean debugRuntime);

        boolean tryStartNodeCandidate(NodeCandidate candidate, boolean debugRuntime);

        int maxNodeStartsPerTick();

        void updateRunCredits();

        double runCredit(int nodeId);

        void subtractRunCredit(int nodeId, double amount);

        int distanceToTerminal(ProcessNode node);

        boolean consumesAvailableInternalInput(ProcessNode node);

        boolean suppliesLowWater(ProcessNode node);

        boolean producesTargetOutput(ProcessNode node);
    }
}
