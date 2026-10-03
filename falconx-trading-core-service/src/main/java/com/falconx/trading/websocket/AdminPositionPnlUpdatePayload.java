package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理端持仓 PnL 实时更新推送 payload。
 *
 * <p>QuoteDrivenEngine 每条 tick 计算完该 symbol 上所有 OPEN 持仓的 per-position markPrice +
 * unrealizedPnl 后批量打包广播；admin 侧按 positionId merge 进表格 state，无需重新 fetch 全表。
 *
 * <p>事件 type = "admin.position.update"，channel = "admin.positions"，
 * 按 symbol 200ms 节流（AdminPositionPushThrottler 控制，与 admin.exposure 同步节奏）。
 *
 * @param symbol  本批 patch 所属的平台 symbol（一次 tick 只覆盖单 symbol）
 * @param quoteTs 触发本次 tick 的 quote 时间戳
 * @param items   该 symbol 当前全部 OPEN 持仓的 PnL 切片
 */
public record AdminPositionPnlUpdatePayload(
        String symbol,
        OffsetDateTime quoteTs,
        List<Item> items
) {

    /**
     * positionId / userId 均为雪花 ID（>2^53），用 String 输出避免前端 JSON.parse 精度损失。
     *
     * <p>STAGE-14E2 Task 1（master §8.3 E）：浮盈亏由单币 {@code unrealizedPnl}（QC 原币）硬切为
     * 双币 + 元数据（quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount），
     * 与客户端 position.update / position.pnl 同口径（复用 {@link TradingRealtimeDualPnlSupport}）。
     */
    public record Item(
            String positionId,
            String userId,
            String side,
            BigDecimal markPrice,
            /** STAGE-14E2：计价币代码（QC，来源 SymbolSpec），过渡期可能为 null。 */
            String quoteCurrency,
            /** STAGE-14E2：fx(QC→AC) 换算率；同币种=1；FX 不可用降级 entryFxRate；缺失为 null。 */
            BigDecimal fxRate,
            /** STAGE-14E2：QC 原币浮盈亏。 */
            BigDecimal unrealizedPnlInQuote,
            /** STAGE-14E2：AC 账户币浮盈亏 = inQuote × fxRate（含 FX 降级）。 */
            BigDecimal unrealizedPnlInAccount
    ) {
    }
}
