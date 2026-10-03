package com.falconx.market.health;

import com.falconx.market.provider.SocketIoLpMarketQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * PROD-OPS-EVIDENCE-01 C2 market 端 LP Socket.IO 行情链路健康指标。
 *
 * <p>补齐《生产观测与回滚手册》§6「market LP 连接健康指标缺失」盲区：基于
 * {@link SocketIoLpMarketQuoteProvider} 的连接状态与最近分发时间戳判定三态：
 *
 * <ul>
 *   <li>未连接 → DOWN（reason=disconnected）。</li>
 *   <li>已连接但从未/久未收到报价（lastQuote==0 或距今 &gt; {@value #STALE_THRESHOLD_MILLIS}ms）
 *       → DOWN（reason=no-recent-quote）。</li>
 *   <li>已连接且有近期报价 → UP（connected=true / lastQuoteAgeMillis）。</li>
 * </ul>
 *
 * <p>STALE 选择 DOWN 而非自定义 Status("STALE") 的理由：DOWN 能直接被 k8s readiness 与
 * 现有 health-all canary（按非 UP 统一处理）识别并摘流，而 reason detail 仍可区分
 * disconnected / no-recent-quote 两种失败模式，无需引入额外状态码语义。
 *
 * <p>说明：本地/无 LP 环境下该指标 DOWN 属预期。
 */
@Component("lpSocketIo")
public class LpSocketIoHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(LpSocketIoHealthIndicator.class);

    /**
     * 报价新鲜度阈值（毫秒）。LP 正常 ~600 quote/s，60s 无任何分发即视为链路 stale。
     * 取 60s 给 GC pause / 短暂重连足够缓冲，又不至于让真实中断长时间无法暴露。
     */
    private static final long STALE_THRESHOLD_MILLIS = 60_000L;

    private final SocketIoLpMarketQuoteProvider lpProvider;

    public LpSocketIoHealthIndicator(SocketIoLpMarketQuoteProvider lpProvider) {
        this.lpProvider = lpProvider;
    }

    @Override
    public Health health() {
        try {
            if (!lpProvider.isLpConnected()) {
                log.debug("market.health.lp.down reason=disconnected");
                return Health.down().withDetail("reason", "disconnected").build();
            }
            long lastQuoteMillis = lpProvider.lastQuoteEpochMillis();
            if (lastQuoteMillis == 0L) {
                log.debug("market.health.lp.down reason=no-recent-quote lastQuote=never");
                return Health.down()
                        .withDetail("reason", "no-recent-quote")
                        .withDetail("connected", true)
                        .build();
            }
            long ageMillis = System.currentTimeMillis() - lastQuoteMillis;
            if (ageMillis > STALE_THRESHOLD_MILLIS) {
                log.debug("market.health.lp.down reason=no-recent-quote ageMillis={}", ageMillis);
                return Health.down()
                        .withDetail("reason", "no-recent-quote")
                        .withDetail("connected", true)
                        .withDetail("lastQuoteAgeMillis", ageMillis)
                        .build();
            }
            return Health.up()
                    .withDetail("connected", true)
                    .withDetail("lastQuoteAgeMillis", ageMillis)
                    .build();
        } catch (Exception ex) {
            log.debug("market.health.lp.error reason={}", ex.getMessage());
            return Health.down(ex).build();
        }
    }
}
