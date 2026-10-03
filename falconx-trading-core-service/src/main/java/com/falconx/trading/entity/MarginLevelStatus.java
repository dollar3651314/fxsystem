package com.falconx.trading.entity;

/**
 * 账户 / 单仓保证金率（MarginLevel）阶段状态。
 *
 * <p>STAGE-14C1 Task 8 引入。对齐 master §6.2「MarginLevel 阶段状态」状态机：
 * <pre>
 * HEALTHY      MarginLevel &gt; marginCallLevel（默认 100%）
 * MARGIN_CALL  stopOutLevel &lt; MarginLevel ≤ marginCallLevel（默认 100% ≥ ML &gt; 30%）→ 推送告警 + 5min 节流
 * STOP_OUT     MarginLevel ≤ stopOutLevel（默认 30%）→ 触发强平（强平由 Task 9 caller 执行）
 * </pre>
 *
 * <p>状态不落 schema 列，由 {@code MarginLevelMonitor} 基于 {@code AccountMarginState.marginLevel}
 * 实时判定；节流状态在 Monitor 内存维护。
 */
public enum MarginLevelStatus {
    HEALTHY,
    MARGIN_CALL,
    STOP_OUT
}
