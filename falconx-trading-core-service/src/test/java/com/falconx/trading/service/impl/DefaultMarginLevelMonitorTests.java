package com.falconx.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.service.model.MarginThresholds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DefaultMarginLevelMonitor} 单元测试。
 *
 * <p>覆盖（master §6.2 三态状态机）：
 * <ul>
 *   <li>marginLevel（百分比）> marginCall(100%) → HEALTHY，不发通知；</li>
 *   <li>100% ≥ ML > 30% → MARGIN_CALL + 发 MARGIN_CALL_TRIGGERED（params marginLevel）；</li>
 *   <li>ML ≤ 30% → STOP_OUT（仅判定，不在 Monitor 强平、不发通知）；</li>
 *   <li>MarginCall 5min 节流：同用户 4min 内不重复发、5min 后再发（可控时钟）；</li>
 *   <li>marginLevel == null → HEALTHY（降级不误触发）；</li>
 *   <li>边界：ML == 100% 归 MARGIN_CALL、ML == 30% 归 STOP_OUT；</li>
 *   <li>阈值从 t_risk_config 读 + 平台行缺失用默认 0.30/1.00。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultMarginLevelMonitorTests {

    private static final BigDecimal STOP_OUT = new BigDecimal("0.30");
    private static final BigDecimal MARGIN_CALL = new BigDecimal("1.00");

    @Mock
    private TradingNotificationApplicationService notificationService;

    @Mock
    private TradingRiskConfigRepository riskConfigRepository;

    private static AccountMarginState stateWithMarginLevel(String marginLevelPercent) {
        return new AccountMarginState(
                new BigDecimal("100"),
                new BigDecimal("50"),
                marginLevelPercent == null ? null : new BigDecimal(marginLevelPercent));
    }

    private DefaultMarginLevelMonitor newMonitor(AtomicReference<Instant> clock) {
        return new DefaultMarginLevelMonitor(notificationService, riskConfigRepository, clock::get);
    }

    @Test
    void ratio高于marginCall_为HEALTHY_不发通知() {
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        // 184% → ratio 1.84 > marginCall 1.00 → HEALTHY
        MarginLevelStatus status = monitor.evaluate(1L, stateWithMarginLevel("184.06"), STOP_OUT, MARGIN_CALL);

        assertThat(status).isEqualTo(MarginLevelStatus.HEALTHY);
        verify(notificationService, never()).send(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(notificationService, never())
                .send(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void ratio在marginCall与stopOut之间_为MARGIN_CALL_并发通知含marginLevel() {
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        // 80% → ratio 0.80，stopOut 0.30 < 0.80 ≤ 1.00 → MARGIN_CALL
        MarginLevelStatus status = monitor.evaluate(7L, stateWithMarginLevel("80.00"), STOP_OUT, MARGIN_CALL);

        assertThat(status).isEqualTo(MarginLevelStatus.MARGIN_CALL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).send(
                eq("MARGIN_CALL_TRIGGERED"),
                eq(7L),
                eq("MARGIN_CALL_TRIGGERED"),
                paramsCaptor.capture(),
                any(),
                any(),
                any());
        assertThat(paramsCaptor.getValue()).containsKey("marginLevel");
        assertThat(paramsCaptor.getValue().get("marginLevel")).isEqualTo("80.00");
    }

    @Test
    void ratio低于stopOut_为STOP_OUT_不在Monitor强平也不发通知() {
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        // 25% → ratio 0.25 ≤ stopOut 0.30 → STOP_OUT
        MarginLevelStatus status = monitor.evaluate(2L, stateWithMarginLevel("25.00"), STOP_OUT, MARGIN_CALL);

        assertThat(status).isEqualTo(MarginLevelStatus.STOP_OUT);
        // STOP_OUT 通知留 Task 9 强平完成后发，本组件不发
        verify(notificationService, never())
                .send(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void marginCall节流_4min内不重复发_5min后再发() {
        var clock = new AtomicReference<>(Instant.EPOCH);
        var monitor = newMonitor(clock);

        // t=0 第一次 → 发
        monitor.evaluate(9L, stateWithMarginLevel("80.00"), STOP_OUT, MARGIN_CALL);
        // t=+4min 第二次 → 节流，不发
        clock.set(Instant.EPOCH.plus(Duration.ofMinutes(4)));
        monitor.evaluate(9L, stateWithMarginLevel("80.00"), STOP_OUT, MARGIN_CALL);

        verify(notificationService, times(1))
                .send(eq("MARGIN_CALL_TRIGGERED"), eq(9L), any(), any(), any(), any(), any());

        // t=+5min 第三次 → 超节流窗口，再发
        clock.set(Instant.EPOCH.plus(Duration.ofMinutes(5)));
        monitor.evaluate(9L, stateWithMarginLevel("80.00"), STOP_OUT, MARGIN_CALL);

        verify(notificationService, times(2))
                .send(eq("MARGIN_CALL_TRIGGERED"), eq(9L), any(), any(), any(), any(), any());
    }

    @Test
    void marginLevel为null_为HEALTHY_降级不误触发() {
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        MarginLevelStatus status = monitor.evaluate(3L, stateWithMarginLevel(null), STOP_OUT, MARGIN_CALL);

        assertThat(status).isEqualTo(MarginLevelStatus.HEALTHY);
        verify(notificationService, never())
                .send(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void 边界_恰100pct归MARGIN_CALL_恰30pct归STOP_OUT() {
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        // ML == 100% → ratio 1.00 ≤ marginCall 1.00 → MARGIN_CALL
        assertThat(monitor.evaluate(11L, stateWithMarginLevel("100.00"), STOP_OUT, MARGIN_CALL))
                .isEqualTo(MarginLevelStatus.MARGIN_CALL);

        // ML == 30% → ratio 0.30 ≤ stopOut 0.30 → STOP_OUT
        assertThat(monitor.evaluate(12L, stateWithMarginLevel("30.00"), STOP_OUT, MARGIN_CALL))
                .isEqualTo(MarginLevelStatus.STOP_OUT);
    }

    @Test
    void 阈值从config读_平台行存在则用其值() {
        when(riskConfigRepository.findPlatformMarginThresholds())
                .thenReturn(Optional.of(new MarginThresholds(new BigDecimal("0.500000"), new BigDecimal("1.500000"))));
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        assertThat(monitor.currentStopOutLevel()).isEqualByComparingTo("0.500000");
        assertThat(monitor.currentMarginCallLevel()).isEqualByComparingTo("1.500000");

        // 120% → ratio 1.20 ≤ marginCall 1.50 且 > stopOut 0.50 → MARGIN_CALL（用 config 阈值）
        assertThat(monitor.evaluate(13L, stateWithMarginLevel("120.00")))
                .isEqualTo(MarginLevelStatus.MARGIN_CALL);
    }

    @Test
    void 阈值从config读_平台行缺失则用默认030_100() {
        when(riskConfigRepository.findPlatformMarginThresholds()).thenReturn(Optional.empty());
        var monitor = newMonitor(new AtomicReference<>(Instant.EPOCH));

        assertThat(monitor.currentStopOutLevel()).isEqualByComparingTo("0.30");
        assertThat(monitor.currentMarginCallLevel()).isEqualByComparingTo("1.00");

        // 25% → ratio 0.25 ≤ 默认 stopOut 0.30 → STOP_OUT
        assertThat(monitor.evaluate(14L, stateWithMarginLevel("25.00")))
                .isEqualTo(MarginLevelStatus.STOP_OUT);
    }
}
