package com.falconx.trading.application;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.contract.event.AccountMarginModeChangedEventPayload;
import com.falconx.trading.dto.MarginModeQueryResult;
import com.falconx.trading.dto.MarginModeSwitchResult;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.TradingAccountService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-14D1 Task 3：用户级 margin mode 切换应用服务。
 *
 * <p>对应 master §6.1（切换状态机）+ §7.4（GET/POST /api/v1/me/margin-mode）。
 *
 * <p><b>切换闸门顺序</b>（{@link #switchMode} 在事务内 + 账户行 SELECT FOR UPDATE 后依次判定）：
 * <ol>
 *   <li>目标模式与当前相同 → 30083 MODE_NO_CHANGE（同模式优先短路，避免无谓查询）</li>
 *   <li>目标为 CROSS 且 {@code cross_mode.enabled} 关闭 → 30088 CROSS_MODE_NOT_ENABLED
 *       （D1 默认 gate；CROSS 强平未就位前不放用户进入无强平保护的 CROSS）</li>
 *   <li>存在 OPEN 持仓 → 30080 MODE_HAS_OPEN_POSITIONS</li>
 *   <li>存在开仓挂单 → 30081 MODE_HAS_ACTIVE_PENDING</li>
 *   <li>冷静期内 → 30082 MODE_COOLING_PERIOD_ACTIVE</li>
 * </ol>
 *
 * <p>此顺序与 master §6.1 列举的 4 项闸门一致（master 未规定 CROSS gate 的相对位置；
 * 本实现把 CROSS gate 放在同模式判定之后、持仓/挂单/冷静期之前，使“目标不可达”类错误先于
 * “账户当前状态”类错误返回，便于客户端先校验目标可行性再提示用户清理持仓/挂单）。
 *
 * <p>切换成功后在同一 {@code @Transactional} 内发 Outbox {@code falconx.trading.account.mode.changed}
 * （Task 4）+ ACCOUNT_MODE_CHANGED 站内信，使事件/通知与状态变更原子（rollback 时一并回滚）。
 */
@Service
public class MarginModeSwitchApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MarginModeSwitchApplicationService.class);

    /** queryMode blockers 标识：存在 OPEN 持仓。 */
    private static final String BLOCKER_OPEN_POSITIONS = "OPEN_POSITIONS";
    /** queryMode blockers 标识：存在开仓挂单。 */
    private static final String BLOCKER_ACTIVE_PENDING = "ACTIVE_PENDING";
    /** queryMode blockers 标识：处于冷静期。 */
    private static final String BLOCKER_COOLING = "COOLING";

    /** Outbox 事件类型（resolveTopic 加 "falconx." 前缀 → falconx.trading.account.mode.changed）。 */
    private static final String MODE_CHANGED_EVENT_TYPE = "trading.account.mode.changed";
    /** ACCOUNT_MODE_CHANGED 通知模板 code（V34 seed）。 */
    private static final String NOTIFICATION_TEMPLATE_CODE = "ACCOUNT_MODE_CHANGED";
    /** 通知 relatedKey（客户端 deep-link 指向账户）。 */
    private static final String NOTIFICATION_RELATED_KEY = "ACCOUNT";

    private final TradingAccountService tradingAccountService;
    private final TradingAccountRepository tradingAccountRepository;
    private final TradingPositionRepository tradingPositionRepository;
    private final TradingPendingOrderTriggerRepository tradingPendingOrderTriggerRepository;
    private final RedisTradingRiskSwitchCache riskSwitchCache;
    private final TradingOutboxRepository tradingOutboxRepository;
    private final TradingNotificationApplicationService notificationService;
    private final TradingRiskConfigRepository riskConfigRepository;
    private final TradingCoreServiceProperties properties;

    public MarginModeSwitchApplicationService(TradingAccountService tradingAccountService,
                                              TradingAccountRepository tradingAccountRepository,
                                              TradingPositionRepository tradingPositionRepository,
                                              TradingPendingOrderTriggerRepository tradingPendingOrderTriggerRepository,
                                              RedisTradingRiskSwitchCache riskSwitchCache,
                                              TradingOutboxRepository tradingOutboxRepository,
                                              TradingNotificationApplicationService notificationService,
                                              TradingRiskConfigRepository riskConfigRepository,
                                              TradingCoreServiceProperties properties) {
        this.tradingAccountService = tradingAccountService;
        this.tradingAccountRepository = tradingAccountRepository;
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingPendingOrderTriggerRepository = tradingPendingOrderTriggerRepository;
        this.riskSwitchCache = riskSwitchCache;
        this.tradingOutboxRepository = tradingOutboxRepository;
        this.notificationService = notificationService;
        this.riskConfigRepository = riskConfigRepository;
        this.properties = properties;
    }

    /**
     * 查询当前 margin mode 与可切换性（GET /api/v1/me/margin-mode）。
     *
     * <p>该查询为只读，blockers 列出“当前阻断切换”的状态原因（OPEN_POSITIONS / ACTIVE_PENDING / COOLING）。
     * CROSS gate 与具体目标模式相关（仅目标为 CROSS 时阻断），不属于账户当前固有状态，
     * 故不放入 blockers；客户端在 POST 时由后端按目标模式判定 30088。
     *
     * @param userId 用户 ID
     * @return 当前 mode + 切换时间 + 冷静期 + canSwitch + blockers
     */
    public MarginModeQueryResult queryMode(Long userId) {
        TradingAccount account = tradingAccountService.getExistingAccountForUpdate(
                userId, properties.getSettlementToken());

        List<String> blockers = new ArrayList<>();
        if (!tradingPositionRepository.findOpenByUserId(userId).isEmpty()) {
            blockers.add(BLOCKER_OPEN_POSITIONS);
        }
        if (tradingPendingOrderTriggerRepository.countUserOpeningPending(userId, null, null) > 0) {
            blockers.add(BLOCKER_ACTIVE_PENDING);
        }
        if (isInCoolingPeriod(account, OffsetDateTime.now())) {
            blockers.add(BLOCKER_COOLING);
        }

        boolean canSwitch = blockers.isEmpty();
        boolean crossModeEnabled = riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false);
        log.info("trading.margin-mode.query userId={} currentMode={} canSwitch={} blockers={} crossModeEnabled={}",
                userId, account.marginMode(), canSwitch, blockers, crossModeEnabled);
        return new MarginModeQueryResult(
                account.marginMode().name(),
                account.modeChangedAt(),
                account.modeCoolingUntil(),
                canSwitch,
                List.copyOf(blockers),
                crossModeEnabled);
    }

    /**
     * 切换 margin mode（POST /api/v1/me/margin-mode）。
     *
     * <p>事务内对账户行 SELECT FOR UPDATE 加锁后，按类注释的闸门顺序判定；全部通过则落库
     * （margin_mode + mode_changed_at=now + mode_cooling_until=now+coolingDuration）。
     *
     * @param userId 用户 ID
     * @param targetMode 目标 margin mode
     * @return 切换结果（oldMode/newMode/changedAt/coolingUntil）
     */
    @Transactional
    public MarginModeSwitchResult switchMode(Long userId, TradingMarginMode targetMode) {
        // a. FOR UPDATE 锁账户行，保证闸门判定与落库在同一事务内对同一行串行
        TradingAccount account = tradingAccountService.getExistingAccountForUpdate(
                userId, properties.getSettlementToken());
        TradingMarginMode currentMode = account.marginMode();
        log.info("trading.margin-mode.switch.request userId={} currentMode={} targetMode={}",
                userId, currentMode, targetMode);

        // b. 同模式短路
        if (targetMode == currentMode) {
            throw modeException(TradingErrorCode.MODE_NO_CHANGE, userId, currentMode, targetMode);
        }
        // c. 目标为 CROSS 且开关未开 → 30088（D1 默认 gate；未配 key 默认 false）
        if (targetMode == TradingMarginMode.CROSS
                && !riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false)) {
            throw modeException(TradingErrorCode.CROSS_MODE_NOT_ENABLED, userId, currentMode, targetMode);
        }
        // d. 存在 OPEN 持仓 → 30080
        if (!tradingPositionRepository.findOpenByUserId(userId).isEmpty()) {
            throw modeException(TradingErrorCode.MODE_HAS_OPEN_POSITIONS, userId, currentMode, targetMode);
        }
        // e. 存在开仓挂单 → 30081（SL/TP 必伴随 OPEN 持仓，已被 30080 覆盖，此处仅查开仓挂单）
        if (tradingPendingOrderTriggerRepository.countUserOpeningPending(userId, null, null) > 0) {
            throw modeException(TradingErrorCode.MODE_HAS_ACTIVE_PENDING, userId, currentMode, targetMode);
        }
        // f. 冷静期内 → 30082
        OffsetDateTime now = OffsetDateTime.now();
        if (isInCoolingPeriod(account, now)) {
            throw modeException(TradingErrorCode.MODE_COOLING_PERIOD_ACTIVE, userId, currentMode, targetMode);
        }

        // g. 闸门全过 → 落库（margin_mode + mode_changed_at=now + mode_cooling_until=now+coolingDuration）
        //    STAGE-14D3a Task 2：冷静期优先读 t_risk_config 平台行（cooling_period_seconds），缺失回退 properties 默认
        long coolingSeconds = riskConfigRepository.findPlatformCoolingPeriodSeconds()
                .map(Integer::longValue)
                .orElseGet(() -> properties.getMarginMode().getCoolingDuration().toSeconds());
        OffsetDateTime coolingUntil = now.plusSeconds(coolingSeconds);
        tradingAccountRepository.switchMarginMode(account.accountId(), targetMode, now, coolingUntil);

        // h. 同事务发 Outbox falconx.trading.account.mode.changed（事件与状态变更原子）
        //    + ACCOUNT_MODE_CHANGED 站内信（@Transactional 内，rollback 时事件与通知一并回滚）
        tradingOutboxRepository.save(buildModeChangedOutbox(userId, currentMode, targetMode, now, coolingUntil));
        notificationService.send(
                NOTIFICATION_TEMPLATE_CODE,
                userId,
                NOTIFICATION_TEMPLATE_CODE,
                Map.of("oldMode", currentMode.name(), "newMode", targetMode.name()),
                NOTIFICATION_RELATED_KEY,
                account.accountId(),
                null);

        log.info("trading.margin-mode.switch.succeeded userId={} accountId={} oldMode={} newMode={} coolingUntil={}",
                userId, account.accountId(), currentMode, targetMode, coolingUntil);
        return new MarginModeSwitchResult(
                currentMode.name(),
                targetMode.name(),
                now,
                coolingUntil);
    }

    /**
     * 构造 margin mode 切换 Outbox 事件。
     *
     * <p>eventType={@value #MODE_CHANGED_EVENT_TYPE}（KafkaTradingOutboxEventPublisher.resolveTopic
     * 加 "falconx." 前缀得 topic falconx.trading.account.mode.changed）；partitionKey=userId
     * 保证同一用户事件有序；payload 为 {@link AccountMarginModeChangedEventPayload}，由 Outbox 仓储
     * 序列化为 JSON 落 t_outbox.payload。
     */
    private TradingOutboxMessage buildModeChangedOutbox(Long userId,
                                                        TradingMarginMode oldMode,
                                                        TradingMarginMode newMode,
                                                        OffsetDateTime changedAt,
                                                        OffsetDateTime coolingUntil) {
        AccountMarginModeChangedEventPayload payload = new AccountMarginModeChangedEventPayload(
                userId,
                oldMode.name(),
                newMode.name(),
                changedAt.toInstant().toEpochMilli(),
                coolingUntil == null ? null : coolingUntil.toInstant().toEpochMilli());
        return new TradingOutboxMessage(
                null,
                "account-mode-changed:" + userId + ":" + changedAt.toInstant().toEpochMilli(),
                MODE_CHANGED_EVENT_TYPE,
                String.valueOf(userId),
                payload,
                TradingOutboxStatus.PENDING,
                changedAt,
                null,
                0,
                changedAt,
                null);
    }

    /** 冷静期判定：mode_cooling_until 非 null 且严格晚于参考时刻。 */
    private boolean isInCoolingPeriod(TradingAccount account, OffsetDateTime reference) {
        return account.modeCoolingUntil() != null && account.modeCoolingUntil().isAfter(reference);
    }

    private TradingBusinessException modeException(TradingErrorCode errorCode,
                                                   Long userId,
                                                   TradingMarginMode currentMode,
                                                   TradingMarginMode targetMode) {
        return new TradingBusinessException(errorCode, Map.of(
                "userId", userId,
                "currentMode", currentMode.name(),
                "targetMode", targetMode.name(),
                "rejectionReason", errorCode.name()));
    }
}
