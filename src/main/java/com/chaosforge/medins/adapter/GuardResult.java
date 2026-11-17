package com.chaosforge.medins.adapter;

/** 回写权限闸门结果：是否允许、中文原因、已具备回写资格的记录条数。 */
public final class GuardResult {

    private final boolean allowed;
    private final String reason;
    private final int eligibleCount;

    public GuardResult(boolean allowed, String reason, int eligibleCount) {
        this.allowed = allowed;
        this.reason = reason == null ? "" : reason;
        this.eligibleCount = eligibleCount;
    }

    public boolean allowed() { return allowed; }
    public String reason() { return reason; }
    public int eligibleCount() { return eligibleCount; }

    @Override
    public String toString() {
        return "GuardResult{allowed=" + allowed + ", reason=" + reason
                + ", eligibleCount=" + eligibleCount + "}";
    }
}
