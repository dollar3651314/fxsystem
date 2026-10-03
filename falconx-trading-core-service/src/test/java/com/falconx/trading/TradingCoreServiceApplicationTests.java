package com.falconx.trading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * trading-core-service 最小上下文测试。
 *
 * <p>该测试用于保障交易核心在 Stage 5 真实 MySQL 与 Redis 接入后，
 * 仍然能够稳定完成上下文启动。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class TradingCoreServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
