package com.chaosforge.medins.adapter;

import com.chaosforge.medins.config.SecretRedactor;
import com.chaosforge.medins.sqlparse.SqlDigest;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 取数视图定义读取（Oracle 首版）：从院内只读库 {@code all_views.text} 取本院运行中的视图定义文本。
 *
 * <p>规则：参数化 PreparedStatement（禁止字符串拼接）、视图名统一大写；
 * 结果为空报「视图未找到或当前账号无读取权限」，绝不返回空文本让下游误判为视图为空；
 * 摘要复用 {@link SqlDigest}；记录取数时间与不含口令的来源标识。
 */
public final class ViewDefinitionRepository {

    /** 固定参数化 SQL；视图名以 {@code ?} 绑定，杜绝拼接注入。 */
    public static final String SELECT_SQL = "select text from all_views where view_name = ?";

    private final Connection connection;
    private final String sourceId;

    public ViewDefinitionRepository(Connection connection) {
        this(connection, deriveSourceId(connection));
    }

    public ViewDefinitionRepository(Connection connection, String sourceId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sourceId = sourceId == null ? "unknown" : SecretRedactor.empty().redact(sourceId);
    }

    /** 生产便捷构造：由只读数据源打开连接；连接由本仓库持有，close 由调用方管理。 */
    public ViewDefinitionRepository(HisReadOnlyDataSource dataSource) throws HisDataSourceException {
        this(dataSource.open(), dataSource.sourceId());
    }

    public ViewDefinition read(String viewName) throws ViewReadException {
        String upper = viewName == null ? "" : viewName.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            throw new ViewReadException("视图名为空，无法读取取数视图定义");
        }
        String text;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_SQL)) {
            ps.setString(1, upper);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ViewReadException("视图未找到或当前账号无读取权限：" + upper);
                }
                text = rs.getString("text");
            }
        } catch (SQLException e) {
            throw new ViewReadException("读取视图定义失败（错误已脱敏）："
                    + SecretRedactor.empty().redact(String.valueOf(e.getMessage())), e);
        }
        if (text == null || text.isBlank()) {
            throw new ViewReadException("视图 " + upper + " 的定义文本为空，拒绝按空文本计算影响面");
        }
        String digest = SqlDigest.digest(text);
        String readAt = Instant.now().toString();
        return new ViewDefinition(upper, "oracle", text, digest, readAt, sourceId);
    }

    private static String deriveSourceId(Connection conn) {
        try {
            DatabaseMetaData md = conn.getMetaData();
            return md == null || md.getURL() == null ? "unknown" : md.getURL();
        } catch (SQLException e) {
            return "unknown";
        }
    }
}
