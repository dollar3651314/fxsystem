package com.falconx.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * wallet-service 最小上下文测试。
 *
 * <p>该测试用于确保钱包服务在 Stage 5 真实 owner 仓储接入后仍能稳定启动，
 * 同时保留外部链监听 stub 的运行入口。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
