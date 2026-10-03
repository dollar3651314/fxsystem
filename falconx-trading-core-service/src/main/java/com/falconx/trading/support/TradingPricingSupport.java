package com.falconx.trading.support;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交易价格与金额口径辅助工具。
 *
 * <p>当前阶段冻结两条高风险规则：
 *
 * <ul>
 *   <li>金额、盈亏、保证金统一保留 8 位小数；舍入口径依方法分别执行，不统一：
 *     <ul>
 *       <li>原币金额（inQuote / IM(QC) 等，由 {@link #scaleAmount} 统一处理）：8 位
 *           {@link RoundingMode#DOWN}（截断）。</li>
 *       <li>账户币换算金额（inAccount，如
 *           {@link #calculatePositionPnlInAccount} 的 {@code inQuote × fxRate}）：8 位
 *           {@link RoundingMode#HALF_UP}（对负 PnL 向绝对值更大方向舍入）。</li>
 *     </ul>
 *   </li>
 *   <li>逐仓估值与强平相关的有效标记价按持仓方向取 `bid / ask`</li>
 * </ul>
 */
public final class TradingPricingSupport {

    private static final int AMOUNT_SCALE = 8;

    private TradingPricingSupport() {
    }

    /**
     * 统一金额截断口径。
     */
    public static BigDecimal scaleAmount(BigDecimal value) {
        return value == null ? null : value.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /**
     * 解析持仓视角下的有效标记价（基准价，不含组加点）。
     *
     * <p>`BUY -> bid`，`SELL -> ask`。
     */
    public static BigDecimal resolvePositionMarkPrice(TradingQuoteSnapshot quote, TradingOrderSide side) {
        if (quote == null || side == null) {
            return null;
        }
        BigDecimal price = side == TradingOrderSide.BUY ? quote.bid() : quote.ask();
        return scaleAmount(price);
    }

    /**
     * STAGE-12-GROUP-MARKUP：解析持仓视角下含 markup 的有效标记价。
     *
     * <p>BUY 持仓平仓走 bid → effective = bid + position.bidExtraAtOpen；
     * SELL 持仓平仓走 ask → effective = ask + position.askExtraAtOpen。
     *
     * <p>用于强平判定 / unrealized PnL / 平仓 exitPrice，与 entryPrice 含 markup 口径对齐。
     */
    public static BigDecimal resolvePositionMarkPrice(TradingQuoteSnapshot quote, TradingPosition position) {
        if (quote == null || position == null) {
            return null;
        }
        BigDecimal base = position.side() == TradingOrderSide.BUY ? quote.bid() : quote.ask();
        if (base == null) {
            return null;
        }
        BigDecimal extra = position.side() == TradingOrderSide.BUY
                ? (position.bidExtraAtOpen() == null ? BigDecimal.ZERO : position.bidExtraAtOpen())
                : (position.askExtraAtOpen() == null ? BigDecimal.ZERO : position.askExtraAtOpen());
        return extra.signum() == 0 ? scaleAmount(base) : scaleAmount(base.add(extra));
    }

    /**
     * 解析市价单在下单时的参考成交价。
     *
     * <p>`BUY -> ask`，`SELL -> bid`。
     */
    public static BigDecimal resolveOrderReferencePrice(TradingQuoteSnapshot quote, TradingOrderSide side) {
        if (quote == null || side == null) {
            return null;
        }
        BigDecimal price = side == TradingOrderSide.BUY ? quote.ask() : quote.bid();
        return scaleAmount(price);
    }

    /**
     * 按平台净敞口方向解析风险观测使用的估值价格。
     *
     * <p>`净多头 -> bid`，`净空头 -> ask`，零敞口保持 `bid` 以维持确定性。
     */
    public static BigDecimal resolveExposureMarkPrice(TradingQuoteSnapshot quote, BigDecimal netExposure) {
        if (quote == null) {
            return null;
        }
        BigDecimal price = netExposure != null && netExposure.signum() < 0 ? quote.ask() : quote.bid();
        return scaleAmount(price);
    }

    /**
     * 统一持仓浮盈亏 / 已实现盈亏计算口径。
     *
     * <p>STAGE-12-GROUP-MARKUP：自动应用 position 冻结的组级 markup 到 effectiveMarkPrice，
     * 以匹配 entryPrice 口径（entry 是含 markup 的成交价）。caller 传入的是基准 bid/ask，
     * 内部加上 position.bidExtraAtOpen / askExtraAtOpen 后再算 PnL：
     * <ul>
     *   <li>BUY 持仓：平仓走 bid → effectivePrice = quote.bid + bidExtraAtOpen</li>
     *   <li>SELL 持仓：平仓走 ask → effectivePrice = quote.ask + askExtraAtOpen</li>
     * </ul>
     * 零加点直接走原 effectiveMarkPrice。
     */
    public static BigDecimal calculatePositionPnl(TradingPosition position, BigDecimal effectiveMarkPrice) {
        if (position == null || effectiveMarkPrice == null) {
            return null;
        }
        BigDecimal extra = position.side() == TradingOrderSide.BUY
                ? (position.bidExtraAtOpen() == null ? BigDecimal.ZERO : position.bidExtraAtOpen())
                : (position.askExtraAtOpen() == null ? BigDecimal.ZERO : position.askExtraAtOpen());
        BigDecimal effectivePrice = extra.signum() == 0
                ? effectiveMarkPrice
                : effectiveMarkPrice.add(extra);
        BigDecimal delta = position.side() == TradingOrderSide.BUY
                ? effectivePrice.subtract(position.entryPrice())
                : position.entryPrice().subtract(effectivePrice);
        return scaleAmount(delta.multiply(position.quantity()));
    }

    /**
     * STAGE-14B Task 7：货币感知持仓盈亏 / 已实现盈亏换算。
     *
     * <p>对齐 master §3.2：
     * <pre>
     *   PnL(QC) = calculatePositionPnl(position, effectiveMarkPrice)   (原币，含 STAGE-12 markup)
     *   PnL(AC) = PnL(QC) × fx(QC→AC)                                  (账户币，8 位 HALF_UP)
     * </pre>
     *
     * <p>原币 PnL 直接复用 {@link #calculatePositionPnl(TradingPosition, BigDecimal)}，
     * 不重复实现 markup / delta 逻辑（DRY）。FX rate 取自单一数据源
     * {@link FxRateService#queryRate}，保证 {@link PnlResult#inAccount()} 与
     * {@link PnlResult#fxRate()} 数学自洽。
     *
     * <p>口径与 {@code MarginCalculator.calculateInitialMargin} 保持一致：
     * <ul>
     *   <li>同币种（{@code quoteCurrency.equals(accountCurrency)}）短路 {@code fx=1}、
     *       {@code inAccount==inQuote}，不触发 FX 查询。</li>
     *   <li>异币种：{@code inAccount = inQuote × fxRate}，8 位 HALF_UP（对负 PnL 向绝对值更大方向舍入）。</li>
     *   <li>{@code quoteCurrency} 为空（SymbolSpec 过渡期可能 null）：不查询、
     *       {@code inAccount=null、fxRate=null}（{@code inQuote} 照常），不抛异常。</li>
     *   <li>FX 不可用（{@code queryRate} 返回 empty）：{@code inAccount=null、fxRate=null}
     *       （{@code inQuote} 照常），不抛异常，交由调用方（Task 9 落账）降级处理。</li>
     * </ul>
     *
     * @param position          持仓
     * @param effectiveMarkPrice 有效标记价 / 平仓价（基准价，内部叠加 position 冻结 markup）
     * @param quoteCurrency     计价币代码（QC），可能为 {@code null}
     * @param accountCurrency   账户币代码（AC）
     * @param fxRateService     FX 汇率服务（rate 唯一来源）
     * @return 货币感知盈亏结果 {@link PnlResult}
     */
    public static PnlResult calculatePositionPnlInAccount(TradingPosition position,
                                                          BigDecimal effectiveMarkPrice,
                                                          String quoteCurrency,
                                                          String accountCurrency,
                                                          FxRateService fxRateService) {
        BigDecimal inQuote = calculatePositionPnl(position, effectiveMarkPrice);
        if (inQuote == null) {
            // position/markPrice 缺失：无可换算金额，inAccount/fxRate 无意义，直接返回，省一次 FX 查询。
            // quoteCurrency 原样透传（可能有值也可能 null），不与下方 quoteCurrency==null 分支冲突。
            return new PnlResult(null, null, null, quoteCurrency);
        }

        // quoteCurrency 缺失：无法换算与留痕，inAccount/fxRate 置 null，不查询。
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            return new PnlResult(inQuote, null, null, null);
        }

        // 同币种短路：fx=1、inAccount==inQuote，避免无谓查询。
        if (quoteCurrency.equals(accountCurrency)) {
            return new PnlResult(inQuote, inQuote, BigDecimal.ONE, quoteCurrency);
        }

        BigDecimal fxRate = fxRateService.queryRate(quoteCurrency, accountCurrency).orElse(null);
        if (fxRate == null) {
            // FX 不可用：保留 inQuote，inAccount/fxRate 置 null 交由 caller 降级。
            return new PnlResult(inQuote, null, null, quoteCurrency);
        }
        // 走到此处 inQuote 必非 null（开头已早期短路），直接换算。
        BigDecimal inAccount = inQuote.multiply(fxRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new PnlResult(inQuote, inAccount, fxRate, quoteCurrency);
    }
}
