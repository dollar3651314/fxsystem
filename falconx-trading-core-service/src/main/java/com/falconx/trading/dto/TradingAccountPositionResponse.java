package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 交易账户下的持仓响应 DTO（账户快照 / account.update 内嵌）。
 *
 * <p>该对象用于在账户查询接口 / 用户侧 WebSocket 账户快照中返回用户当前 OPEN 持仓，
 * 并基于 Redis 最新 `markPrice` 动态拼接浮盈亏，而不是把该动态值持久化到 MySQL。
 *
 * <p>STAGE-14E1 Task 2（master §7.5 WebSocket 最终 break）：随 position 推送由单币硬切双币，
 * 删除单币 {@code unrealizedPnl}（QC 原币），改为双币 + 元数据
 * （{@code quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin}），
 * 口径对齐 {@link TradingPositionItemResponse}（复用 {@code TradingUserRealtimePayloadFactory} 的
 * {@code calculatePositionPnlInAccount} + 实时 FX，不可用降级开仓冻结 entryFxRate，不抛），
 * 避免客户端在 position 列表与账户内嵌持仓上出现两种形状。
 */
public record TradingAccountPositionResponse(
        Long positionId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal markPrice,
        /**
         * STAGE-14E1 Task 2：计价币代码（QC，来源 SymbolSpec），过渡期可能为 null。
         */
        String quoteCurrency,
        /**
         * STAGE-14E1 Task 2：fx(QC→AC) 换算率；同币种=1；实时 FX 不可用降级用开仓冻结 entryFxRate；
         * QC 缺失或 entryFxRate 亦缺失时为 null。
         */
        BigDecimal fxRate,
        /** STAGE-14E1 Task 2：QC 原币浮盈亏（含 STAGE-12 markup，由实时 markPrice 即时算）。 */
        BigDecimal unrealizedPnlInQuote,
        /** STAGE-14E1 Task 2：AC 账户币浮盈亏 = inQuote × fxRate（含 FX 降级）。 */
        BigDecimal unrealizedPnlInAccount,
        /**
         * STAGE-14E1 Task 2：逐仓占用保证金（= position.margin，AC 账户币）；
         * ISOLATED 仓有值，CROSS 仓为 null（保证金不归属单仓）。不新增 DB 列，用 margin 映射。
         */
        BigDecimal isolatedMargin,
        String marginMode,
        BigDecimal liquidationPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        boolean quoteStale,
        OffsetDateTime quoteTs,
        String priceSource
) {
}
