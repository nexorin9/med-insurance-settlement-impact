package com.chaosforge.medins.adapter;

import com.chaosforge.medins.config.EnvSource;
import com.chaosforge.medins.config.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 36：HIS 只读数据源与环境变量凭据读取（含 doctor 自检）。
 * 不使用真实环境变量、真实网络、真实数据库：环境用 EnvSource 替身，连接失败用构造的 SQLException 归类断言。
 */
class HisDataSourceConfigTest {

    private static final String PASSWORD = "S3cr3t-Pwd-01";

    @Test
    void missingCredentials_reportVariableNamesOnly_andNoValues() {
        HisDataSourceException ex = assertThrows(HisDataSourceException.class,
                () -> HisReadOnlyDataSource.fromEnv(EnvSource.of(Map.of())));
        assertEquals(HisDataSourceException.FailureKind.MISSING_CREDENTIAL, ex.kind());
        String msg = ex.getMessage();
        assertTrue(msg.contains("HIS_DB_URL"), "须指出缺失变量名 HIS_DB_URL");
        assertTrue(msg.contains("HIS_DB_USER"), "须指出缺失变量名 HIS_DB_USER");
        assertTrue(msg.contains("HIS_DB_PASSWORD"), "须指出缺失变量名 HIS_DB_PASSWORD");
        // 只报变量名，不回显任何取值
        assertFalse(msg.contains(PASSWORD));
        assertTrue(msg.contains("只报变量名") || msg.contains("不含"));

        // 只缺一项时精确指出那一项
        HisDataSourceException one = assertThrows(HisDataSourceException.class,
                () -> HisReadOnlyDataSource.fromEnv(EnvSource.of(Map.of(
                        "HIS_DB_URL", "jdbc:oracle:thin:@//h:1521/SID",
                        "HIS_DB_USER", "readonly"))));
        assertTrue(one.getMessage().contains("HIS_DB_PASSWORD"));
        assertFalse(one.getMessage().contains("HIS_DB_URL"));
    }

    @Test
    void credentialsAndLogsAreRedacted_passwordNeverAppears() {
        HisReadOnlyDataSource ds = HisReadOnlyDataSource.of(
                "jdbc:oracle:thin:@//dbhost:1521/ORCL", "readonly", PASSWORD);
        // safeUrl 不含口令
        assertFalse(ds.safeUrl().contains(PASSWORD));
        assertFalse(ds.sourceId().contains(PASSWORD));

        // 连接异常里即便拼进口令，也要经脱敏器去掉
        SQLException leaking = new SQLException("ORA-01017 invalid username/password=" + PASSWORD);
        String message = HisReadOnlyDataSource.actionableMessage(
                leaking, SecretRedactor.of(List.of(PASSWORD)));
        assertFalse(message.contains(PASSWORD), "异常与日志文本不得出现口令字面量");
        assertTrue(message.contains("账号口令错误") || message.contains("账号"));
    }

    @Test
    void connectionFailure_threeDistinguishableKindsWithActionableHints() {
        SQLException network = new SQLException("IO error: Connection refused to host");
        SQLException auth = new SQLException("ORA-01017: invalid username/password; logon denied");
        SQLException dbMissing = new SQLException("ORA-12505: TNS:listener does not currently know of SID given");

        assertEquals(HisDataSourceException.FailureKind.NETWORK_UNREACHABLE,
                HisReadOnlyDataSource.classify(network));
        assertEquals(HisDataSourceException.FailureKind.AUTH_FAILED,
                HisReadOnlyDataSource.classify(auth));
        assertEquals(HisDataSourceException.FailureKind.DB_NOT_FOUND,
                HisReadOnlyDataSource.classify(dbMissing));

        // 三类提示可区分
        assertTrue(HisReadOnlyDataSource.classifyRaw("connection refused").hintZh().contains("主机"));
        assertTrue(HisReadOnlyDataSource.classifyRaw("invalid password").hintZh().contains("只读账号")
                || HisReadOnlyDataSource.classifyRaw("invalid password").hintZh().contains("HIS_DB_USER"));
        assertTrue(HisReadOnlyDataSource.classifyRaw("ORA-12505 SID").hintZh().contains("SID")
                || HisReadOnlyDataSource.classifyRaw("ORA-12505 SID").hintZh().contains("服务名"));
        assertFalse(HisReadOnlyDataSource.classifyRaw("connection refused").hintZh()
                .equals(HisReadOnlyDataSource.classifyRaw("invalid password").hintZh()));

        // 驱动缺失也能区分
        assertEquals(HisDataSourceException.FailureKind.DRIVER_UNAVAILABLE,
                HisReadOnlyDataSource.classifyRaw("No suitable driver found"));
    }

    @Test
    void doctorSelfCheck_reportsJdkDriverAndCredentialPresenceOnly() {
        assertTrue(HisReadOnlyDataSource.doctorJdkVersion().contains("Java"));
        // 只报变量名与是否已设，不回显值
        List<String> status = HisReadOnlyDataSource.doctorCredentialStatus(
                EnvSource.of(Map.of("HIS_DB_URL", "jdbc:oracle:thin:@//h:1521/SID")));
        String joined = String.join("|", status);
        assertTrue(joined.contains("HIS_DB_URL=已设置"));
        assertTrue(joined.contains("HIS_DB_PASSWORD=未设置"));
        assertFalse(joined.contains("jdbc:oracle"), "不得回显连接串取值");
        String report = HisReadOnlyDataSource.doctorReport(EnvSource.of(Map.of()));
        assertTrue(report.contains("Java"));
        assertTrue(report.contains("只读库驱动"));
    }
}
