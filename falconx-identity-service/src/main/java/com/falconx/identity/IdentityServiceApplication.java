package com.falconx.identity;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FalconX identity-service 启动类。
 *
 * <p>当前服务负责注册、登录、JWT、用户状态管理，以及
 * `falconx.trading.deposit.credited` 事件的消费幂等记录。
 *
 * <p>{@code @EnableScheduling}（2026-05-26 性能加固加入）让 inbox cleanup
 * scheduler 等定时任务生效。
 */
@SpringBootApplication(scanBasePackages = {
        "com.falconx.identity",
        "com.falconx.infrastructure"
})
@EnableScheduling
@MapperScan("com.falconx.identity.repository.mapper")
public class IdentityServiceApplication {

    /**
     * identity-service 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
