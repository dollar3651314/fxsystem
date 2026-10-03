package com.falconx.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V28/V29 Flyway 历史漂移 repair 脚本复演 IT。
 *
 * <p>用 app 同版本 flyway-core（编程式 {@link Flyway#configure()}）在自管隔离库
 * {@code falconx_trading_v2829repair_it} 上精确复演 STAGE-14B root bug 留下的漂移态，并验证
 * 交付的 {@code scripts/db-repair/V28V29-flyway-history-repair.sql} 能修复漂移、使 flyway 从
 * V30 继续顺序 migrate 到 V37（默认 validate-on-migrate 校验 V28/V29 补行的 checksum 与迁移文件一致）。
 *
 * <p>步骤：建隔离库 → 干净 migrate 到 V27 → 手工跑 V28/V29 body 模拟污染（列在、history 无 V28/V29 行）
 * → 执行真实交付的 repair .sql → 断言补行就位且 checksum 正确 → flyway migrate 应用 V30-V37
 * → 断言到 V37、关键产物存在 → 删隔离库。
 *
 * <p>隔离库由本测试自建自删，不触碰共享 IT 库（沿团队 IT 隔离约定）。需本地 MySQL（localhost:3306, root/root）。
 */
class TradingFlywayV28V29RepairRehearsalIntegrationTests {

    private static final String HOST = "localhost:3306";
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String DB = "falconx_trading_v2829repair_it";
    private static final String MIGRATION_LOCATIONS = "classpath:db/migration";

    /** V28/V29 迁移文件在当前 flyway-core 下的权威 checksum（与 repair .sql 内硬编码值同源）。 */
    private static final int V28_CHECKSUM = -448423770;
    private static final int V29_CHECKSUM = -809397028;

    private static final String SERVER_URL =
            "jdbc:mysql://" + HOST + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_URL = "jdbc:mysql://" + HOST + "/" + DB
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Test
    void repairScriptHealsDriftAndAllowsMigrateToResume() throws Exception {
        recreateDatabase();
        try {
            // 1. 干净 migrate 到 V27（漂移基线）。
            flyway(MigrationVersion.fromVersion("27")).migrate();
            assertEquals(27, maxVersion(), "V27 基线 migrate 应到位");

            // 2. 模拟污染：手工跑 V28/V29 的 DDL+回填 body（列物理出现，但 flyway_schema_history 无 V28/V29 行）。
            executeSqlScript(readClasspath("db/migration/V28__ledger_currency_columns.sql"));
            executeSqlScript(readClasspath("db/migration/V29__position_open_fx_snapshot.sql"));
            assertEquals(3, ledgerCurrencyColumnCount(), "污染态：t_ledger 三列应物理存在");
            assertEquals(1, positionFxColumnCount(), "污染态：t_position.entry_fx_rate 应物理存在");
            assertEquals(0, v28v29HistoryRowCount(), "污染态：flyway_schema_history 应仍无 V28/V29 行");
            assertEquals(27, maxVersion(), "污染态：flyway 基线应仍停在 V27");

            // 3. 执行真实交付的 repair .sql 文件本体。
            executeSqlScript(readRepairScript());

            // 补行就位且 checksum 与迁移文件一致。
            assertEquals(2, v28v29HistoryRowCount(), "repair 后应补入 V28/V29 两行");
            assertEquals(V28_CHECKSUM, checksumOf("28"), "V28 补行 checksum 必须与迁移文件一致");
            assertEquals(V29_CHECKSUM, checksumOf("29"), "V29 补行 checksum 必须与迁移文件一致");
            assertEquals(1, successOf("28"), "V28 补行 success 必须为 1");
            assertEquals(1, successOf("29"), "V29 补行 success 必须为 1");

            // 4. flyway migrate（无 target）→ 应用 V30-V37 + 默认 validate 校验 V28/V29 checksum；
            //    若 repair 补行 checksum 错误，此步会因 validate 失败抛异常。
            flyway(MigrationVersion.LATEST).migrate();

            // 5. 末态断言：到 V37、关键 V30+ 产物存在。
            assertTrue(maxVersion() >= 37, "repair 后应顺序 migrate 到 ≥V37，实际=" + maxVersion());
            assertTrue(tableExists("t_symbol_leverage_tier"), "V30 产物 t_symbol_leverage_tier 应存在");
            assertTrue(tableExists("t_fx_pause_behavior"), "V31 产物 t_fx_pause_behavior 应存在");
            assertTrue(columnExists("t_risk_config", "cooling_period_seconds"), "V37 列 cooling_period_seconds 应存在");
            assertEquals(0, failedHistoryRowCount(), "flyway_schema_history 不应有 success=0 行");
        } finally {
            dropDatabase();
        }
    }

    /**
     * 守卫验证：repair .sql 在干净库（已含 V28/V29 历史行）上为安全 no-op，不重复插入。
     */
    @Test
    void repairScriptIsNoOpOnCleanDatabase() throws Exception {
        recreateDatabase();
        try {
            // 干净库直接 migrate 到 V29（V28/V29 历史行由 flyway 正常写入）。
            flyway(MigrationVersion.fromVersion("29")).migrate();
            assertEquals(2, v28v29HistoryRowCount(), "干净库应已有 V28/V29 两行");

            // 在干净库上执行 repair —— 应为 no-op（仍是 2 行，不重复）。
            executeSqlScript(readRepairScript());
            assertEquals(2, v28v29HistoryRowCount(), "干净库上 repair 应为 no-op，不重复插入");
        } finally {
            dropDatabase();
        }
    }

    // ---------------------------------------------------------------------
    // flyway / jdbc helpers
    // ---------------------------------------------------------------------

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(DB_URL, USER, PASS)
                .locations(MIGRATION_LOCATIONS)
                .placeholderReplacement(false) // 对齐 app application.yml
                .target(target)
                .load();
    }

    private void recreateDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(SERVER_URL, USER, PASS); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB);
            s.execute("CREATE DATABASE " + DB + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void dropDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(SERVER_URL, USER, PASS); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB);
        }
    }

    /** 逐条执行 SQL 脚本（剥离 -- 行注释后按 ; 切分）。SELECT/SET/INSERT/DDL 均逐条 execute。 */
    private void executeSqlScript(String script) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, USER, PASS); Statement s = c.createStatement()) {
            for (String stmt : splitStatements(script)) {
                s.execute(stmt);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue; // 跳过空行与整行注释（本仓 SQL 不使用行内 -- 注释，故按行剥离安全）
            }
            sql.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : sql.toString().split(";")) {
            String stmt = part.trim();
            if (!stmt.isEmpty()) {
                statements.add(stmt);
            }
        }
        return statements;
    }

    private long maxVersion() throws Exception {
        return scalar("SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) FROM flyway_schema_history");
    }

    private long v28v29HistoryRowCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('28','29')");
    }

    private long failedHistoryRowCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0");
    }

    private int checksumOf(String version) throws Exception {
        return (int) scalar("SELECT checksum FROM flyway_schema_history WHERE version = '" + version + "'");
    }

    private int successOf(String version) throws Exception {
        return (int) scalar("SELECT success FROM flyway_schema_history WHERE version = '" + version + "'");
    }

    private long ledgerCurrencyColumnCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + DB
                + "' AND table_name = 't_ledger'"
                + " AND column_name IN ('original_amount','original_currency','fx_rate_at_settlement')");
    }

    private long positionFxColumnCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + DB
                + "' AND table_name = 't_position' AND column_name = 'entry_fx_rate'");
    }

    private boolean tableExists(String table) throws Exception {
        return scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + DB
                + "' AND table_name = '" + table + "'") > 0;
    }

    private boolean columnExists(String table, String column) throws Exception {
        return scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + DB
                + "' AND table_name = '" + table + "' AND column_name = '" + column + "'") > 0;
    }

    private long scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, USER, PASS);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------------
    // 文件读取：迁移文件走 classpath，repair 脚本从仓库根向上定位
    // ---------------------------------------------------------------------

    private String readClasspath(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                fail("classpath 资源缺失: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readRepairScript() throws IOException {
        Path repairRelative = Paths.get("scripts", "db-repair", "V28V29-flyway-history-repair.sql");
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repairRelative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        fail("未能定位 repair 脚本: " + repairRelative + "（从 " + Paths.get("").toAbsolutePath() + " 向上查找）");
        return null;
    }
}
