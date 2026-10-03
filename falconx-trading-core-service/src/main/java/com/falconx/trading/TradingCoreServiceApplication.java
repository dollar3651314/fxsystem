package com.falconx.trading;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FalconX trading-core-service 启动类。
 *
 * <p>当前阶段仅建立交易核心服务可启动骨架。
 * 后续将在该服务中逐步补充账户、账本、订单、持仓、入金入账、
 * 风控校验、强平执行和 quote-driven-engine。
 */
@SpringBootApplication(scanBasePackages = {
        "com.falconx.trading",
        "com.falconx.infrastructure"
})
@EnableScheduling
@MapperScan("com.falconx.trading.repository.mapper")
public class TradingCoreServiceApplication {

    /**
     * trading-core-service 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TradingCoreServiceApplication.class, args);
    }
}
