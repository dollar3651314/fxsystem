package com.falconx.trading.service;

import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.service.model.AccountMarginState;
import java.math.BigDecimal;

/**
 * MarginLevel 阈值判定 + MarginCall 节流通知（STAGE-14C1 Task 8）。
 *
 * <p>职责（master §6.2）：根据 {@link AccountMarginState#marginLevel()} 把账户 / 单仓判为
 * {@link MarginLevelStatus} 三态之一；进入 MARGIN_CALL 时推送告警（同用户 5min 节流），
 * 进入 STOP_OUT 时仅返回状态、<b>不在本组件执行强平</b>（强平由 Task 9 的 QuoteDrivenEngine
 * 调用方执行，且 STOP_OUT 通知在强平完成、拿到「已平仓位」信息后由 Task 9 发出）。
 *
 * <p><b>口径统一</b>：{@code state.marginLevel()} 是百分比数值（×100，如 184.06 表示 184%），
 * 而入参 {@code stopOutLevel}/{@code marginCallLevel} 是小数（0.30 / 1.00）。判定前内部把
 * marginLevel 换算成小数（{@code /100}）再比较，边界 100% / 30% 在实现与测试中覆盖。
 *
 * <p>{@code marginLevel == null}（无持仓 / 除零 / FX 降级不可判定）→ 一律 {@link MarginLevelStatus#HEALTHY}，
 * 不告警、不强平（降级不误触发）。
 */
public interface MarginLevelMonitor {

    /**
     * 用显式阈值判定（测试 / caller 已读好阈值时用）。
     *
     * @param userId          用户 ID（MarginCall 节流维度）
     * @param state           账户 / 单仓净值保证金率状态
     * @param stopOutLevel    StopOut 阈值（小数，如 0.30）
     * @param marginCallLevel MarginCall 阈值（小数，如 1.00）
     * @return 三态判定结果
     */
    MarginLevelStatus evaluate(Long userId,
                               AccountMarginState state,
                               BigDecimal stopOutLevel,
                               BigDecimal marginCallLevel);

    /**
     * 内部读取 t_risk_config 平台行阈值（带缓存 + 默认兜底）后判定。
     */
    MarginLevelStatus evaluate(Long userId, AccountMarginState state);

    /** 当前 StopOut 阈值（小数）：读 t_risk_config 平台行 + 本地缓存 + 默认 0.30 兜底。 */
    BigDecimal currentStopOutLevel();

    /** 当前 MarginCall 阈值（小数）：读 t_risk_config 平台行 + 本地缓存 + 默认 1.00 兜底。 */
    BigDecimal currentMarginCallLevel();
}
