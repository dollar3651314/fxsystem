package com.falconx.trading.service.impl;

import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.service.model.MarginThresholds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link MarginLevelMonitor} 默认实现（STAGE-14C1 Task 8）。
 *
 * <p><b>口径统一</b>：{@link AccountMarginState#marginLevel()} 是百分比数值（×100，如 184.06 = 184%），
 * 阈值 stopOut/marginCall 是小数（0.30 / 1.00）。判定前把 marginLevel 换算成小数
 * （{@code ratio = marginLevel / 100}）再与阈值比较。判定规则（master §6.2）：
 * <pre>
 * ratio &gt; marginCallLevel                       → HEALTHY
 * stopOutLevel &lt; ratio ≤ marginCallLevel         → MARGIN_CALL（发告警 + 同用户 5min 节流）
 * ratio ≤ stopOutLevel                            → STOP_OUT（仅返回，强平由 Task 9 caller 执行）
 * marginLevel == null（降级 / 无持仓 / 除零）       → HEALTHY（不告警不强平）
 * </pre>
 *
 * <p><b>节流</b>：内存 {@link ConcurrentHashMap}{@code <userId, lastNotifyInstant>}，同用户两次
 * MARGIN_CALL 通知间隔 < 5min 则跳过。时间源 {@link Supplier}{@code <Instant>} 可注入，生产用
 * {@code Instant::now}，测试用可控时钟。
 *
 * <p><b>阈值来源</b>：{@code t_risk_config} 平台行（symbol IS NULL）的 stop_out_level/margin_call_level
 * （小数），本地短 TTL 缓存（默认 30s），平台行缺失用默认 0.30/1.00 兜底。
 *
 * <p><b>不在本组件强平</b>：STOP_OUT 仅返回状态；强平 + STOP_OUT_TRIGGERED 通知由 Task 9 的
 * QuoteDrivenEngine 调用方在拿到「已平仓位」信息后执行。
 */
@Service
public class DefaultMarginLevelMonitor implements MarginLevelMonitor {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarginLevelMonitor.class);

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_STOP_OUT = new BigDecimal("0.30");
    private static final BigDecimal DEFAULT_MARGIN_CALL = new BigDecimal("1.00");
    private static final Duration MARGIN_CALL_THROTTLE = Duration.ofMinutes(5);
    private static final Duration THRESHOLD_CACHE_TTL = Duration.ofSeconds(30);

    private final TradingNotificationApplicationService notificationService;
    private final TradingRiskConfigRepository riskConfigRepository;
    private final Supplier<Instant> clock;

    /** 同用户上次 MarginCall 通知时间（节流维度）。 */
    private final ConcurrentHashMap<Long, Instant> lastMarginCallNotify = new ConcurrentHashMap<>();
    /** 阈值本地缓存（平台级单一值）。 */
    private volatile CachedThresholds cachedThresholds;

    @Autowired
    public DefaultMarginLevelMonitor(TradingNotificationApplicationService notificationService,
                                     TradingRiskConfigRepository riskConfigRepository) {
        this(notificationService, riskConfigRepository, Instant::now);
    }

    /** 测试用构造：注入可控时钟。 */
    DefaultMarginLevelMonitor(TradingNotificationApplicationService notificationService,
                              TradingRiskConfigRepository riskConfigRepository,
                              Supplier<Instant> clock) {
        this.notificationService = notificationService;
        this.riskConfigRepository = riskConfigRepository;
        this.clock = clock;
    }

    @Override
    public MarginLevelStatus evaluate(Long userId, AccountMarginState state) {
        return evaluate(userId, state, currentStopOutLevel(), currentMarginCallLevel());
    }

    @Override
    public MarginLevelStatus evaluate(Long userId,
                                      AccountMarginState state,
                                      BigDecimal stopOutLevel,
                                      BigDecimal marginCallLevel) {
        if (state == null || state.marginLevel() == null) {
            // 降级 / 无持仓 / 除零 → 不可判定 → HEALTHY（不告警不强平）
            return MarginLevelStatus.HEALTHY;
        }
        // 百分比 → 小数，与阈值口径统一
        BigDecimal ratio = state.marginLevel().divide(HUNDRED);

        if (ratio.compareTo(marginCallLevel) > 0) {
            return MarginLevelStatus.HEALTHY;
        }
        if (ratio.compareTo(stopOutLevel) <= 0) {
            // 仅判定，强平 + STOP_OUT 通知由 Task 9 caller 执行
            return MarginLevelStatus.STOP_OUT;
        }
        // stopOutLevel < ratio ≤ marginCallLevel → MARGIN_CALL
        maybeSendMarginCall(userId, state.marginLevel());
        return MarginLevelStatus.MARGIN_CALL;
    }

    /** MarginCall 通知 + 同用户 5min 节流。 */
    private void maybeSendMarginCall(Long userId, BigDecimal marginLevelPercent) {
        Instant now = clock.get();
        Instant last = lastMarginCallNotify.get(userId);
        if (last != null && Duration.between(last, now).compareTo(MARGIN_CALL_THROTTLE) < 0) {
            // 节流窗口内，跳过
            return;
        }
        lastMarginCallNotify.put(userId, now);
        // V31 模板 MARGIN_CALL_TRIGGERED 占位 ${marginLevel}（显示百分比数值）
        Map<String, String> params = Map.of("marginLevel", marginLevelPercent.toPlainString());
        notificationService.send(
                "MARGIN_CALL_TRIGGERED",
                userId,
                "MARGIN_CALL_TRIGGERED",
                params,
                "RISK",
                null,
                null);
    }

    @Override
    public BigDecimal currentStopOutLevel() {
        return loadThresholds().stopOutLevel();
    }

    @Override
    public BigDecimal currentMarginCallLevel() {
        return loadThresholds().marginCallLevel();
    }

    /** 读 t_risk_config 平台行阈值 + 本地 TTL 缓存 + 默认兜底。 */
    private MarginThresholds loadThresholds() {
        Instant now = clock.get();
        CachedThresholds cached = cachedThresholds;
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.thresholds();
        }
        MarginThresholds fresh = fetchThresholds();
        cachedThresholds = new CachedThresholds(fresh, now.plus(THRESHOLD_CACHE_TTL));
        return fresh;
    }

    private MarginThresholds fetchThresholds() {
        Optional<MarginThresholds> row = riskConfigRepository.findPlatformMarginThresholds();
        if (row.isEmpty() || row.get().stopOutLevel() == null || row.get().marginCallLevel() == null) {
            log.warn("trading.margin.thresholds.fallback-default stopOut={} marginCall={}",
                    DEFAULT_STOP_OUT, DEFAULT_MARGIN_CALL);
            return new MarginThresholds(DEFAULT_STOP_OUT, DEFAULT_MARGIN_CALL);
        }
        return row.get();
    }

    private record CachedThresholds(MarginThresholds thresholds, Instant expiresAt) {
    }
}
