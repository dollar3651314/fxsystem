package com.falconx.infrastructure.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * STAGE-13 系统配置中心客户端 — service 端读取 API。
 *
 * <p>所有可动态调整的运维配置（限流速率 / token TTL / IP 白名单 等）通过此接口读取。
 * service 启动时全量拉取所有 config 到本地 cache，运行时通过 Redis pub/sub 接收变更事件。
 *
 * <p>调用方应用模式：
 * <pre>
 * int rate = configClient.getInt("gateway.ratelimit.auth-per-minute", 20);
 * List&lt;String&gt; ips = configClient.getStringList("gateway.security.trusted-proxy-ips",
 *     List.of("127.0.0.1"));
 * </pre>
 *
 * <p>**所有 getter 都有 defaultValue**：cache 未就绪 / key 不存在 / 解析失败时 fallback。
 * 这保证 service 在配置中心宕机时仍能用业务默认值正常运行（degraded 模式）。
 */
public interface SystemConfigClient {

    /** 原始字符串值；不存在或 cache 未就绪时返回 defaultValue。 */
    String getString(String key, String defaultValue);

    /** Boolean 值（true/1/yes/on 视为 true，其余 false）；解析失败返回 defaultValue。 */
    boolean getBoolean(String key, boolean defaultValue);

    int getInt(String key, int defaultValue);

    long getLong(String key, long defaultValue);

    BigDecimal getDecimal(String key, BigDecimal defaultValue);

    /** Duration（接受 ISO-8601 + Spring 简短记法 "1h" / "30m" / "10s"）。 */
    Duration getDuration(String key, Duration defaultValue);

    /** 解析 JSON 数组为 List&lt;String&gt;；非 JSON 数组返回 defaultValue。 */
    List<String> getStringList(String key, List<String> defaultValue);
}
