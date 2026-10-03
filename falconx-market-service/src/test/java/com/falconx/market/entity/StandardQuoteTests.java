package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-UT-011 ~ TC-GM-UT-012：
 * StandardQuote.withExtraMarkup 零开销回退 + bid/ask/mid 重算契约。
 */
class StandardQuoteTests {

    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Test
    void TC_GM_UT_011_withExtraMarkup_zero_returns_same_instance() {
        StandardQuote base = new StandardQuote(
                "BTCUSDT",
                new BigDecimal("60000.00"),
                new BigDecimal("60010.00"),
                new BigDecimal("60005.00"),
                new BigDecimal("60005.00"),
                TS, "LP", false);

        StandardQuote returned = base.withExtraMarkup(BigDecimal.ZERO, BigDecimal.ZERO);

        Assertions.assertSame(base, returned, "零加点必须直接返回 this，避免无意义对象分配");
    }

    @Test
    void TC_GM_UT_011_withExtraMarkup_null_extras_treated_as_zero() {
        StandardQuote base = new StandardQuote(
                "BTCUSDT",
                new BigDecimal("60000.00"),
                new BigDecimal("60010.00"),
                new BigDecimal("60005.00"),
                new BigDecimal("60005.00"),
                TS, "LP", false);

        StandardQuote returned = base.withExtraMarkup(null, null);

        Assertions.assertSame(base, returned, "null 加点等价零加点，必须返回 this");
    }

    @Test
    void TC_GM_UT_012_withExtraMarkup_recomputes_bid_ask_mid_mark() {
        StandardQuote base = new StandardQuote(
                "BTCUSDT",
                new BigDecimal("60000.00"),
                new BigDecimal("60010.00"),
                new BigDecimal("60005.00"),
                new BigDecimal("60005.00"),
                TS, "LP", false);

        StandardQuote applied = base.withExtraMarkup(new BigDecimal("0.50"), new BigDecimal("1.00"));

        Assertions.assertEquals(0, applied.bid().compareTo(new BigDecimal("60000.50")),
                "newBid = bid + bidExtra");
        Assertions.assertEquals(0, applied.ask().compareTo(new BigDecimal("60011.00")),
                "newAsk = ask + askExtra");
        Assertions.assertEquals(0, applied.mid().compareTo(new BigDecimal("60005.75")),
                "newMid = (newBid + newAsk) / 2");
        Assertions.assertEquals(0, applied.mark().compareTo(new BigDecimal("60005.75")),
                "mark 跟随 mid 保持一致");
        Assertions.assertEquals("BTCUSDT", applied.symbol(),
                "symbol / ts / source 不变");
        Assertions.assertEquals(base.ts(), applied.ts());
        Assertions.assertEquals(base.source(), applied.source());
    }

    @Test
    void TC_GM_UT_012_withExtraMarkup_negative_extras_apply_correctly() {
        StandardQuote base = new StandardQuote(
                "BTCUSDT",
                new BigDecimal("60000.00"),
                new BigDecimal("60010.00"),
                new BigDecimal("60005.00"),
                new BigDecimal("60005.00"),
                TS, "LP", false);

        StandardQuote applied = base.withExtraMarkup(new BigDecimal("-0.50"), new BigDecimal("-1.00"));

        Assertions.assertEquals(0, applied.bid().compareTo(new BigDecimal("59999.50")),
                "负 bidExtra（让利场景）：buyer 拿到更低 bid");
        Assertions.assertEquals(0, applied.ask().compareTo(new BigDecimal("60009.00")),
                "负 askExtra（让利场景）：buyer 用更低 ask 买入");
    }
}
