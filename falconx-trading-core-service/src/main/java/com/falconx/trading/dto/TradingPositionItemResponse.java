package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 持仓查询项。
 */
public record TradingPositionItemResponse(
        Long positionId,
        Long openingOrderId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal leverage,
        BigDecimal margin,
        String marginMode,
        BigDecimal liquidationPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal markPrice,
        /**
         * STAGE-14E1 Task 1（master §7.5 WebSocket 最终 break）：position 浮盈亏由单币
         * {@code unrealizedPnl}（QC 原币）硬切为双币 + 元数据。计价币代码（QC，来源 SymbolSpec），
         * 过渡期 SymbolSpec.quoteCurrency 可能为 null。
         */
        String quoteCurrency,
        /**
         * STAGE-14E1：fx(QC→AC) 换算率；同币种=1；实时 FX 不可用降级用开仓冻结 entryFxRate；
         * QC 缺失或 entryFxRate 亦缺失时为 null。
         */
        BigDecimal fxRate,
        /** STAGE-14E1：QC 原币浮盈亏（含 STAGE-12 markup，由实时 markPrice 即时算）。 */
        BigDecimal unrealizedPnlInQuote,
        /** STAGE-14E1：AC 账户币浮盈亏 = inQuote × fxRate（含 FX 降级）。 */
        BigDecimal unrealizedPnlInAccount,
        /**
         * STAGE-14E1：逐仓占用保证金（= position.margin，AC 账户币）；
         * ISOLATED 仓有值，CROSS 仓为 null（保证金不归属单仓）。不新增 DB 列，用 margin 映射。
         */
        BigDecimal isolatedMargin,
        BigDecimal closePrice,
        String closeReason,
        BigDecimal realizedPnl,
        String status,
        Boolean quoteStale,
        OffsetDateTime quoteTs,
        String quoteSource,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt,
        OffsetDateTime updatedAt,
        /**
         * 开仓已扣手续费：openFeeRate × entryPrice × quantity（USDT）。
         * UI 用作「净盈亏 = unrealizedPnl - openFee」+ 持仓行 tooltip 显示。
         */
        BigDecimal openFee,
        /** 开仓时的费率快照（开 / 平复算用同一费率），UI 可拼"5%"展示 */
        BigDecimal openFeeRate,
        /**
         * STAGE-12-GROUP-MARKUP: 开仓时用户所属组（冻结）。
         * 用户后续可能换组，但该 position 永远按 groupCodeAtOpen 算 PnL。
         */
        String groupCodeAtOpen,
        /**
         * STAGE-12-GROUP-MARKUP: 开仓时该组对该 symbol 的 bid_extra（冻结）。
         * 即使运营事后删除 / 改动 markup 配置，position 的 PnL 仍按此冻结值算。
         * SELL 持仓平仓走 ask + askExtraAtOpen；BUY 平仓走 bid + bidExtraAtOpen。
         */
        BigDecimal bidExtraAtOpen,
        /**
         * STAGE-12-GROUP-MARKUP: 开仓时该组对该 symbol 的 ask_extra（冻结）。
         */
        BigDecimal askExtraAtOpen,
        /**
         * STAGE-12-GROUP-MARKUP: 有效标记价 = 公允 mark + 持仓方向对应的冻结 markup。
         * 这是 PnL 实际使用的对比基准，与 entryPrice 同口径（都含 markup）。
         * UI 展示 markPrice (公允) + effectiveMarkPrice (有效) 让用户理解 PnL 算法。
         */
        BigDecimal effectiveMarkPrice
) {
}
