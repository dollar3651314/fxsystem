package com.falconx.market.support;

import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * market-service MyBatis 测试支撑配置。
 *
 * <p>运行时主应用只扫描正式 owner Mapper。集成测试额外依赖测试支撑 Mapper
 * 完成清库、计数和种子状态切换，因此这里把测试包下的接口显式挂到对应的
 * MySQL / ClickHouse `SqlSessionFactory`，避免测试环境里出现 Mapper 接口可注入、
 * 但 XML statement 未绑定的情况。
 */
@TestConfiguration
@MapperScans({
        @MapperScan(
                basePackages = "com.falconx.market.repository.mapper.test",
                sqlSessionFactoryRef = "marketOwnerSqlSessionFactory"
        ),
        @MapperScan(
                basePackages = "com.falconx.market.analytics.mapper.test",
                sqlSessionFactoryRef = "marketAnalyticsSqlSessionFactory"
        )
})
public class MarketMybatisTestSupportConfiguration {
}
