package com.chaosforge.medins.sqlparse;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Task 11：SQL 规范化摘要（仅空白/注释差异视为同一段）与幂等键稳定性。 */
public class SqlDigestTest {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void synonymousRewritesSameDigest() {
        String a = "SELECT o.o_orderkey AS bill_no FROM orders o WHERE o.o_orderstatus = 'F'";
        String b = "-- 注释\n"
                + "SELECT   o.o_orderkey   AS   bill_no\n"
                + "  FROM   orders o\n"
                + " WHERE o.o_orderstatus = 'F' /* 块注释 */";
        assertEquals(SqlDigest.normalize(a), SqlDigest.normalize(b));
        assertEquals(SqlDigest.digest(a), SqlDigest.digest(b), "仅空白/注释差异应视为同一段 SQL");
    }

    @Test
    void semanticChangeDifferentDigest() {
        String a = "SELECT o.o_orderkey AS bill_no FROM orders o";
        String b = "SELECT o.o_custkey AS bill_no FROM orders o";
        assertNotEquals(SqlDigest.digest(a), SqlDigest.digest(b), "语义改动应改变摘要");
    }

    @Test
    void digestIs64Hex() {
        assertTrue(HEX64.matcher(SqlDigest.digest("SELECT 1 FROM dual")).matches());
        assertTrue(HEX64.matcher(SqlDigest.sha256Hex("abc")).matches());
        assertEquals(64, SqlDigest.digest("SELECT 1 FROM dual").length());
    }

    @Test
    void idempotencyKeyStableAndSensitive() {
        String d = SqlDigest.digest("SELECT 1 FROM dual");
        String k1 = SqlDigest.idempotencyKey("v_bill_fee", d, "fee_amt");
        String k2 = SqlDigest.idempotencyKey("v_bill_fee", d, "fee_amt");
        assertEquals(k1, k2, "同一（视图, 摘要, 列）幂等键稳定");
        assertEquals(64, k1.length());
        assertNotEquals(k1, SqlDigest.idempotencyKey("v_other", d, "fee_amt"), "视图变化键变化");
        assertNotEquals(k1, SqlDigest.idempotencyKey(
                "v_bill_fee", SqlDigest.digest("SELECT 2 FROM dual"), "fee_amt"), "摘要变化键变化");
        assertNotEquals(k1, SqlDigest.idempotencyKey("v_bill_fee", d, "tax_amt"), "列变化键变化");
    }

    @Test
    void normalizeRemovesCommentsAndUppercases() {
        assertEquals("SELECT A FROM T", SqlDigest.normalize("select a -- x\n from t /* y */"));
        assertTrue(SqlDigest.normalize("select 1 /* 注释 */ from dual").contains("DUAL"));
    }
}
