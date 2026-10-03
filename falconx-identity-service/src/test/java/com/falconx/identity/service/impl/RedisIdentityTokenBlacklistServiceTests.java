package com.falconx.identity.service.impl;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `RedisIdentityTokenBlacklistService` 单元测试。
 *
 * <p>该测试只验证当前 Stage 6B 已落地的黑名单 owner 存储语义：
 * TTL 正常时写入 Redis，TTL 小于等于零时跳过写入。
 */
@ExtendWith(MockitoExtension.class)
class RedisIdentityTokenBlacklistServiceTests {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void shouldWriteBlacklistEntryWithProvidedTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisIdentityTokenBlacklistService service = new RedisIdentityTokenBlacklistService(stringRedisTemplate);

        service.blacklistToken("jti-ttl-positive", Duration.ofMinutes(15));

        verify(valueOperations).set(
                "falconx:auth:token:blacklist:jti-ttl-positive",
                "1",
                Duration.ofMinutes(15)
        );
    }

    @Test
    void shouldSkipBlacklistWriteWhenTtlIsZeroOrNegative() {
        RedisIdentityTokenBlacklistService service = new RedisIdentityTokenBlacklistService(stringRedisTemplate);

        service.blacklistToken("jti-zero", Duration.ZERO);
        service.blacklistToken("jti-negative", Duration.ofSeconds(-1));

        verify(stringRedisTemplate, never()).opsForValue();
        verify(valueOperations, never()).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }
}
