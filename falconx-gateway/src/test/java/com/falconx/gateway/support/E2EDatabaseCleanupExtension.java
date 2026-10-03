package com.falconx.gateway.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 在 gateway E2E 测试结束后清理 MySQL 测试数据库的 JUnit 5 扩展。
 *
 * <p>该扩展不依赖 Spring 上下文，只读取测试类上满足约定的数据库名常量，
 * 并在 `afterAll` 中直接通过 JDBC 执行 `DROP DATABASE IF EXISTS`。
 * 任意清理失败都只记录告警，不影响测试结果。
 */
public class E2EDatabaseCleanupExtension implements AfterAllCallback {

    private static final Logger log = Logger.getLogger(E2EDatabaseCleanupExtension.class.getName());
    private static final String JDBC_URL =
            "jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    @Override
    public void afterAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        for (String databaseName : collectDatabaseNames(testClass)) {
            dropDatabaseQuietly(databaseName);
        }
    }

    private List<String> collectDatabaseNames(Class<?> testClass) {
        List<String> databaseNames = new ArrayList<>();
        Class<?> current = testClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!isDatabaseNameField(field)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof String databaseName && !databaseName.isBlank()) {
                        databaseNames.add(databaseName);
                    }
                } catch (Exception exception) {
                    log.log(Level.WARNING,
                            "gateway.e2e.db.cleanup.scan.failed class={0} field={1} reason={2}",
                            new Object[]{testClass.getName(), field.getName(), exception.getMessage()});
                }
            }
            current = current.getSuperclass();
        }
        return databaseNames;
    }

    private boolean isDatabaseNameField(Field field) {
        int modifiers = field.getModifiers();
        return field.getName().endsWith("_DB_NAME")
                && field.getType() == String.class
                && Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers);
    }

    private void dropDatabaseQuietly(String databaseName) {
        String sql = "DROP DATABASE IF EXISTS `" + databaseName.replace("`", "``") + "`";
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            log.log(Level.WARNING,
                    "gateway.e2e.db.cleanup.drop.failed db={0} reason={1}",
                    new Object[]{databaseName, exception.getMessage()});
        }
    }
}
