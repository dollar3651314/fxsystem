package com.falconx.infrastructure.config;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * STAGE-13 SystemConfigClient 默认实现。
 *
 * <p>职责：
 * <ol>
 *   <li>启动时通过 HTTP 调 console-service {@code /internal/v1/system-config} 拉全量配置</li>
 *   <li>本地 ConcurrentHashMap cache</li>
 *   <li>订阅 Redis pub/sub channel {@code falconx:config:changed}，收到变更事件后更新 cache</li>
 *   <li>所有 getter 解析失败/cache 未就绪时返回 defaultValue（degraded 模式不影响业务）</li>
 * </ol>
 *
 * <p>线程模型：cache 用 ConcurrentHashMap，Redis 监听是 Spring listener thread，
 * service 读取是任意业务线程，全程 thread-safe。
 */
public class DefaultSystemConfigClient implements SystemConfigClient, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DefaultSystemConfigClient.class);
    private static final TypeReference<List<String>> LIST_STRING_TYPE = new TypeReference<>() {};

    private final SystemConfigClientProperties properties;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** key → 当前值（字符串）。命中后按 getter 类型解析。 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public DefaultSystemConfigClient(SystemConfigClientProperties properties,
                                     ReactiveRedisConnectionFactory connectionFactory,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 用 reactive connection factory 转换成同步 listener container
        this.listenerContainer = new RedisMessageListenerContainer();
        if (connectionFactory instanceof org.springframework.data.redis.connection.RedisConnectionFactory blockFactory) {
            this.listenerContainer.setConnectionFactory(blockFactory);
        }
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("system-config.client disabled (falconx.system-config.enabled=false) — 全部用 defaultValue");
            return;
        }
        try {
            bootstrapCache();
            subscribeChannel();
            ready = true;
            log.info("system-config.client.ready cacheSize={}", cache.size());
        } catch (Exception e) {
            log.error("system-config.client.bootstrap-failed — service 用 defaultValue 继续，下次 redis 推送时再 cache",
                    e);
        }
    }

    /** 启动时同步拉全量 config。失败抛出由 init() 容忍。 */
    private void bootstrapCache() throws Exception {
        URI uri = URI.create(properties.getConsoleBaseUrl() + "/internal/v1/system-config");
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getFetchTimeoutMillis()))
                .GET();
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank()) {
            reqBuilder.header("X-Internal-Token", properties.getInternalToken());
        }
        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Bootstrap fetch HTTP " + response.statusCode() + " body=" + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        JsonNode items = data.has("items") ? data.path("items") : data;
        if (!items.isArray()) {
            throw new IllegalStateException("Bootstrap response.data.items is not array: " + response.body());
        }
        int loaded = 0;
        for (JsonNode item : items) {
            String key = item.path("configKey").asText(null);
            String value = item.path("configValue").asText(null);
            if (key != null && value != null) {
                cache.put(key, value);
                loaded++;
            }
        }
        log.info("system-config.client.bootstrap.loaded count={}", loaded);
    }

    private void subscribeChannel() {
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        listenerContainer.addMessageListener(this, new PatternTopic(SystemConfigChangedEvent.CHANNEL));
        log.info("system-config.client.subscribed channel={}", SystemConfigChangedEvent.CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            SystemConfigChangedEvent event = objectMapper.readValue(body, SystemConfigChangedEvent.class);
            cache.put(event.configKey(), event.newValue() == null ? "" : event.newValue());
            log.info("system-config.client.updated key={} action={} newValue={}",
                    event.configKey(), event.action(), event.newValue());
        } catch (Exception e) {
            log.error("system-config.client.message-parse-failed body={}",
                    message == null ? "null" : new String(message.getBody()), e);
        }
    }

    // ============ getters ============

    @Override
    public String getString(String key, String defaultValue) {
        if (!ready) return defaultValue;
        String v = cache.get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        String low = v.trim().toLowerCase();
        return "true".equals(low) || "1".equals(low) || "yes".equals(low) || "on".equals(low);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("system-config.parse-int-failed key={} value={} - using default {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("system-config.parse-long-failed key={} value={}", key, v);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.warn("system-config.parse-decimal-failed key={} value={}", key, v);
            return defaultValue;
        }
    }

    @Override
    public Duration getDuration(String key, Duration defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            // 支持简写 "1h" / "30m" / "10s"
            String trimmed = v.trim();
            if (trimmed.endsWith("h")) return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("m")) return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("s")) return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            return Duration.parse(trimmed);
        } catch (Exception e) {
            log.warn("system-config.parse-duration-failed key={} value={}", key, v);
            return defaultValue;
        }
    }

    @Override
    public List<String> getStringList(String key, List<String> defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return objectMapper.readValue(v, LIST_STRING_TYPE);
        } catch (Exception e) {
            log.warn("system-config.parse-json-list-failed key={} value={}", key, v);
            return defaultValue;
        }
    }
}
