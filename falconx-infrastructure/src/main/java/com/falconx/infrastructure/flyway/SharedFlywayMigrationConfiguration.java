package com.falconx.infrastructure.flyway;

import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * FalconX 共享 Flyway 迁移配置。
 *
 * <p>Stage 5 开始，identity、market、trading-core、wallet 四个 owner 服务都要真正把 schema
 * 初始化交给 Flyway 管理。考虑到当前 Spring Boot 4 运行时并没有自动把 Flyway 初始化稳定拉起，
 * 这里在共享基础设施模块中显式声明 `Flyway` Bean，并在容器启动时执行 `migrate()`。
 *
 * <p>这样做有两个目的：
 *
 * <ol>
 *   <li>把“启动即校验并应用 owner 迁移”的行为固定下来，避免不同服务各自实现一套初始化逻辑</li>
 *   <li>让 Stage 5 的真实持久化测试和本地启动行为一致，不再依赖隐式自动配置是否生效</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SharedFlywayMigrationConfiguration {

    /**
     * 创建并执行当前服务的 Flyway 迁移器。
     *
     * <p>这里优先读取 `spring.flyway.locations`；如果调用方没有单独指定，就统一回退到
     * `classpath:db/migration`，与当前项目文档里的 owner 迁移目录约定保持一致。
     *
     * @param dataSource 当前 owner 服务的数据源
     * @param environment Spring 环境配置读取入口
     * @return 启动完成后已执行过 `migrate()` 的 Flyway 实例
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway flyway(DataSource dataSource, Environment environment) {
        String[] migrationLocations = environment.getProperty(
                "spring.flyway.locations",
                String[].class,
                new String[]{"classpath:db/migration"}
        );

        // 默认 false（2026-05-20 加固）：项目无任何 Flyway placeholder 使用，但 V22 通知模板 SQL
        // 注释和 INSERT 值里大量 `${variable}` 作为业务模板占位符。原 default=true 会让 Flyway 试图
        // 解析这些 ${...} 抛 "No value provided for placeholder"，需要每个 service 的 application.yml
        // 显式声明 placeholder-replacement: false 才能解锁。测试环境若没该配置也会全部挂。
        // 改 default=false 让"不显式启用 placeholder"这种主流意图安全；需要时显式 set true。
        boolean placeholderReplacement = environment.getProperty(
                "spring.flyway.placeholder-replacement",
                Boolean.class,
                false
        );

        return Flyway.configure()
                .dataSource(dataSource)
                .locations(Arrays.stream(migrationLocations)
                        .map(String::trim)
                        .filter(location -> !location.isEmpty())
                        .toArray(String[]::new))
                .baselineOnMigrate(environment.getProperty(
                        "spring.flyway.baseline-on-migrate",
                        Boolean.class,
                        false
                ))
                .placeholderReplacement(placeholderReplacement)
                .load();
    }
}
