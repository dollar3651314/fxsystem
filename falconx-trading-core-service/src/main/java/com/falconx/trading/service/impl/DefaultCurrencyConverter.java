package com.falconx.trading.service.impl;

import com.falconx.trading.service.CurrencyConverter;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-14B Task 4：默认货币换算器。
 *
 * <p>薄包装 {@link FxRateService#queryRate}：同币种短路，其余查询实时汇率后做乘法并保留 8 位小数 HALF_UP，
 * 精度与 market-side {@code DECIMAL(24,8)} 及 {@link com.falconx.trading.service.FxRateConverterHelper} 一致。
 *
 * <p>汇率不可用（{@code queryRate} 返回 empty）时返回 {@code null} 并打 WARN，
 * 由调用方检测 null 抛 30073 FX_RATE_UNAVAILABLE；不抛异常、不退化为 {@code amount × 1}。
 */
@Service
public class DefaultCurrencyConverter implements CurrencyConverter {

    private static final Logger log = LoggerFactory.getLogger(DefaultCurrencyConverter.class);

    /** 与 market-side DECIMAL(24,8) 及 FxRateConverterHelper SCALE 对齐。 */
    private static final int SCALE = 8;

    private final FxRateService fxRateService;

    public DefaultCurrencyConverter(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @Override
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        // 同币种短路：不查询，原样返回
        if (from.equals(to)) {
            return amount;
        }

        Optional<BigDecimal> rate = fxRateService.queryRate(from, to);
        if (rate.isEmpty()) {
            // 汇率不可用 → 返回 null，由调用方抛 30073 FX_RATE_UNAVAILABLE
            log.warn("trading.fx.convert.unavailable from={} to={} amount={}", from, to, amount);
            return null;
        }

        return amount.multiply(rate.get()).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
