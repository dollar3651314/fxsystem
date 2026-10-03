package com.falconx.market.scheduler;

import com.falconx.market.service.FxRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FX rate 超时检测 scheduler。
 *
 * <p>定时扫 FxRateService.snapshotAll, 若某 base/quote 超过 staleThresholdSeconds (admin 可配)
 * 未更新, 触发 60011 告警 hook (log warn + Prometheus metric).
 * <p>消费者: ops 监控. STAGE-14A Task 8 实施.
 */
@Component
public class FxRateStaleDetector {

    private static final Logger log = LoggerFactory.getLogger(FxRateStaleDetector.class);
    private final FxRateService service;
    private final WarningHook hook;

    public interface WarningHook {
        void onStale(String base, String quote, long ageSec);
    }

    public FxRateStaleDetector(FxRateService service, WarningHook hook) {
        this.service = service;
        this.hook = hook;
    }

    /** Spring 注入: 默认 hook 写 warn log (60011)；@Autowired 消除 Spring Boot 4 多构造函数歧义 */
    @Autowired
    public FxRateStaleDetector(FxRateService service) {
        this(service, (b, q, age) ->
            log.warn("market.fx.rate.stale [60011] {}/{} age={}s", b, q, age));
    }

    @Scheduled(fixedDelayString = "${falconx.market.fx.stale-check-interval-ms:10000}")
    public void scan() {
        try {
            for (var p : service.snapshotAll()) {
                if (service.isStale(p.baseCurrency(), p.quoteCurrency())) {
                    long ageSec = (System.currentTimeMillis() - p.eventTimeMillis()) / 1000;
                    try {
                        hook.onStale(p.baseCurrency(), p.quoteCurrency(), ageSec);
                    } catch (RuntimeException ex) {
                        log.warn("market.fx.stale-detector.hook.failed pair={}/{} reason={}",
                            p.baseCurrency(), p.quoteCurrency(), ex.getMessage(), ex);
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("market.fx.stale-detector.scan.failed reason={}", ex.getMessage(), ex);
        }
    }
}
