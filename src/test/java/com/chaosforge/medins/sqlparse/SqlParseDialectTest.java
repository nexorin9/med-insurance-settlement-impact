package com.chaosforge.medins.sqlparse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 10：Oracle 方言白名单，MySQL/PG 特征显式「首版不支持」且带行列号，Oracle 段不误报。 */
public class SqlParseDialectTest {

    @Test
    void mysqlBacktickAndLimitUnsupportedWithPosition() throws Exception {
        String sql = SqlParseFixtures.edgecase("dialect_unsupported");
        assertEquals(SqlDialect.MYSQL, SqlDialect.detect(sql));
        ParseResult r = SqlParseFixtures.parse("v_mysql", sql);
        assertFalse(r.success());
        assertTrue(SqlParseFixtures.hasReason(r, ManualReviewItem.Reason.DIALECT_UNSUPPORTED));
        assertFalse(r.errors().isEmpty());
        StructuredError e = r.errors().get(0);
        assertEquals("DIALECT_UNSUPPORTED", e.code());
        assertTrue(e.line() >= 1 && e.column() >= 1, "需带行列号");
        assertTrue(e.locatedMessage().contains("行") && e.locatedMessage().contains("列"));
        assertTrue(e.message().contains("Oracle") || e.message().contains("不支持"));
    }

    @Test
    void postgresqlCastUnsupported() {
        String sql = "SELECT o_orderkey::text AS bill_no FROM orders";
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.detect(sql));
        ParseResult r = SqlParseFixtures.parse("v_pg", sql);
        assertFalse(r.success());
        assertEquals("DIALECT_UNSUPPORTED", r.errors().get(0).code());
        assertTrue(r.errors().get(0).line() >= 1);
        assertTrue(SqlParseFixtures.hasReason(r, ManualReviewItem.Reason.DIALECT_UNSUPPORTED));
    }

    @Test
    void oracleSampleNoFalsePositive() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee");
        assertTrue(r.success());
        assertEquals(SqlDialect.ORACLE, r.dialect());
        assertFalse(SqlParseFixtures.hasReason(r, ManualReviewItem.Reason.DIALECT_UNSUPPORTED));
        assertEquals(SqlDialect.ORACLE, SqlDialect.detect(SqlParseFixtures.sample("v_filter_matrix")));
        assertEquals(SqlDialect.ORACLE, SqlDialect.detect(SqlParseFixtures.sample("v_dept_cost_cte")));
    }

    @Test
    void parseStrictThrowsOnUnsupportedDialect() throws Exception {
        String sql = SqlParseFixtures.edgecase("dialect_unsupported");
        SqlParseException ex = assertThrows(SqlParseException.class,
                () -> new SqlViewParser().parseStrict("v_mysql", sql));
        assertEquals("DIALECT_UNSUPPORTED", ex.code());
        assertTrue(ex.line() >= 1 && ex.column() >= 1);
        assertEquals(ex.code(), ex.toStructuredError().code());
    }

    @Test
    void dialectEnumWhitelist() {
        assertTrue(SqlDialect.ORACLE.supported());
        assertFalse(SqlDialect.MYSQL.supported());
        assertFalse(SqlDialect.POSTGRESQL.supported());
        assertFalse(SqlDialect.UNSUPPORTED.supported());
    }
}
