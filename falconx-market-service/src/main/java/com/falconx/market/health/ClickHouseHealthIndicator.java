package com.falconx.market.health;

import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * PROD-OPS-EVIDENCE-01 C2 market 端 ClickHouse 可达性健康指标。
 *
 * <p>补齐《生产观测与回滚手册》§6「market ClickHouse 连接健康指标缺失」盲区：
 * 从 {@code marketClickHouseDataSource}（HikariCP 池，连 ClickHouse 而非 owner MySQL）
 * 取连接执行 {@code SELECT 1} 探活，命中 UP（withDetail 数据库名/url host），
 * 失败 DOWN（withException）。探测异常吞掉转 DOWN，不抛。
 */
@Component("clickHouse")
public class ClickHouseHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseHealthIndicator.class);

    /** 连接有效性校验超时（秒）。 */
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    private final DataSource clickHouseDataSource;

    public ClickHouseHealthIndicator(
            @Qualifier("marketClickHouseDataSource") DataSource clickHouseDataSource
    ) {
        this.clickHouseDataSource = clickHouseDataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = clickHouseDataSource.getConnection()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                return Health.down().withDetail("reason", "connection-invalid").build();
            }
            // SELECT 1 显式探活，确认 ClickHouse 端真正可应答而非仅拿到池内连接。
            try (var statement = connection.createStatement();
                    var rs = statement.executeQuery("SELECT 1")) {
                rs.next();
            }
            return Health.up()
                    .withDetail("database", safeDatabase(connection))
                    .build();
        } catch (Exception ex) {
            log.debug("market.health.clickhouse.unreachable error={}", ex.getMessage());
            return Health.down(ex).build();
        }
    }

    /**
     * 取连接所在的 ClickHouse catalog（库名），失败回退 unknown。
     *
     * <p>不记录 jdbcUrl/用户名/密码等敏感信息，仅暴露库名作为定位线索。
     */
    private static String safeDatabase(Connection connection) {
        try {
            String catalog = connection.getCatalog();
            return (catalog == null || catalog.isBlank()) ? "unknown" : catalog;
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
