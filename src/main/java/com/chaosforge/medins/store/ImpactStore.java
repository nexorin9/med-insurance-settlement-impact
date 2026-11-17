package com.chaosforge.medins.store;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 影响面记录持久化（SQLite 单文件，路径可配，默认 data/impact.db 由上层传入）。
 *
 * <p>按幂等键覆盖：{@link #upsert} 用 {@code ON CONFLICT(idempotency_key) DO UPDATE}，
 * 同键重复写入只更新既有行，不追加第二条；状态与确认／回写信息跨重启留存。
 * 表结构自建，时间统一 ISO-8601 字符串。
 */
public final class ImpactStore implements AutoCloseable {

    public static final String DEFAULT_DB_FILE = "data/impact.db";

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS impact_records (
                idempotency_key TEXT PRIMARY KEY,
                view_name       TEXT,
                view_dialect    TEXT,
                sql_digest      TEXT,
                changed_field   TEXT,
                change_kind     TEXT,
                section         TEXT,
                list_field_name TEXT,
                source_column   TEXT,
                impact          TEXT,
                confidence      REAL,
                scope           TEXT,
                hops            INTEGER,
                batch           TEXT,
                verdict         TEXT,
                reason_zh       TEXT,
                status          TEXT,
                confirmed_by    TEXT,
                confirmed_at    TEXT,
                occurred_at     TEXT,
                write_back_at   TEXT
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO impact_records (
                idempotency_key, view_name, view_dialect, sql_digest, changed_field, change_kind,
                section, list_field_name, source_column, impact, confidence, scope, hops, batch,
                verdict, reason_zh, status, confirmed_by, confirmed_at, occurred_at, write_back_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(idempotency_key) DO UPDATE SET
                view_name=excluded.view_name, view_dialect=excluded.view_dialect,
                sql_digest=excluded.sql_digest, changed_field=excluded.changed_field,
                change_kind=excluded.change_kind, section=excluded.section,
                list_field_name=excluded.list_field_name, source_column=excluded.source_column,
                impact=excluded.impact, confidence=excluded.confidence, scope=excluded.scope,
                hops=excluded.hops, batch=excluded.batch, verdict=excluded.verdict,
                reason_zh=excluded.reason_zh, status=excluded.status,
                confirmed_by=excluded.confirmed_by, confirmed_at=excluded.confirmed_at,
                occurred_at=excluded.occurred_at, write_back_at=excluded.write_back_at
            """;

    private final Connection connection;

    public ImpactStore(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 初始化失败：" + e.getMessage(), e);
        }
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_SQL);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("打开影响面库失败", e);
        }
    }

    /** 按幂等键覆盖写入；同键不追加第二行。 */
    public synchronized void upsert(ImpactRecord r) {
        Objects.requireNonNull(r, "record");
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_SQL)) {
            bind(ps, r);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入影响面记录失败", e);
        }
    }

    public synchronized Optional<ImpactRecord> find(String idempotencyKey) {
        String sql = "SELECT * FROM impact_records WHERE idempotency_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey == null ? "" : idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询影响面记录失败", e);
        }
    }

    public synchronized List<ImpactRecord> all() {
        List<ImpactRecord> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM impact_records")) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取全部影响面记录失败", e);
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // 关闭失败不再抛出，避免掩盖业务结果
            System.err.println("[ImpactStore] 关闭连接失败：" + e.getMessage());
        }
    }

    private static void bind(PreparedStatement ps, ImpactRecord r) throws SQLException {
        ps.setString(1, nz(r.idempotencyKey()));
        ps.setString(2, nz(r.viewName()));
        ps.setString(3, nz(r.viewDialect()));
        ps.setString(4, nz(r.sqlDigest()));
        ps.setString(5, nz(r.changedField()));
        ps.setString(6, r.changeKind() == null ? "" : r.changeKind().wireName());
        ps.setString(7, nz(r.section()));
        ps.setString(8, nz(r.listFieldName()));
        ps.setString(9, nz(r.sourceColumn()));
        ps.setString(10, r.impact() == null ? "" : r.impact().wireName());
        ps.setDouble(11, clamp01(r.confidence()));
        ps.setString(12, r.scope() == null ? "" : r.scope().wireName());
        ps.setInt(13, Math.max(0, r.hops()));
        ps.setString(14, nz(r.batch()));
        ps.setString(15, r.verdict() == null ? "" : r.verdict().wireName());
        ps.setString(16, nz(r.reasonZh()));
        ps.setString(17, r.status() == null ? "" : r.status().wireName());
        ps.setString(18, nz(r.confirmedBy()));
        ps.setString(19, nz(r.confirmedAt()));
        ps.setString(20, nz(r.occurredAt()));
        ps.setString(21, nz(r.writeBackAt()));
    }

    private static ImpactRecord read(ResultSet rs) throws SQLException {
        ChangeKind ck = ChangeKind.fromWireName(rs.getString("change_kind"));
        ImpactType impact = ImpactType.fromWireName(rs.getString("impact"));
        ImpactScope scope = ImpactScope.fromWireName(rs.getString("scope"));
        Verdict verdict = Verdict.fromWireName(rs.getString("verdict"));
        ImpactStatus status = ImpactStatus.fromWireName(rs.getString("status"));
        return ImpactRecord.builder()
                .idempotencyKey(rs.getString("idempotency_key"))
                .viewName(rs.getString("view_name"))
                .viewDialect(rs.getString("view_dialect"))
                .sqlDigest(rs.getString("sql_digest"))
                .changedField(rs.getString("changed_field"))
                .changeKind(ck == null ? ChangeKind.LOGIC_CHANGED : ck)
                .section(rs.getString("section"))
                .listFieldName(rs.getString("list_field_name"))
                .sourceColumn(rs.getString("source_column"))
                .impact(impact == null ? ImpactType.DIRECT : impact)
                .confidence(clamp01(rs.getDouble("confidence")))
                .scope(scope == null ? ImpactScope.STRICT : scope)
                .hops(Math.max(0, rs.getInt("hops")))
                .batch(rs.getString("batch"))
                .verdict(verdict == null ? Verdict.NOT_ATTRIBUTED : verdict)
                .reasonZh(rs.getString("reason_zh"))
                .status(status == null ? ImpactStatus.PARSED : status)
                .confirmedBy(rs.getString("confirmed_by"))
                .confirmedAt(rs.getString("confirmed_at"))
                .occurredAt(rs.getString("occurred_at"))
                .writeBackAt(rs.getString("write_back_at"))
                .build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, v));
    }
}
