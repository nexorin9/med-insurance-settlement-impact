package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.sqlparse.EdgeConfidencePolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下游可达子集结果：受影响字段集合、每字段跳数、最短路径、环标记。
 *
 * <p>fields 为 {@link LinkedHashSet}（BFS 入队顺序，同序稳定，不含 seed）。
 * needsHumanConfirmation 为跳数 ≥ 3 的字段集合；maxHops 为最大跳数。
 * 结构化对象与中文短句均由同一份数据生成，避免两处说法不一致。
 */
public final class ReachabilityResult {

    private final LinkedHashSet<FieldKey> fields;
    private final Map<FieldKey, Integer> hops;
    private final Map<FieldKey, List<PathStep>> paths;
    private final ImpactScope scope;
    private final boolean containsCycle;
    private final List<String> cycleEdges;
    private final Set<FieldKey> needsHumanConfirmation;
    private final int maxHops;

    ReachabilityResult(LinkedHashSet<FieldKey> fields,
                       Map<FieldKey, Integer> hops,
                       Map<FieldKey, List<PathStep>> paths,
                       ImpactScope scope,
                       boolean containsCycle,
                       List<String> cycleEdges) {
        this.fields = new LinkedHashSet<>(fields);
        this.hops = Collections.unmodifiableMap(new LinkedHashMap<>(hops));
        LinkedHashMap<FieldKey, List<PathStep>> copiedPaths = new LinkedHashMap<>();
        paths.forEach((k, v) -> copiedPaths.put(k, List.copyOf(v)));
        this.paths = Collections.unmodifiableMap(copiedPaths);
        this.scope = scope == null ? ImpactScope.STRICT : scope;
        this.containsCycle = containsCycle;
        this.cycleEdges = List.copyOf(cycleEdges);

        LinkedHashSet<FieldKey> need = new LinkedHashSet<>();
        int max = 0;
        for (FieldKey f : this.fields) {
            int h = this.hops.getOrDefault(f, 0);
            if (EdgeConfidencePolicy.needsHumanConfirmation(h)) {
                need.add(f);
            }
            if (h > max) {
                max = h;
            }
        }
        this.needsHumanConfirmation = Collections.unmodifiableSet(need);
        this.maxHops = max;
    }

    /** 受影响字段集合（不含 seed），BFS 入队顺序，同序稳定。 */
    public LinkedHashSet<FieldKey> fields() {
        return new LinkedHashSet<>(fields);
    }

    /** 每个字段（含 seed 的 0 跳）到改动点的最短跳数。 */
    public Map<FieldKey, Integer> hops() {
        return hops;
    }

    /** 每个字段的一条最短路径（逐跳 PathStep）。 */
    public Map<FieldKey, List<PathStep>> paths() {
        return paths;
    }

    public ImpactScope scope() {
        return scope;
    }

    public boolean containsCycle() {
        return containsCycle;
    }

    /** 回边清单，形如 FROM.KEY->TO.KEY。 */
    public List<String> cycleEdges() {
        return cycleEdges;
    }

    /** 跳数 ≥ 3、需人工确认的字段集合。 */
    public Set<FieldKey> needsHumanConfirmation() {
        return needsHumanConfirmation;
    }

    /** 单个字段是否需人工确认（逐字段版本）。 */
    public boolean needsHumanConfirmation(FieldKey field) {
        Integer h = hops.get(field);
        return h != null && EdgeConfidencePolicy.needsHumanConfirmation(h);
    }

    /** 最大跳数；无可达字段时为 0。 */
    public int maxHops() {
        return maxHops;
    }
}
