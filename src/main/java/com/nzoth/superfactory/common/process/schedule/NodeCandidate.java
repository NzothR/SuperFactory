package com.nzoth.superfactory.common.process.schedule;

import com.nzoth.superfactory.common.process.ProcessNode;

public final class NodeCandidate {

    public final ProcessNode node;
    public final int actualParallel;
    public final CandidateLayer layer;
    public final double runCredit;
    public final int targetDistance;

    public NodeCandidate(ProcessNode node, int actualParallel, CandidateLayer layer, double runCredit,
        int targetDistance) {
        this.node = node;
        this.actualParallel = actualParallel;
        this.layer = layer;
        this.runCredit = runCredit;
        this.targetDistance = targetDistance;
    }
}
