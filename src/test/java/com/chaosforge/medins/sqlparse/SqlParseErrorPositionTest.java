package com.chaosforge.medins.sqlparse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 10：解析失败按行列号报告，行列号落在错误语句附近（容差 1 行）。 */
public class SqlParseErrorPositionTest {

    /** 第 3 行 WHERE 处括号不闭合，错误语句在第 3 行。 */
    private static final String BAD = ""
            + "SELECT o.o_orderkey AS bill_no\n"
            + "  FROM orders o\n"
            + " WHERE (o.o_custkey = 1\n";

    @Test
    void parseFailureHasLineColumn() {
        ParseResult r = SqlParseFixtures.parse("v_bad", BAD);
        assertFalse(r.success());
        assertFalse(r.errors().isEmpty(), "失败结果需带结构化错误");
        StructuredError e = r.errors().get(0);
        assertTrue(e.line() >= 1);
        assertTrue(e.column() >= 1);
        String msg = e.locatedMessage();
        assertTrue(msg.contains("行") && msg.contains("列"), "错误信息需含行列号：" + msg);
    }

    @Test
    void lineNearFaultyStatementWithinOneLine() {
        ParseResult r = SqlParseFixtures.parse("v_bad", BAD);
        StructuredError e = r.errors().get(0);
        int expectedLine = 3;
        assertTrue(Math.abs(e.line() - expectedLine) <= 1,
                "行列号应落在错误语句附近（容差 1 行），实际：" + e.locatedMessage());
    }

    @Test
    void parseStrictThrowsLocatedException() {
        SqlParseException ex = assertThrows(SqlParseException.class,
                () -> new SqlViewParser().parseStrict("v_bad", BAD));
        assertTrue(ex.line() >= 1 && ex.column() >= 1);
        assertTrue(ex.getMessage().contains("行") && ex.getMessage().contains("列"));
        assertEquals("PARSE_ERROR", ex.code());
        assertEquals(ex.line(), ex.toStructuredError().line());
        assertEquals(ex.column(), ex.toStructuredError().column());
    }

    @Test
    void parseDoesNotThrowToUi() {
        // parse 对 UI 友好：坏 SQL 返回 failure 结果而非抛异常
        assertDoesNotThrow(() -> SqlParseFixtures.parse("v_bad", BAD));
    }
}
