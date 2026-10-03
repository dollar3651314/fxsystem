package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.MarginModeQueryResult;
import com.falconx.trading.dto.MarginModeSwitchResult;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.contract.event.AccountMarginModeChangedEventPayload;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.TradingAccountService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * STAGE-14D1 Task 3：{@link MarginModeSwitchApplicationService} 单元测试（mock repo / switch / properties）。
 *
 * <p>覆盖闸门顺序（同模式 30083 → CROSS gate 30088 → OPEN 持仓 30080 → 开仓挂单 30081 → 冷静期 30082）、
 * cross_mode.enabled 开启后 ISOLATED→CROSS 正常切换（verify switchMarginMode + coolingUntil=now+5m）、
 * queryMode 返回 blockers。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarginModeSwitchApplicationServiceTests {

    private static final long USER_ID = 1001L;
    private static final long ACCOUNT_ID = 9001L;
    private static final String SETTLEMENT = "USDT";

    @Mock
    private TradingAccountService tradingAccountService;

    @Mock
    private TradingAccountRepository tradingAccountRepository;

    @Mock
    private TradingPositionRepository tradingPositionRepository;

    @Mock
    private TradingPendingOrderTriggerRepository tradingPendingOrderTriggerRepository;

    @Mock
    private RedisTradingRiskSwitchCache riskSwitchCache;

    @Mock
    private TradingOutboxRepository tradingOutboxRepository;

    @Mock
    private TradingNotificationApplicationService notificationService;

    @Mock
    private TradingRiskConfigRepository riskConfigRepository;

    private TradingCoreServiceProperties properties() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        props.getMarginMode().setCoolingDuration(Duration.ofMinutes(5));
        return props;
    }

    private MarginModeSwitchApplicationService service() {
        return new MarginModeSwitchApplicationService(
                tradingAccountService,
                tradingAccountRepository,
                tradingPositionRepository,
                tradingPendingOrderTriggerRepository,
                riskSwitchCache,
                tradingOutboxRepository,
                notificationService,
                riskConfigRepository,
                properties());
    }

    private static TradingAccount account(TradingMarginMode mode, OffsetDateTime coolingUntil) {
        return new TradingAccount(
                ACCOUNT_ID, USER_ID, SETTLEMENT,
                new BigDecimal("1000.00000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                mode, null, coolingUntil,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now());
    }

    private static TradingPosition openPosition() {
        return Mockito.mock(TradingPosition.class);
    }

    @Test
    void switchMode_目标与当前相同_拒30083() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.ISOLATED))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.MODE_NO_CHANGE);
    }

    @Test
    void switchMode_切CROSS但开关未开_拒30088() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(false);

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.CROSS))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.CROSS_MODE_NOT_ENABLED);
    }

    @Test
    void switchMode_有OPEN持仓_拒30080() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID))
                .thenReturn(List.of(openPosition()));

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.CROSS))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.MODE_HAS_OPEN_POSITIONS);
    }

    @Test
    void switchMode_有开仓挂单_拒30081() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(2L);

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.CROSS))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.MODE_HAS_ACTIVE_PENDING);
    }

    @Test
    void switchMode_冷静期内_拒30082() {
        OffsetDateTime coolingUntil = OffsetDateTime.now().plusMinutes(3);
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, coolingUntil));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.CROSS))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(ex -> ((TradingBusinessException) ex).getErrorCode())
                .isEqualTo(TradingErrorCode.MODE_COOLING_PERIOD_ACTIVE);
    }

    @Test
    void switchMode_开关开启且无阻断_ISOLATED切CROSS成功_冷静期5分钟() {
        OffsetDateTime before = OffsetDateTime.now();
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        Mockito.when(tradingAccountRepository.switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                Mockito.any(), Mockito.any())).thenReturn(1);

        MarginModeSwitchResult result = service().switchMode(USER_ID, TradingMarginMode.CROSS);

        assertThat(result.oldMode()).isEqualTo("ISOLATED");
        assertThat(result.newMode()).isEqualTo("CROSS");

        ArgumentCaptor<OffsetDateTime> changedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> coolingUntil = ArgumentCaptor.forClass(OffsetDateTime.class);
        Mockito.verify(tradingAccountRepository).switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                changedAt.capture(), coolingUntil.capture());

        // coolingUntil ≈ changedAt + 5min
        long gapSeconds = Duration.between(changedAt.getValue(), coolingUntil.getValue()).toSeconds();
        assertThat(gapSeconds).isEqualTo(300L);
        assertThat(result.coolingUntil()).isEqualTo(coolingUntil.getValue());
        assertThat(changedAt.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    void switchMode_成功后发Outbox_eventType与payload正确() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        Mockito.when(tradingAccountRepository.switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                Mockito.any(), Mockito.any())).thenReturn(1);

        MarginModeSwitchResult result = service().switchMode(USER_ID, TradingMarginMode.CROSS);

        ArgumentCaptor<TradingOutboxMessage> outbox = ArgumentCaptor.forClass(TradingOutboxMessage.class);
        Mockito.verify(tradingOutboxRepository).save(outbox.capture());
        TradingOutboxMessage message = outbox.getValue();
        // resolveTopic("trading.account.mode.changed") = "falconx.trading.account.mode.changed"
        assertThat(message.eventType()).isEqualTo("trading.account.mode.changed");
        assertThat(message.partitionKey()).isEqualTo(String.valueOf(USER_ID));
        assertThat(message.payload()).isInstanceOf(AccountMarginModeChangedEventPayload.class);
        AccountMarginModeChangedEventPayload payload =
                (AccountMarginModeChangedEventPayload) message.payload();
        assertThat(payload.userId()).isEqualTo(USER_ID);
        assertThat(payload.oldMode()).isEqualTo("ISOLATED");
        assertThat(payload.newMode()).isEqualTo("CROSS");
        assertThat(payload.changedAtMillis()).isEqualTo(result.modeChangedAt().toInstant().toEpochMilli());
        assertThat(payload.coolingUntilMillis())
                .isEqualTo(result.coolingUntil().toInstant().toEpochMilli());
    }

    @Test
    void switchMode_成功后发通知_code与params正确() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        Mockito.when(tradingAccountRepository.switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                Mockito.any(), Mockito.any())).thenReturn(1);

        service().switchMode(USER_ID, TradingMarginMode.CROSS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(notificationService).send(
                Mockito.eq("ACCOUNT_MODE_CHANGED"),
                Mockito.eq(USER_ID),
                Mockito.eq("ACCOUNT_MODE_CHANGED"),
                params.capture(),
                Mockito.eq("ACCOUNT"),
                Mockito.eq(Long.valueOf(ACCOUNT_ID)),
                Mockito.isNull());
        assertThat(params.getValue())
                .containsEntry("oldMode", "ISOLATED")
                .containsEntry("newMode", "CROSS");
    }

    @Test
    void switchMode_闸门拒绝时_不发Outbox也不发通知() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));

        assertThatThrownBy(() -> service().switchMode(USER_ID, TradingMarginMode.ISOLATED))
                .isInstanceOf(TradingBusinessException.class);

        Mockito.verifyNoInteractions(tradingOutboxRepository);
        Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    void queryMode_有持仓且冷静期内_blockers含OPEN和COOLING_canSwitchFalse() {
        OffsetDateTime coolingUntil = OffsetDateTime.now().plusMinutes(2);
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, coolingUntil));
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID))
                .thenReturn(List.of(openPosition()));
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);

        MarginModeQueryResult result = service().queryMode(USER_ID);

        assertThat(result.currentMode()).isEqualTo("ISOLATED");
        assertThat(result.canSwitch()).isFalse();
        assertThat(result.blockers()).contains("OPEN_POSITIONS", "COOLING");
        assertThat(result.coolingUntil()).isEqualTo(coolingUntil);
    }

    @Test
    void queryMode_无任何阻断_canSwitchTrue_blockers空() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);

        MarginModeQueryResult result = service().queryMode(USER_ID);

        assertThat(result.canSwitch()).isTrue();
        assertThat(result.blockers()).isEmpty();
        // cross_mode 开关未开（默认）→ crossModeEnabled=false，前端据此 disable CROSS。
        assertThat(result.crossModeEnabled()).isFalse();
    }

    @Test
    void queryMode_cross开关开启_crossModeEnabledTrue() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);

        MarginModeQueryResult result = service().queryMode(USER_ID);

        assertThat(result.crossModeEnabled()).isTrue();
    }

    @Test
    void queryMode_当前已是CROSS但CROSS开关未开_blockers含CROSS_DISABLED() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.CROSS, null));
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(false);

        MarginModeQueryResult result = service().queryMode(USER_ID);

        // 当前已是 CROSS，目标切回 ISOLATED 不受 CROSS gate 约束，故 canSwitch 仍可为 true，
        // 但 blockers 仅列“当前阻断切到非当前模式”的原因；CROSS_DISABLED 只在目标为 CROSS 时阻断。
        // 此处当前=CROSS，切到 ISOLATED 不需要 cross_mode.enabled，因此不应出现 CROSS_DISABLED。
        assertThat(result.blockers()).doesNotContain("CROSS_DISABLED");
        assertThat(result.canSwitch()).isTrue();
    }

    @Test
    void switchMode_usesCoolingPeriodFromDbWhenPresent() {
        OffsetDateTime before = OffsetDateTime.now();
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        // DB 平台行配置冷静期 120s（优先于 properties 默认 300s）
        Mockito.when(riskConfigRepository.findPlatformCoolingPeriodSeconds())
                .thenReturn(java.util.Optional.of(120));
        Mockito.when(tradingAccountRepository.switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                Mockito.any(), Mockito.any())).thenReturn(1);

        MarginModeSwitchResult result = service().switchMode(USER_ID, TradingMarginMode.CROSS);

        ArgumentCaptor<OffsetDateTime> changedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> coolingUntil = ArgumentCaptor.forClass(OffsetDateTime.class);
        Mockito.verify(tradingAccountRepository).switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                changedAt.capture(), coolingUntil.capture());

        // coolingUntil = changedAt + 120s（取自 DB，非 properties 300s）
        long gapSeconds = Duration.between(changedAt.getValue(), coolingUntil.getValue()).toSeconds();
        assertThat(gapSeconds).isEqualTo(120L);
        assertThat(result.coolingUntil()).isEqualTo(coolingUntil.getValue());
        assertThat(changedAt.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    void switchMode_fallsBackToPropertiesDefaultWhenDbMissing() {
        Mockito.when(tradingAccountService.getExistingAccountForUpdate(USER_ID, SETTLEMENT))
                .thenReturn(account(TradingMarginMode.ISOLATED, null));
        Mockito.when(riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false))
                .thenReturn(true);
        Mockito.when(tradingPositionRepository.findOpenByUserId(USER_ID)).thenReturn(List.of());
        Mockito.when(tradingPendingOrderTriggerRepository.countUserOpeningPending(USER_ID, null, null))
                .thenReturn(0L);
        // 平台行缺失冷静期 → 回退 properties 默认 300s
        Mockito.when(riskConfigRepository.findPlatformCoolingPeriodSeconds())
                .thenReturn(java.util.Optional.empty());
        Mockito.when(tradingAccountRepository.switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                Mockito.any(), Mockito.any())).thenReturn(1);

        service().switchMode(USER_ID, TradingMarginMode.CROSS);

        ArgumentCaptor<OffsetDateTime> changedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> coolingUntil = ArgumentCaptor.forClass(OffsetDateTime.class);
        Mockito.verify(tradingAccountRepository).switchMarginMode(
                Mockito.eq(ACCOUNT_ID), Mockito.eq(TradingMarginMode.CROSS),
                changedAt.capture(), coolingUntil.capture());

        // coolingUntil = changedAt + properties 默认 300s
        long gapSeconds = Duration.between(changedAt.getValue(), coolingUntil.getValue()).toSeconds();
        assertThat(gapSeconds).isEqualTo(300L);
    }
}
