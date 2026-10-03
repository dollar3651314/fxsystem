package com.falconx.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FX 汇率交叉换算工具。
 *
 * <p>输入是 {@code base/quote → rate} 的 snapshot 映射，
 * 通过 USD 作为 pivot 计算任意两币种间的换算率。
 *
 * <p>精度统一 8 位（DECIMAL(24,8) 对齐），HALF_UP 截断。
 */
public final class FxRateConverter {

    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * USD 1:1 锚定稳定币：换算中视同 USD。与 trading-core {@code FxRateConverterHelper} 保持一致——
     * FX 源只产法币 USD 交叉对、无任何 USDT 对，USDT（稳定币）按 1:1 锚定接入。如需更多在此追加。
     */
    private static final Set<String> USD_PEGGED = Set.of("USDT");

    private final Map<String, BigDecimal> directRates;

    private static boolean isUsdEquivalent(String currency) {
        return "USD".equals(currency) || USD_PEGGED.contains(currency);
    }

    public FxRateConverter(Map<String, BigDecimal> directRates) {
        this.directRates = Map.copyOf(directRates);  // null 入参时 copyOf 抛 NPE；同时拒绝 null values
    }

    /**
     * 计算 from → to 的汇率。
     * 优先级：同币种 → 直接对 → 反向对 → 通过 USD pivot 交叉。
     *
     * @return 1 from = rate × to，找不到时返回 null
     */
    public BigDecimal rate(String from, String to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) return ONE;

        // 直接对
        BigDecimal direct = directRates.get(from + "/" + to);
        if (direct != null) return direct.setScale(SCALE, RoundingMode.HALF_UP);

        // 反向对
        BigDecimal reverse = directRates.get(to + "/" + from);
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE, RoundingMode.HALF_UP);
        }

        // USD pivot
        BigDecimal fromToUsd = currencyToUsd(from);
        BigDecimal usdToTo = usdToCurrency(to);
        if (fromToUsd == null || usdToTo == null) return null;

        return fromToUsd.multiply(usdToTo).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal currencyToUsd(String currency) {
        if (isUsdEquivalent(currency)) return ONE;
        BigDecimal direct = directRates.get(currency + "/USD");
        if (direct != null) return direct;
        BigDecimal reverse = directRates.get("USD/" + currency);
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE + 4, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal usdToCurrency(String currency) {
        if (isUsdEquivalent(currency)) return ONE;
        BigDecimal direct = directRates.get("USD/" + currency);
        if (direct != null) return direct;
        BigDecimal reverse = directRates.get(currency + "/USD");
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE + 4, RoundingMode.HALF_UP);
        }
        return null;
    }
}
