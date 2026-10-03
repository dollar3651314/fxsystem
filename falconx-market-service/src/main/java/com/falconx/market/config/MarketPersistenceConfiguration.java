package com.falconx.market.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * market 持久化配置。
 *
 * <p>该配置负责两类基础设施：
 *
 * <ul>
 *   <li>ClickHouse 数据源与 MyBatis SessionFactory</li>
 *   <li>服务启动时执行 ClickHouse 建库建表脚本</li>
 * </ul>
 *
 * <p>外部 LP 连接由 Provider 层管理，当前配置只负责 market-service 持久化基础设施。
 */
@Configuration
public class MarketPersistenceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarketPersistenceConfiguration.class);

    /**
     * market owner MySQL 数据源属性。
     *
     * <p>market-service 同时连接 MySQL 和 ClickHouse。为了让 Flyway、事务管理器和
     * 默认数据访问基础设施都指向 owner MySQL，这里显式声明主数据源属性，而不再依赖自动推断。
     *
     * @return 绑定 `spring.datasource.*` 的 MySQL 数据源属性
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties marketOwnerDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * market owner MySQL 主数据源。
     *
     * <p>该 Bean 作为 `@Primary` 数据源存在，确保：
     *
     * <ul>
     *   <li>Flyway 迁移始终作用于 `falconx_market`</li>
     *   <li>Spring JDBC 默认基础设施不会误连到 ClickHouse</li>
     * </ul>
     *
     * @param dataSourceProperties owner MySQL 数据源属性
     * @return 指向 `falconx_market` 的主数据源
     */
    @Bean
    @Primary
    DataSource marketOwnerDataSource(
            @Qualifier("marketOwnerDataSourceProperties") DataSourceProperties dataSourceProperties
    ) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * market owner MySQL 的 MyBatis SessionFactory。
     *
     * <p>market-service 同时持有 MySQL 与 ClickHouse 两套 MyBatis Mapper。
     * 由于服务内已经显式声明了 ClickHouse 的 `SqlSessionFactory`，如果不再额外声明
     * owner MySQL 的 SessionFactory，默认自动配置会回退，导致元数据 Mapper
     * 和管理端 Mapper 绑定不到对应 XML。
     *
     * @param dataSource owner MySQL 数据源
     * @return 绑定 metadata 与 admin XML 的 SessionFactory
     * @throws Exception 初始化异常
     */
    @Bean
    @Primary
    SqlSessionFactory marketOwnerSqlSessionFactory(
            @Qualifier("marketOwnerDataSource") DataSource dataSource
    ) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(resolveMapperLocations(
                "classpath*:mapper/market/metadata/*.xml",
                "classpath*:mapper/market/admin/*.xml",
                "classpath*:mapper/market/metadata/test/*.xml"
        ));
        return factoryBean.getObject();
    }

    @Bean
    @Qualifier("marketClickHouseDataSource")
    DataSource marketClickHouseDataSource(MarketServiceProperties properties) {
        // 2026-05-26 Sprint 5 A4：DriverManagerDataSource 每次拿连接都新建（不带池），
        // 高频 ingest 链路下大量连接开销 + ClickHouse 端 too many connections。
        // 换成 HikariCP 池：默认 max=10、min-idle=2、idleTimeout=10min、maxLifetime=30min。
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setJdbcUrl(properties.getAnalytics().getJdbcUrl());
        dataSource.setUsername(properties.getAnalytics().getUsername());
        dataSource.setPassword(properties.getAnalytics().getPassword());
        dataSource.setPoolName("market-clickhouse-pool");
        dataSource.setMinimumIdle(2);
        dataSource.setMaximumPoolSize(10);
        dataSource.setIdleTimeout(600_000L);     // 10 min
        dataSource.setMaxLifetime(1_800_000L);   // 30 min
        dataSource.setConnectionTimeout(30_000L);
        dataSource.setKeepaliveTime(300_000L);   // 5 min
        return dataSource;
    }

    @Bean
    SqlSessionFactory marketAnalyticsSqlSessionFactory(
            @Qualifier("marketClickHouseDataSource") DataSource dataSource
    ) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(resolveMapperLocations(
                "classpath*:mapper/market/analytics/*.xml",
                "classpath*:mapper/market/analytics/test/*.xml"
        ));
        return factoryBean.getObject();
    }

    @Bean
    ApplicationRunner marketClickHouseSchemaInitializer(@Qualifier("marketClickHouseDataSource") DataSource dataSource) {
        return args -> {
            // 2026-05-21: 按版本号依次执行所有 CH_V*.sql，新加 migration 只需放进 db/clickhouse/ 目录即可。
            // 所有脚本必须自身幂等（CREATE IF NOT EXISTS / ALTER 同值 no-op / DROP IF EXISTS），
            // 因为本机制没有版本表，每次 boot 都从头跑一遍。
            org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] resources =
                    resolver.getResources("classpath:db/clickhouse/CH_V*.sql");
            java.util.Arrays.sort(resources, java.util.Comparator.comparing(org.springframework.core.io.Resource::getFilename));
            try (Connection connection = dataSource.getConnection()) {
                for (org.springframework.core.io.Resource resource : resources) {
                    log.info("market.clickhouse.schema.apply file={}", resource.getFilename());
                    ScriptUtils.executeSqlScript(connection, resource);
                }
            }
            log.info("market.clickhouse.schema.ready scriptCount={}", resources.length);
        };
    }

    /**
     * 解析一组 MyBatis XML 资源路径。
     *
     * <p>运行时正式 XML 与测试支撑 XML 分目录存放，但两者都必须绑定到各自 owner 的
     * `SqlSessionFactory`。这里统一聚合资源，避免不同运行形态下出现“接口已扫描但 statement 未装载”。
     *
     * @param locations 资源匹配模式
     * @return 合并后的资源数组
     * @throws Exception 资源解析异常
     */
    private Resource[] resolveMapperLocations(String... locations) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        for (String location : locations) {
            Resource[] matched = resolver.getResources(location);
            for (Resource resource : matched) {
                resources.add(resource);
            }
        }
        return resources.toArray(Resource[]::new);
    }
}
