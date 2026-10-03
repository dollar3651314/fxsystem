package com.falconx.gateway.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * `E2EDatabaseCleanupExtension` 的注解驱动真实 smoke 测试。
 *
 * <p>该测试不依赖 Spring，测试方法只负责准备数据库存在/不存在的前置状态。
 * 测试结束后的删库动作由 `@E2ECleanupDatabases` 驱动的扩展自动完成，
 * 最终删除结果通过测试结束后的外部验证确认。
 */
@E2ECleanupDatabases
class E2EDatabaseCleanupExtensionTests {

    private static final String JDBC_URL =
            "jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";
    private static final String CREATED_DB_NAME = "falconx_gateway_cleanup_created_" + randomSuffix();
    private static final String MISSING_DB_NAME = "falconx_gateway_cleanup_missing_" + randomSuffix();

    @Test
    void shouldPrepareCreatedAndMissingDatabasesForAnnotationDrivenCleanup() {
        dropDatabaseIfExists(CREATED_DB_NAME);
        dropDatabaseIfExists(MISSING_DB_NAME);
        createDatabase(CREATED_DB_NAME);

        Assertions.assertTrue(databaseExists(CREATED_DB_NAME));
        Assertions.assertFalse(databaseExists(MISSING_DB_NAME));
    }

    private static void createDatabase(String databaseName) {
        execute("CREATE DATABASE `" + escapeIdentifier(databaseName) + "`");
    }

    private static void dropDatabaseIfExists(String databaseName) {
        execute("DROP DATABASE IF EXISTS `" + escapeIdentifier(databaseName) + "`");
    }

    private static boolean databaseExists(String databaseName) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SHOW DATABASES LIKE '" + escapeSqlLiteral(databaseName) + "'")) {
            return resultSet.next();
        } catch (Exception exception) {
            throw new IllegalStateException("查询数据库是否存在失败: " + databaseName, exception);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("执行 SQL 失败: " + sql, exception);
        }
    }

    private static String escapeIdentifier(String value) {
        return value.replace("`", "``");
    }

    private static String escapeSqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
