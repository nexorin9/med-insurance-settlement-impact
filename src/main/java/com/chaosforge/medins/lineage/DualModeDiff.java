package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 双口径差异输出：保守口径比严格口径多出的字段集合。
 *
 * <p>改一个筛选条件列会改变下游口径却不改变下游取值，这两种影响必须分开呈现。
 * 多出的集合恰好是「必须经由 FILTER 边才能到达」的字段，供人工只看差异部分。
 */
public final class DualModeDiff {

    private DualModeDiff() { }

    public static Set<FieldKey> extraConservativeFields(FieldLineageGraph g, FieldKey seed) {
        Set<FieldKey> strict = DownstreamReachability.reachable(g, seed, ImpactScope.STRICT).fields();
        Set<FieldKey> conservative = DownstreamReachability.reachable(g, seed, ImpactScope.CONSERVATIVE).fields();
        LinkedHashSet<FieldKey> extra = new LinkedHashSet<>(conservative);
        extra.removeAll(strict);
        return extra;
    }
}
