package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-2-REALTIME-DATA Phase 1：持仓 PnL 实时更新推送 payload。
 *
 * <p>轻量结构：positionId + markPrice + 双币浮盈亏 + 元数据 + liquidationDistance；
 * 客户端按 positionId 增量 patch PositionsTable，无需重新 fetch 完整持仓。
 *
 * <p>STAGE-14E1 Task 1（master §7.5 WebSocket 最终 break）：浮盈亏由单币 {@code unrealizedPnl}
 * （QC 原币）硬切为双币 + 元数据（quoteCurrency / fxRate / unrealizedPnlInQuote /
 * unrealizedPnlInAccount / isolatedMargin），与 position.update 同口径。
 *
 * <p>事件 type = "position.pnl"，复用 CHANNEL_POSITIONS（无需新增订阅 channel）。
 * 按 symbol 100ms 节流（避免高频品种打爆客户端）。
 */
public record TradingPositionPnlUpdatePayload(
        Long positionId,
        String symbol,
        String side,
        BigDecimal markPrice,
        /** STAGE-14E1：计价币代码（QC，来源 SymbolSpec），过渡期可能为 null。 */
        String quoteCurrency,
        /** STAGE-14E1：fx(QC→AC) 换算率；同币种=1；FX 不可用降级 entryFxRate；缺失为 null。 */
        BigDecimal fxRate,
        /** STAGE-14E1：QC 原币浮盈亏。 */
        BigDecimal unrealizedPnlInQuote,
        /** STAGE-14E1：AC 账户币浮盈亏 = inQuote × fxRate（含 FX 降级）。 */
        BigDecimal unrealizedPnlInAccount,
        /** STAGE-14E1：逐仓占用保证金（=position.margin）；ISOLATED 有值，CROSS 为 null。 */
        BigDecimal isolatedMargin,
        BigDecimal liquidationDistance,
        OffsetDateTime quoteTs
) {
}
