package com.falconx.console.customer;

import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-CUSTOMER 调余额限额双写服务（按管理端架构 §4.4）。
 *
 * <p>Redis 滑动窗口：
 *
 * <ul>
 *   <li>key: {@code falconx:admin:balance-adjust:daily:{adminUserId}:{yyyyMMdd-UTC}}</li>
 *   <li>TTL: 25h（覆盖 UTC 自然日 + 1h buffer）</li>
 *   <li>写时序：操作前 INCRBY {@code abs(deltaCents)}，超过阈值则 DECRBY 回退 + 拒绝</li>
 * </ul>
 *
 * <p>Redis 失败时直接拒绝调余额，避免限额计数不可用时高风险资金操作 fail-open。
 */
@Service
public class BalanceAdjustQuotaService {

    private static final Logger log = LoggerFactory.getLogger(BalanceAdjustQuotaService.class);
    private static final String KEY_PREFIX = "falconx:admin:balance-adjust:daily:";
    private static final Duration TTL = Duration.ofHours(25);
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;

    public BalanceAdjustQuotaService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 校验单次 + 单日累计限额；通过则在 Redis 累计 abs(deltaUSD)。
     *
     * @param adminUserId 操作管理员
     * @param deltaUSD 调整金额（可正可负）
     * @param singleLimitUSD 单次上限
     * @param dailyLimitUSD 单日上限
     * @throws AdminBusinessException 90301 单次超限 / 90302 单日超限
     */
    public void verifyAndIncrement(long adminUserId, BigDecimal deltaUSD,
                                    BigDecimal singleLimitUSD, BigDecimal dailyLimitUSD) {
        BigDecimal absDelta = deltaUSD.abs();
        if (absDelta.compareTo(singleLimitUSD) > 0) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_BALANCE_ADJUST_SINGLE_LIMIT_EXCEEDED);
        }

        String key = buildKey(adminUserId);
        long absCents = absDelta.movePointRight(2).longValueExact();
        long dailyLimitCents = dailyLimitUSD.movePointRight(2).longValueExact();

        Long newCents;
        try {
            newCents = stringRedisTemplate.opsForValue().increment(key, absCents);
            if (newCents != null && newCents == absCents) {
                stringRedisTemplate.expire(key, TTL);
            }
        } catch (Exception ex) {
            log.error("admin.balance-adjust.quota.redis-failure adminUserId={} reason={} downgrade=fail-closed",
                    adminUserId, ex.getMessage());
            throw new IllegalStateException("Balance adjust quota unavailable", ex);
        }
        if (newCents != null && newCents > dailyLimitCents) {
            try {
                stringRedisTemplate.opsForValue().increment(key, -absCents);
            } catch (Exception ignored) {
                log.warn("admin.balance-adjust.quota.rollback-failed adminUserId={} cents={}", adminUserId, absCents);
            }
            throw new AdminBusinessException(AdminErrorCode.ADMIN_BALANCE_ADJUST_DAILY_LIMIT_EXCEEDED);
        }
    }

    private String buildKey(long adminUserId) {
        return KEY_PREFIX + adminUserId + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY_FORMATTER);
    }
}
