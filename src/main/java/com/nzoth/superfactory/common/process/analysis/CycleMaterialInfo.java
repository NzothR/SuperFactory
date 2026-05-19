package com.nzoth.superfactory.common.process.analysis;

import com.nzoth.superfactory.common.process.key.MaterialKey;

public final class CycleMaterialInfo {

    public final MaterialKey material;
    public final double producedRate;
    public final double consumedRate;
    public final double netRate;

    public CycleMaterialInfo(MaterialKey material, double producedRate, double consumedRate) {
        this.material = material;
        this.producedRate = producedRate;
        this.consumedRate = consumedRate;
        this.netRate = producedRate - consumedRate;
    }
}
