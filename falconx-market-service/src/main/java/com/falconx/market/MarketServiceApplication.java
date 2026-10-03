package com.falconx.market;

import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FalconX market-service 启动类。
 *
 * <p>当前服务负责接入自建 LP 行情、写 Redis 最新价缓存、
 * ClickHouse 报价与 K 线持久化，并发布价格事件。
 */
@SpringBootApplication(scanBasePackages = {
        "com.falconx.market",
        "com.falconx.infrastructure"
})
@EnableScheduling
@MapperScans({
        @MapperScan(
                basePackages = "com.falconx.market.repository.mapper",
                sqlSessionFactoryRef = "marketOwnerSqlSessionFactory"
        ),
        @MapperScan(
                basePackages = "com.falconx.market.analytics.mapper",
                sqlSessionFactoryRef = "marketAnalyticsSqlSessionFactory"
        )
})
public class MarketServiceApplication {

    /**
     * market-service 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MarketServiceApplication.class, args);
    }
}
