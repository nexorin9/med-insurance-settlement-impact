package com.chaosforge.medins.service;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.store.AuditLog;
import com.chaosforge.medins.store.ImpactStore;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 业务状态机：主链 {@code PARSED → COMPUTED → ATTRIBUTED → PENDING_CONFIRM → CONFIRMED → WRITTEN_BACK}，
 * 旁支 {@code DEGRADED / AMBIGUOUS / PENDING_WRITEBACK}。
 *
 * <p>规则：
 * <ul>
 *   <li>合法跃迁表固定，非法跃迁抛 {@link IllegalStateException}，消息含「从 X 不能到 Y」；</li>
 *   <li>{@code CONFIRMED} 只能由 {@link Actor#HUMAN} 推进（模型／工具／后台任务都无权）；</li>
 *   <li>模型与工具无权把记录直接推到 {@code WRITTEN_BACK}；</li>
 *   <li>与 store 联动：实例 {@link #apply} 在每次合法跃迁后写库并追加审计条目，非法跃迁不落库。</li>
 * </ul>
 */
public final class ImpactStateMachine {

    /** 状态推进来源。 */
    public enum Actor { HUMAN, TOOL, MODEL, BACKGROUND }

    private static final Map<ImpactStatus, Set<ImpactStatus>> TABLE = buildTable();

    private static Map<ImpactStatus, Set<ImpactStatus>> buildTable() {
        Map<ImpactStatus, Set<ImpactStatus>> t = new EnumMap<>(ImpactStatus.class);
        t.put(ImpactStatus.PARSED, Set.of(ImpactStatus.COMPUTED, ImpactStatus.DEGRADED));
        t.put(ImpactStatus.COMPUTED, Set.of(ImpactStatus.ATTRIBUTED, ImpactStatus.DEGRADED, ImpactStatus.AMBIGUOUS));
        t.put(ImpactStatus.ATTRIBUTED, Set.of(ImpactStatus.PENDING_CONFIRM, ImpactStatus.DEGRADED, ImpactStatus.AMBIGUOUS));
        t.put(ImpactStatus.PENDING_CONFIRM, Set.of(ImpactStatus.CONFIRMED, ImpactStatus.DEGRADED, ImpactStatus.AMBIGUOUS));
        t.put(ImpactStatus.CONFIRMED, Set.of(
                ImpactStatus.WRITTEN_BACK, ImpactStatus.PENDING_WRITEBACK, ImpactStatus.PENDING_CONFIRM));
        t.put(ImpactStatus.PENDING_WRITEBACK, Set.of(ImpactStatus.WRITTEN_BACK, ImpactStatus.CONFIRMED));
        t.put(ImpactStatus.DEGRADED, Set.of(ImpactStatus.COMPUTED, ImpactStatus.AMBIGUOUS));
        t.put(ImpactStatus.AMBIGUOUS, Set.of(ImpactStatus.ATTRIBUTED, ImpactStatus.PENDING_CONFIRM));
        t.put(ImpactStatus.WRITTEN_BACK, Set.of());
        return t;
    }

    private final ImpactStore store;
    private final AuditLog audit;

    /** 纯状态机（不落库），供静态校验与调用方自行持久化。 */
    public ImpactStateMachine() {
        this(null, null);
    }

    /** 与 store / 审计链联动的状态机：每次合法跃迁写库并追加审计条目。 */
    public ImpactStateMachine(ImpactStore store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
    }

    public static boolean canTransition(ImpactStatus from, ImpactStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<ImpactStatus> allowed = TABLE.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 校验合法性与来源后，返回带上新状态的记录副本（原对象不可变）。
     * 非法跃迁抛 {@link IllegalStateException}，消息含「从 X 不能到 Y」。
     */
    public static ImpactRecord transition(ImpactRecord r, ImpactStatus to, Actor actor) {
        Objects.requireNonNull(r, "record");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(actor, "actor");
        ImpactStatus from = r.status();
        String base = "从 " + (from == null ? "null" : from.name()) + " 不能到 " + to.name();
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法状态跃迁：" + base);
        }
        if (to == ImpactStatus.CONFIRMED && actor != Actor.HUMAN) {
            throw new IllegalStateException("非法状态跃迁：" + base
                    + "（CONFIRMED 只能由 Actor.HUMAN 推进，当前来源 " + actor + "）");
        }
        if (to == ImpactStatus.WRITTEN_BACK && (actor == Actor.MODEL || actor == Actor.TOOL)) {
            throw new IllegalStateException("非法状态跃迁：" + base
                    + "（模型与工具无权把记录直接推到已回写，当前来源 " + actor + "）");
        }
        return r.withStatus(to);
    }

    /**
     * 联动版跃迁：先做静态校验（非法则抛错，不落库），成功后再写库并追加审计条目。
     */
    public ImpactRecord apply(ImpactRecord r, ImpactStatus to, Actor actor) {
        ImpactRecord next = transition(r, to, actor);
        if (store != null) {
            store.upsert(next);
        }
        if (audit != null) {
            audit.append(AuditLog.AuditEntry.builder()
                    .operator(actor.name())
                    .action("状态跃迁")
                    .view(next.viewName())
                    .batch(next.batch())
                    .column(next.changedField())
                    .section(next.section())
                    .listField(next.listFieldName())
                    .scope(next.scope() == null ? "" : next.scope().wireName())
                    .confirmedBy(next.confirmedBy())
                    .writtenBack(to == ImpactStatus.WRITTEN_BACK)
                    .idempotencyKey(next.idempotencyKey())
                    .fromStatus(r.status() == null ? "" : r.status().name())
                    .toStatus(to.name())
                    .detail("从 " + r.status() + " 跃迁到 " + to + "，来源 " + actor)
                    .build());
        }
        return next;
    }
}
