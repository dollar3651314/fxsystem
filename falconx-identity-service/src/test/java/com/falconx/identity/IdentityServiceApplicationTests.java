package com.falconx.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * identity-service 最小上下文测试。
 *
 * <p>该测试用于验证身份服务在 Stage 5 真实持久化接入后，
 * 仍然可以完成基础 Spring 上下文装配。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class IdentityServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
