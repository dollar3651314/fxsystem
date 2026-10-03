package com.falconx.wallet;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FalconX wallet-service 启动类。
 *
 * <p>当前阶段仅建立钱包服务的可启动骨架。
 * 后续将在该服务中逐步补充地址分配、链监听、确认推进、
 * 原始交易落库和钱包事件发布能力。
 */
@SpringBootApplication(scanBasePackages = {
        "com.falconx.wallet",
        "com.falconx.infrastructure"
})
@EnableScheduling
@MapperScan("com.falconx.wallet.repository.mapper")
public class WalletServiceApplication {

    /**
     * wallet-service 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
