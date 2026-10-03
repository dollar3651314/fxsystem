package com.falconx.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FalconX 统一北向入口服务启动类。
 *
 * <p>当前阶段该服务只建立 Spring Cloud Gateway 的可启动骨架，
 * 后续阶段再逐步补充路由、鉴权、traceId 注入、统一错误映射与聚合查询能力。
 *
 * <p>该类是 gateway 服务的唯一启动入口，
 * 运行后应仅承担网关层职责，不直接承载业务库访问或交易编排逻辑。
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * Gateway 服务进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
