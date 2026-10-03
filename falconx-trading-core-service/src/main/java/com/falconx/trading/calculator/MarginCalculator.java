package com.falconx.trading.calculator;

import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 保证金与手续费计算器。
 *
 * <p>该组件负责承载最小、可测试的数值计算逻辑，
 * 避免把精度处理和公式散落在应用服务中。
 *
 * <p><b>算法层 FX 数据源约定（STAGE-14B 控制者 R1 决策）</b>：算法层（保证金 / 后续 PnL、强平等）
 * 的 FX 换算统一以 {@link FxRateService} 为唯一数据源。理由：Task 6/7/8 的结果 record
 * （{@link MarginResult}、后续 PnlResult 等）都需要把当次 {@code fxRate} 填入结果做留痕，而
 * {@code CurrencyConverter.convert}（Task 4）只返回换算后的金额、不返回 rate，无法满足留痕需求。
 * 以 rate 真源 {@code FxRateService} 单一数据源最自洽，故算法层不直接依赖 {@code CurrencyConverter}。
 * {@code CurrencyConverter} 是纯金额换算的薄包装，其定位待 Task 9 业务流路核查后收口。
 */
@Component
public class MarginCalculator {

    /**
     * 计算开仓所需初始保证金（多币种，STAGE-14B Task 6）。
     *
     * <p>对齐 master §3.2 公式：
     * <pre>
     *   IM(QC) = Notional(QC) / lev          (原币，8 位 DOWN，沿用历史口径)
     *   IM(AC) = IM(QC) × fx(QC→AC)          (账户币，8 位 HALF_UP)
     * </pre>
     *
     * <p>FX rate 取自单一数据源 {@link FxRateService#queryRate}，保证 {@link MarginResult#inAccount()}
     * 与 {@link MarginResult#fxRate()} 数学自洽。同币种（{@code quoteCurrency.equals(accountCurrency)}）
     * 短路为 {@code fx=1}，不触发查询。
     *
     * <p>FX 不可用（{@code queryRate} 返回 empty 且币种不同）时不抛异常：返回
     * {@code inAccount=null、fxRate=null}（{@code inQuote} 照常），由调用方据此拒单
     * （对应 master §7.3 错误码 30073 FX_RATE_UNAVAILABLE）。
     *
     * @param fillPrice       成交价（原币 quote currency）
     * @param quantity        数量
     * @param leverage        杠杆倍数
     * @param quoteCurrency   计价币代码（QC）
     * @param accountCurrency 账户币代码（AC）
     * @param fxRateService   FX 汇率服务（rate 唯一来源）
     * @return 三口径保证金结果 {@link MarginResult}
     */
    public MarginResult calculateInitialMargin(BigDecimal fillPrice,
                                               BigDecimal quantity,
                                               BigDecimal leverage,
                                               String quoteCurrency,
                                               String accountCurrency,
                                               FxRateService fxRateService) {
        BigDecimal inQuote = fillPrice.multiply(quantity)
                .divide(leverage, 8, RoundingMode.DOWN);

        // 同币种短路：fx=1、inAccount==inQuote，避免无谓查询，且不依赖 FxRateService 是否对同币种返回 ONE。
        if (quoteCurrency.equals(accountCurrency)) {
            return new MarginResult(inQuote, inQuote, BigDecimal.ONE);
        }

        BigDecimal fxRate = fxRateService.queryRate(quoteCurrency, accountCurrency).orElse(null);
        if (fxRate == null) {
            // FX 不可用：保留 inQuote，inAccount/fxRate 置 null 交由 caller 拒单（30073）。
            return new MarginResult(inQuote, null, null);
        }
        BigDecimal inAccount = inQuote.multiply(fxRate).setScale(8, RoundingMode.HALF_UP);
        return new MarginResult(inQuote, inAccount, fxRate);
    }

    /**
     * 计算手续费（多币种，STAGE-14B Task 8）。
     *
     * <p>对齐 master §3.2「手续费 Fee」公式：
     * <pre>
     *   Fee(QC) = Notional(QC) × feeRate = fillPrice × quantity × feeRate   (原币，8 位 DOWN，沿用历史口径)
     *   Fee(AC) = Fee(QC) × fx(QC→AC) 当时快照                              (账户币，8 位 HALF_UP)
     * </pre>
     *
     * <p><b>复用 {@link MarginResult}</b>：MarginResult 本质是通用「inQuote / inAccount / fxRate」三元组，
     * 此处 {@code inQuote} 表示 Fee(QC)、{@code inAccount} 表示 Fee(AC)、{@code fxRate} 表示当次
     * fx(quoteCurrency→accountCurrency)。复用以避免再引入一个语义重复的 record。
     *
     * <p>FX 取数与 null 口径与 {@link #calculateInitialMargin} 完全一致：同币种短路 {@code fx=1}/
     * {@code inAccount==inQuote} 不查询；异币种取 {@link FxRateService#queryRate}；FX 不可用
     * （{@code queryRate} 返回 empty 且币种不同）时不抛异常，返回 {@code inAccount=null、fxRate=null}
     * （{@code inQuote} 照常），由调用方据此拒单（master §7.3 错误码 30073 FX_RATE_UNAVAILABLE）。
     *
     * @param fillPrice       成交价（原币 quote currency）
     * @param quantity        数量
     * @param feeRate         手续费率
     * @param quoteCurrency   计价币代码（QC）
     * @param accountCurrency 账户币代码（AC）
     * @param fxRateService   FX 汇率服务（rate 唯一来源）
     * @return 三口径手续费结果 {@link MarginResult}（inQuote=Fee(QC)、inAccount=Fee(AC)、fxRate）
     */
    public MarginResult calculateFee(BigDecimal fillPrice,
                                     BigDecimal quantity,
                                     BigDecimal feeRate,
                                     String quoteCurrency,
                                     String accountCurrency,
                                     FxRateService fxRateService) {
        BigDecimal inQuote = fillPrice.multiply(quantity)
                .multiply(feeRate)
                .setScale(8, RoundingMode.DOWN);

        // 同币种短路：fx=1、inAccount==inQuote，避免无谓查询。
        if (quoteCurrency.equals(accountCurrency)) {
            return new MarginResult(inQuote, inQuote, BigDecimal.ONE);
        }

        BigDecimal fxRate = fxRateService.queryRate(quoteCurrency, accountCurrency).orElse(null);
        if (fxRate == null) {
            // FX 不可用：保留 inQuote，inAccount/fxRate 置 null 交由 caller 拒单（30073）。
            return new MarginResult(inQuote, null, null);
        }
        BigDecimal inAccount = inQuote.multiply(fxRate).setScale(8, RoundingMode.HALF_UP);
        return new MarginResult(inQuote, inAccount, fxRate);
    }
}
