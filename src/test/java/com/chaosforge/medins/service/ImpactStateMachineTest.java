package com.chaosforge.medins.service;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.store.AuditLog;
import com.chaosforge.medins.store.ImpactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 45：业务状态机与非法跃迁拒绝（CONFIRMED 只能由人推进）、与 store/审计联动。 */
class ImpactStateMachineTest {

    @TempDir
    Path dir;

    private static ImpactRecord rec(ImpactStatus status) {
        return ImpactRecord.builder()
                .idempotencyKey("K-1")
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .impact(ImpactType.DIRECT)
                .confidence(0.9d)
                .scope(ImpactScope.STRICT)
                .batch("<EXAMPLE_BATCH>")
                .status(status)
                .build();
    }

    private static final ImpactStatus[][] LEGAL = {
            {ImpactStatus.PARSED, ImpactStatus.COMPUTED},
            {ImpactStatus.PARSED, ImpactStatus.DEGRADED},
            {ImpactStatus.COMPUTED, ImpactStatus.ATTRIBUTED},
            {ImpactStatus.COMPUTED, ImpactStatus.DEGRADED},
            {ImpactStatus.COMPUTED, ImpactStatus.AMBIGUOUS},
            {ImpactStatus.ATTRIBUTED, ImpactStatus.PENDING_CONFIRM},
            {ImpactStatus.ATTRIBUTED, ImpactStatus.DEGRADED},
            {ImpactStatus.ATTRIBUTED, ImpactStatus.AMBIGUOUS},
            {ImpactStatus.PENDING_CONFIRM, ImpactStatus.CONFIRMED},
            {ImpactStatus.PENDING_CONFIRM, ImpactStatus.DEGRADED},
            {ImpactStatus.PENDING_CONFIRM, ImpactStatus.AMBIGUOUS},
            {ImpactStatus.CONFIRMED, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.CONFIRMED, ImpactStatus.PENDING_WRITEBACK},
            {ImpactStatus.CONFIRMED, ImpactStatus.PENDING_CONFIRM},
            {ImpactStatus.PENDING_WRITEBACK, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.PENDING_WRITEBACK, ImpactStatus.CONFIRMED},
            {ImpactStatus.DEGRADED, ImpactStatus.COMPUTED},
            {ImpactStatus.DEGRADED, ImpactStatus.AMBIGUOUS},
            {ImpactStatus.AMBIGUOUS, ImpactStatus.ATTRIBUTED},
            {ImpactStatus.AMBIGUOUS, ImpactStatus.PENDING_CONFIRM},
    };

    private static final ImpactStatus[][] ILLEGAL = {
            {ImpactStatus.PARSED, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.PARSED, ImpactStatus.CONFIRMED},
            {ImpactStatus.COMPUTED, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.ATTRIBUTED, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.PENDING_CONFIRM, ImpactStatus.WRITTEN_BACK},
            {ImpactStatus.WRITTEN_BACK, ImpactStatus.CONFIRMED},
            {ImpactStatus.WRITTEN_BACK, ImpactStatus.PARSED},
            {ImpactStatus.CONFIRMED, ImpactStatus.PARSED},
    };

    @Test
    void everyLegalTransitionCanBeTaken() {
        for (ImpactStatus[] pair : LEGAL) {
            assertTrue(ImpactStateMachine.canTransition(pair[0], pair[1]),
                    pair[0] + " -> " + pair[1] + " 应为合法");
            ImpactRecord result = ImpactStateMachine.transition(
                    rec(pair[0]), pair[1], ImpactStateMachine.Actor.HUMAN);
            assertEquals(pair[1], result.status());
        }
    }

    @Test
    void everyIllegalTransitionIsRejected() {
        for (ImpactStatus[] pair : ILLEGAL) {
            assertFalse(ImpactStateMachine.canTransition(pair[0], pair[1]),
                    pair[0] + " -> " + pair[1] + " 应为非法");
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ImpactStateMachine.transition(
                            rec(pair[0]), pair[1], ImpactStateMachine.Actor.HUMAN));
            assertTrue(ex.getMessage().contains("不能到"), "消息须含「从 X 不能到 Y」");
            assertTrue(ex.getMessage().contains(pair[0].name()));
            assertTrue(ex.getMessage().contains(pair[1].name()));
        }
    }

    @Test
    void confirmedOnlyAdvancedByHuman() {
        for (ImpactStateMachine.Actor actor : new ImpactStateMachine.Actor[]{
                ImpactStateMachine.Actor.TOOL,
                ImpactStateMachine.Actor.MODEL,
                ImpactStateMachine.Actor.BACKGROUND}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ImpactStateMachine.transition(
                            rec(ImpactStatus.PENDING_CONFIRM), ImpactStatus.CONFIRMED, actor));
            assertTrue(ex.getMessage().contains("HUMAN") || ex.getMessage().contains("人工"));
            assertTrue(ex.getMessage().contains("不能到"));
        }
        // 仅人工可推进
        assertEquals(ImpactStatus.CONFIRMED,
                ImpactStateMachine.transition(rec(ImpactStatus.PENDING_CONFIRM),
                        ImpactStatus.CONFIRMED, ImpactStateMachine.Actor.HUMAN).status());

        // 模型与工具无权把记录直接推到已回写
        assertThrows(IllegalStateException.class,
                () -> ImpactStateMachine.transition(rec(ImpactStatus.CONFIRMED),
                        ImpactStatus.WRITTEN_BACK, ImpactStateMachine.Actor.MODEL));
        assertThrows(IllegalStateException.class,
                () -> ImpactStateMachine.transition(rec(ImpactStatus.CONFIRMED),
                        ImpactStatus.WRITTEN_BACK, ImpactStateMachine.Actor.TOOL));
    }

    @Test
    void applyWritesStoreAndAudit_illegalDoesNotPersist() {
        try (ImpactStore store = new ImpactStore(dir.resolve("impact.db"))) {
            AuditLog audit = new AuditLog(dir.resolve("audit.log"));
            ImpactStateMachine machine = new ImpactStateMachine(store, audit);

            ImpactRecord advanced = machine.apply(rec(ImpactStatus.PENDING_CONFIRM),
                    ImpactStatus.CONFIRMED, ImpactStateMachine.Actor.HUMAN);
            assertEquals(ImpactStatus.CONFIRMED, advanced.status());
            assertTrue(store.find("K-1").isPresent(), "跃迁后写库");
            assertEquals(ImpactStatus.CONFIRMED, store.find("K-1").orElseThrow().status());
            assertTrue(audit.byView("V_BILL_FEE").size() >= 1, "跃迁后审计链有对应条目");

            int auditBefore = audit.all().size();
            assertThrows(IllegalStateException.class,
                    () -> machine.apply(rec(ImpactStatus.PENDING_CONFIRM),
                            ImpactStatus.WRITTEN_BACK, ImpactStateMachine.Actor.TOOL));
            assertEquals(auditBefore, audit.all().size(), "非法跃迁不追加审计");
            assertEquals(1, store.all().size(), "非法跃迁不落库");
            assertEquals(ImpactStatus.CONFIRMED, store.find("K-1").orElseThrow().status());
        }
    }
}
