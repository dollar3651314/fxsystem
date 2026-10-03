package com.falconx.console.systemconfig;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * STAGE-13 系统配置变更广播器。
 *
 * <p>console-service 改了配置后，调用 {@link #broadcast} 把变更事件
 * 发布到 Redis pub/sub channel {@link SystemConfigChangedEvent#CHANNEL}。
 * 所有业务 service 启动时订阅此 channel，收到事件后失效本地 cache。
 *
 * <p>失败容忍：Redis 不可达时只 log 不抛错；config 已落 DB，service 下次
 * 全量拉取（启动 / 定时）仍能拿到新值，只是延迟更大。
 */
@Component
public class SystemConfigBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigBroadcaster.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SystemConfigBroadcaster(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void broadcast(SystemConfigChangedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            Long subscribers = redisTemplate.convertAndSend(SystemConfigChangedEvent.CHANNEL, payload);
            log.info("system-config.broadcast.sent key={} action={} subscribers={}",
                    event.configKey(), event.action(), subscribers);
        } catch (JacksonException e) {
            log.error("system-config.broadcast.serialize-failed key={} action={}",
                    event.configKey(), event.action(), e);
        } catch (Exception e) {
            log.error("system-config.broadcast.redis-unavailable key={} action={} —— DB 已落，service 会在下次拉取时拿到",
                    event.configKey(), event.action(), e);
        }
    }
}
