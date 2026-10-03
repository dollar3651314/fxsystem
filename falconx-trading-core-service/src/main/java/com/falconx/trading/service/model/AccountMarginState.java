package com.falconx.trading.service.model;

import java.math.BigDecimal;

/**
 * 账户 / 单仓净值与保证金率计算结果（领域读模型）。
 *
 * <p>STAGE-14C1 Task 7 引入。对齐 master §3.2「账户净值 Equity」与「保证金率 MarginLevel」公式，
 * 由 {@code AccountEquityCalculator} 在两条口径下产出：
 * <ul>
 *   <li><b>单仓口径</b>（StopOut 主路径，C1 ISOLATED）：
 *       {@code Equity_i = margin_i(AC) + uPnL_i(AC)}，{@code MarginLevel_i = Equity_i / MM_i × 100}。</li>
 *   <li><b>账户级口径</b>（ISOLATED 跨仓汇总，供展示 / CROSS 预留）：
 *       {@code Equity = balance + frozen + Σ uPnL_i(AC)}，{@code MarginLevel = Equity / Σ MM_i × 100}。</li>
 * </ul>
 *
 * <p><b>降级 / 边界约定</b>：
 * <ul>
 *   <li>FX 不可用（某仓 uPnL inAccount 为 null）→ {@code equity=null、marginLevel=null}
 *       （caller 据此降级，<b>不强平</b>）；{@code totalMaintenanceMargin} 仍按可算部分给出。</li>
 *   <li>{@code totalMaintenanceMargin == 0}（空仓位 / MM 为零）→ 除零无意义，{@code marginLevel=null}
 *       （账户级语义为「无持仓」）。</li>
 * </ul>
 *
 * <p><b>本 task 不含 status 字段</b>：{@code MarginLevelStatus} 枚举由 Task 8 创建，
 * 阈值判定（HEALTHY / MARGIN_CALL / STOP_OUT）在 {@code MarginLevelMonitor} 里基于本 record 的
 * {@code marginLevel} 完成，避免本 task 跨 task 依赖未定义类型。
 *
 * @param equity                  净值（账户币）；FX 不可用时为 {@code null}
 * @param totalMaintenanceMargin  维持保证金合计（账户币，{@code Σ MM_i}）；空仓位为 {@code 0}
 * @param marginLevel             保证金率（百分比，已 ×100；2 位 HALF_UP）；FX 不可用 / 除零时为 {@code null}
 */
public record AccountMarginState(
        BigDecimal equity,
        BigDecimal totalMaintenanceMargin,
        BigDecimal marginLevel
) {
}
