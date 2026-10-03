package com.falconx.console.customer;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * STAGE-2-CUSTOMER：调余额限额服务 fail-closed 行为测试。
 */
class BalanceAdjustQuotaServiceTests {

    @Test
    void shouldFailClosedWhenRedisQuotaCounterIsUnavailable() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString(), Mockito.anyLong()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        BalanceAdjustQuotaService service = new BalanceAdjustQuotaService(redisTemplate);

        IllegalStateException error = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.verifyAndIncrement(
                        1001L,
                        new BigDecimal("100.00"),
                        new BigDecimal("5000.00"),
                        new BigDecimal("20000.00")),
                "Redis 限额计数不可用时必须拒绝调余额，不能跳过限额继续执行");

        Assertions.assertEquals("Balance adjust quota unavailable", error.getMessage());
    }
}
