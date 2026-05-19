package com.nzoth.superfactory.common.process.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.nzoth.superfactory.common.process.ProcessNode;

public final class IntegratedFactoryScheduler {

    private IntegratedFactoryScheduler() {}

    public static int schedule(Context context, boolean debugRuntime) {
        context.updateRunCredits();
        int starts = 0;
        for (CandidateLayer layer : CandidateLayer.values()) {
            for (NodeCandidate candidate : buildNodeCandidates(context, layer, debugRuntime)) {
                if (starts >= context.maxNodeStartsPerTick()) {
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

    private static List<NodeCandidate> buildNodeCandidates(Context context, CandidateLayer layer,
        boolean debugRuntime) {
        ArrayList<NodeCandidate> candidates = new ArrayList<>();
        for (ProcessNode node : context.schedulingOrder()) {
            if (context.runningJobsForNode(node.id) > 0) {
                continue;
            }
            int effectiveParallelLimit = context.effectiveParallelLimit(node);
            int effectiveDurationTicks = context.effectiveDurationTicks(node);
            if (!node.locked || effectiveParallelLimit <= 0 || effectiveDurationTicks <= 0) {
                continue;
            }
            if (classifyCandidateLayer(context, node) != layer
                || context.isExternalOutputThrottled(node, effectiveParallelLimit, debugRuntime)) {
                continue;
            }
            int parallel = context.runnableParallel(node, effectiveParallelLimit, debugRuntime);
            if (parallel > 0) {
                candidates.add(
                    new NodeCandidate(
                        node,
                        parallel,
                        layer,
                        context.runCredit(node.id),
                        context.distanceToTerminal(node)));
            }
        }
        candidates.sort(
            Comparator.comparingDouble((NodeCandidate candidate) -> -candidate.runCredit)
                .thenComparingInt(candidate -> candidate.targetDistance)
                .thenComparingInt(candidate -> candidate.node.id));
        return candidates;
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
