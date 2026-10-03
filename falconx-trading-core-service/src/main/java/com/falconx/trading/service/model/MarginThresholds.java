package com.falconx.trading.service.model;

import java.math.BigDecimal;

/**
 * 账户级 MarginLevel 阈值（小数口径）。
 *
 * <p>STAGE-14C1 Task 8 引入。来源为 {@code t_risk_config} 平台行（symbol IS NULL）的
 * {@code stop_out_level} / {@code margin_call_level}（V31 新增列，DECIMAL(8,6)，**小数**：
 * 0.30 = 30% StopOut、1.00 = 100% MarginCall）。
 *
 * <p>注意单位：本 record 的两个阈值是<b>小数</b>；而 {@code AccountMarginState.marginLevel}
 * 是<b>百分比数值</b>（×100，如 184.06 表示 184%）。{@code MarginLevelMonitor} 比较前会把
 * marginLevel 换算成小数（{@code /100}）后再与本阈值比较，口径统一。
 *
 * @param stopOutLevel    StopOut 强平阈值（小数，默认 0.30）
 * @param marginCallLevel MarginCall 告警阈值（小数，默认 1.00）
 */
public record MarginThresholds(
        BigDecimal stopOutLevel,
        BigDecimal marginCallLevel
) {
}
