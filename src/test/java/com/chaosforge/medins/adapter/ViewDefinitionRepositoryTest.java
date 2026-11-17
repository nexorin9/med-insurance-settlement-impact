package com.chaosforge.medins.adapter;

import com.chaosforge.medins.sqlparse.SqlDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 37：取数视图定义读取（all_views.text）与视图摘要计算。
 * 本地协议替身用内存 SQLite（构造同构的 all_views 表），绝不使用真实数据库或网络。
 */
class ViewDefinitionRepositoryTest {

    private Connection conn;
    private ViewDefinitionRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE all_views (view_name TEXT, \"text\" TEXT)");
        }
        repo = new ViewDefinitionRepository(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void parameterizedQuery_uppercasesViewName_blocksInjection() throws Exception {
        // 固定参数化 SQL，视图名绝不拼接进语句
        assertEquals("select text from all_views where view_name = ?",
                ViewDefinitionRepository.SELECT_SQL);
        assertTrue(ViewDefinitionRepository.SELECT_SQL.contains("?"));
        assertFalse(ViewDefinitionRepository.SELECT_SQL.contains("V_BILL_FEE"));

        String sql = "SELECT o.o_orderkey AS bill_no FROM orders o";
        insert("V_BILL_FEE", sql);
        insert("O'BRIEN", "SELECT 1 AS x FROM dual");

        ViewDefinition vd = repo.read("v_bill_fee");
        assertEquals("V_BILL_FEE", vd.viewName(), "视图名须大写化");
        assertEquals("oracle", vd.dialect());
        assertEquals(sql, vd.sql());
        assertEquals(SqlDigest.digest(sql), vd.sqlDigest(), "摘要复用 SqlDigest.digest");
        assertFalse(vd.readAt().isBlank(), "须记录取数时间");
        assertFalse(vd.sourceId().isBlank(), "须记录来源标识");
        assertFalse(vd.sourceId().contains("secret"), "来源标识不含口令");

        // 含单引号的视图名：参数化下当作字面值，正常命中而不破坏 SQL
        assertEquals("O'BRIEN", repo.read("O'BRIEN").viewName());

        // 注入串：参数化把它当普通字面值，不会「返回全部行」，应判为视图未找到
        assertThrows(ViewReadException.class, () -> repo.read("X' OR '1'='1"));
    }

    @Test
    void viewNotFound_throwsClearError() {
        ViewReadException ex = assertThrows(ViewReadException.class,
                () -> repo.read("NO_SUCH_VIEW"));
        assertTrue(ex.getMessage().contains("视图未找到或当前账号无读取权限"));
    }

    @Test
    void sameSql_multipleReads_sameDigest() throws Exception {
        String sql = "SELECT l.l_extendedprice AS fee_amt FROM lineitem l";
        insert("V_FEE", sql);
        String d1 = repo.read("V_FEE").sqlDigest();
        String d2 = repo.read("V_FEE").sqlDigest();
        assertEquals(d1, d2, "同一 SQL 多次读取摘要一致");
        assertEquals(SqlDigest.digest(sql), d1);
        // 仅注释/空白差异视为同一段 SQL
        assertEquals(SqlDigest.digest(sql), SqlDigest.digest(sql + "\n-- 追加注释\n"));
    }

    private void insert(String viewName, String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO all_views(view_name, \"text\") VALUES (?,?)")) {
            ps.setString(1, viewName);
            ps.setString(2, text);
            ps.executeUpdate();
        }
    }
}
