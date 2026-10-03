package com.falconx.console;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FalconX console-service 启动类。
 *
 * <p>该服务是管理后台后端入口，承担：
 *
 * <ul>
 *   <li>独立 schema {@code falconx_console}（admin 用户/角色/菜单/权限/审计）</li>
 *   <li>跨业务 schema 只读查询（identity / market / trading / wallet）</li>
 *   <li>调用业务服务的 {@code /internal/v1/*} RPC 进行业务写操作</li>
 *   <li>RBAC：用户/角色/菜单/按钮四级权限点</li>
 *   <li>独立 admin JWT（私钥与 C 端隔离）</li>
 *   <li>每次管理操作写 {@code t_admin_operation_log} 审计表</li>
 * </ul>
 *
 * <p>详细架构与 RBAC 模型见 {@code docs/architecture/管理端架构.md}。
 */
@SpringBootApplication(scanBasePackages = {
        "com.falconx.console",
        "com.falconx.infrastructure"
})
@MapperScan("com.falconx.console.repository.mapper")
public class ConsoleServiceApplication {

    /**
     * console-service 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ConsoleServiceApplication.class, args);
    }
}
