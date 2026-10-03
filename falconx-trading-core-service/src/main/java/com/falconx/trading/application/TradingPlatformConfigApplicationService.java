package com.falconx.trading.application;

import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.service.model.MarginThresholds;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-14D3a Task 4：平台风控配置（冷静期 + MarginLevel 阈值）管理端编排服务。
 *
 * <p>承接 {@code AdminInternalTradingConfigController}（console 透传链路）：读/写
 * {@code t_risk_config} 平台行（symbol IS NULL）的冷静期秒数 + StopOut/MarginCall 阈值。
 * 参数范围校验在命令 record（Bean Validation），本服务只做委托 + 日志。
 *
 * <p>{@link #getPlatformConfig()} 一个 view 同时服务 D3b console 的冷静期页与阈值页；
 * 平台行缺失时回退默认（冷静期 300s、StopOut 0.30、MarginCall 1.00），与既有读路径口径一致。
 */
@Service
public class TradingPlatformConfigApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPlatformConfigApplicationService.class);

    /** 平台行缺失时的冷静期回退默认（秒），与 properties 默认一致。 */
    private static final int DEFAULT_COOLING_PERIOD_SECONDS = 300;
    /** 平台行缺失时的 StopOut 回退默认（小数）。 */
    private static final BigDecimal DEFAULT_STOP_OUT_LEVEL = new BigDecimal("0.30");
    /** 平台行缺失时的 MarginCall 回退默认（小数）。 */
    private static final BigDecimal DEFAULT_MARGIN_CALL_LEVEL = new BigDecimal("1.00");

    private final TradingRiskConfigRepository riskConfigRepository;
    private final FxPauseBehaviorRepository fxPauseBehaviorRepository;

    public TradingPlatformConfigApplicationService(
            TradingRiskConfigRepository riskConfigRepository,
            FxPauseBehaviorRepository fxPauseBehaviorRepository) {
        this.riskConfigRepository = riskConfigRepository;
        this.fxPauseBehaviorRepository = fxPauseBehaviorRepository;
    }

    /**
     * 平台风控配置视图（冷静期 + 阈值），D3b console 两页共用一个 GET。
     *
     * @param coolingPeriodSeconds 冷静期秒数
     * @param stopOutLevel         StopOut 强平阈值（小数）
     * @param marginCallLevel      MarginCall 告警阈值（小数）
     */
    public record PlatformConfigView(
            int coolingPeriodSeconds,
            BigDecimal stopOutLevel,
            BigDecimal marginCallLevel) {
    }

    /**
     * 写平台行冷静期秒数。
     *
     * @param seconds 冷静期秒数（范围已由命令 record 校验）
     */
    @Transactional
    public void updateCoolingPeriod(int seconds) {
        log.info("trading.config.cooling-period.update.request seconds={}", seconds);
        riskConfigRepository.updatePlatformCoolingPeriodSeconds(seconds);
        log.info("trading.config.cooling-period.update.completed seconds={}", seconds);
    }

    /**
     * 写平台行 MarginLevel 阈值。
     *
     * @param stopOut    StopOut 强平阈值（小数，范围已由命令 record 校验）
     * @param marginCall MarginCall 告警阈值（小数，范围已由命令 record 校验）
     */
    @Transactional
    public void updateRiskThresholds(BigDecimal stopOut, BigDecimal marginCall) {
        log.info("trading.config.risk-thresholds.update.request stopOut={} marginCall={}", stopOut, marginCall);
        riskConfigRepository.updatePlatformMarginThresholds(stopOut, marginCall);
        log.info("trading.config.risk-thresholds.update.completed stopOut={} marginCall={}", stopOut, marginCall);
    }

    /**
     * 读平台风控配置（冷静期 + 阈值），平台行缺失时回退默认。
     *
     * @return 平台配置视图
     */
    public PlatformConfigView getPlatformConfig() {
        int coolingPeriodSeconds = riskConfigRepository.findPlatformCoolingPeriodSeconds()
                .orElse(DEFAULT_COOLING_PERIOD_SECONDS);
        MarginThresholds thresholds = riskConfigRepository.findPlatformMarginThresholds()
                .orElse(new MarginThresholds(DEFAULT_STOP_OUT_LEVEL, DEFAULT_MARGIN_CALL_LEVEL));
        return new PlatformConfigView(
                coolingPeriodSeconds,
                thresholds.stopOutLevel(),
                thresholds.marginCallLevel());
    }

    /**
     * 读全部 FX_PAUSED 类目行为开关（V31 seed 8 行，按 category 升序），D3b console 配置页用。
     *
     * @return 全部类目行为开关
     */
    public List<FxPauseBehavior> listFxPauseBehaviors() {
        return fxPauseBehaviorRepository.findAll();
    }

    /**
     * 按品种类目绝对覆盖写 FX_PAUSED 行为开关（写后失效缓存即时生效），落审计列 {@code updated_by_admin_id}。
     *
     * @param category 品种类目编码（1-8，范围已由 controller @PathVariable 校验）
     * @param allowOpen 是否允许开仓
     * @param allowClose 是否允许平仓
     * @param allowLiquidation 是否允许被动强平
     * @param adminUserId 操作 admin 用户 id（审计）
     */
    @Transactional
    public void updateFxPauseBehavior(int category, boolean allowOpen, boolean allowClose,
                                      boolean allowLiquidation, Long adminUserId) {
        log.info("trading.config.fx-pause.update.request category={} open={} close={} liq={}",
                category, allowOpen, allowClose, allowLiquidation);
        fxPauseBehaviorRepository.updateByCategory(category, allowOpen, allowClose, allowLiquidation, adminUserId);
        log.info("trading.config.fx-pause.update.completed category={}", category);
    }
}
