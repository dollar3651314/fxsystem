package com.falconx.gateway.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 标记 gateway E2E 测试类在执行完成后自动删除测试数据库。
 *
 * <p>被该注解标记的测试类会在 `afterAll` 阶段由
 * {@link E2EDatabaseCleanupExtension} 反射扫描所有字段名以 `_DB_NAME`
 * 结尾的 `static final String` 字段，并逐个执行
 * `DROP DATABASE IF EXISTS` 清理。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(E2EDatabaseCleanupExtension.class)
public @interface E2ECleanupDatabases {
}
