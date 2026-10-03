package com.falconx.market.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 市场最新报价响应 DTO。
 *
 * <p>该对象用于 market-service 北向接口返回标准最新报价，
 * 字段语义与内部 `StandardQuote` 保持一致，但只暴露查询所需的稳定结果。
 */
public record MarketQuoteResponse(
        String symbol,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal mid,
        BigDecimal mark,
        /**
         * STAGE-12-GROUP-MARKUP: 平台基准价（不含用户组 markup），与 WS price.tick 对称。
         * 前端公允标记价、markup 对照与诊断展示可使用 base*；K 线图只消费 REST 历史 K 线与
         * WSS kline.{interval}，不从 price.tick/base* 合成。含 markup 的 bid/ask/mid/mark
         * 是用户视角，撮合 / ticker 用。当用户组无 markup 配置或为 default 0 加点时，
         * base* === bid/ask/mid。业界 OKX/Binance/MT5 同款 base+effective 双字段设计。
         */
        BigDecimal baseBid,
        BigDecimal baseAsk,
        BigDecimal baseMid,
        Boolean hasMarkup,
        OffsetDateTime ts,
        String source,
        boolean stale,
        String quoteStatus,
        String qualityReason
) {
}
