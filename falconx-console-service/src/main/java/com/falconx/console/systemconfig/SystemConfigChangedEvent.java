package com.falconx.console.systemconfig;

/**
 * STAGE-13 系统配置变更事件 — 通过 Redis pub/sub 广播给所有订阅 service。
 *
 * <p>channel：{@link #CHANNEL}。各 service 启动时订阅此 channel，收到事件后：
 * <ol>
 *   <li>校验 {@code configKey} 是否是自己关心的</li>
 *   <li>从本地 cache 中失效该 key</li>
 *   <li>下次读取时按 newValue 解析并应用</li>
 * </ol>
 *
 * <p>序列化用 JSON（Jackson），格式：
 * <pre>{"configKey":"gateway.ratelimit.auth-per-minute","newValue":"30","action":"UPDATE"}</pre>
 *
 * @param configKey 配置 key
 * @param newValue 变更后值（DELETE 时为 null）
 * @param action CREATE / UPDATE / DELETE / RESET
 */
public record SystemConfigChangedEvent(
        String configKey,
        String newValue,
        String action
) {

    public static final String CHANNEL = "falconx:config:changed";
}
