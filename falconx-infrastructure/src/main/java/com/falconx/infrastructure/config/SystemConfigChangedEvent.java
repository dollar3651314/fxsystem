package com.falconx.infrastructure.config;

/**
 * STAGE-13 配置变更事件 — service 端订阅模型。
 *
 * <p>与 console-service 端 {@code com.falconx.console.systemconfig.SystemConfigChangedEvent}
 * 完全镜像（同字段同 JSON 序列化）；分两个文件以保持模块独立性（service 不能依赖 console-service jar）。
 *
 * <p>JSON 格式：
 * <pre>{"configKey":"gateway.ratelimit.auth-per-minute","newValue":"30","action":"UPDATE"}</pre>
 */
public record SystemConfigChangedEvent(
        String configKey,
        String newValue,
        String action
) {
    public static final String CHANNEL = "falconx:config:changed";
}
