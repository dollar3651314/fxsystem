package com.falconx.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Gateway 服务最小上下文加载测试。
 *
 * <p>当前测试用于验证 Stage 1 骨架下 gateway 服务至少可以完成
 * Spring Boot 与 Spring Cloud Gateway 的基础装配，不涉及任何业务路由行为验证。
 */
@SpringBootTest(classes = GatewayApplication.class)
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
