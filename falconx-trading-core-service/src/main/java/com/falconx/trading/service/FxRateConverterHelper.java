package com.falconx.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * STAGE-14B Task 3：trading-core FX 汇率交叉换算工具。
 *
 * <p>算法与 {@code falconx-market-service FxRateConverter} 保持一致（同 commit 1378f58），
 * 内置在 trading 包，避免引入跨服务依赖。
 *
 * <p>精度统一 8 位（DECIMAL(24,8) 对齐），HALF_UP 截断。
 * USD 作为 pivot 计算任意两币种间的换算率。
 */
public final class FxRateConverterHelper {

    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * USD 1:1 锚定稳定币：换算中视同 USD。
     *
     * <p>背景：FX 汇率源仅产 8 个法币 USD 交叉对，无任何 USDT 对；而账户币是 USDT（稳定币），
     * 故所有 quote→USDT 换算原本因找不到 USDT 汇率而失败（FX_RATE_UNAVAILABLE / PnL 降级 fxRate=1.0）。
     * USDT 作为 USD 稳定币按 1:1 锚定接入：USD↔USDT=1，quote→USDT 经 quote→USD→USDT(=USD) 成立。
     * 如需接入更多 USD 锚定稳定币（USDC 等）在此追加。
     */
    private static final Set<String> USD_PEGGED = Set.of("USDT");

    private static boolean isUsdEquivalent(String currency) {
        return "USD".equals(currency) || USD_PEGGED.contains(currency);
    }

    private FxRateConverterHelper() {
        // utility
    }

    /**
     * 计算 from → to 的汇率。
     * 优先级：同币种 → 直接对 → 反向对 → 通过 USD pivot 交叉。
     *
     * @param directRates 内存 rate 映射，key 格式为 "BASE/QUOTE"
     * @param from        基础货币
     * @param to          计价货币
     * @return 1 from = rate × to；找不到路径时返回 empty
     */
    public static Optional<BigDecimal> compute(Map<String, BigDecimal> directRates, String from, String to) {
        Objects.requireNonNull(directRates, "directRates");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) return Optional.of(ONE);

        // 直接对
        BigDecimal direct = directRates.get(from + "/" + to);
        if (direct != null) return Optional.of(direct.setScale(SCALE, RoundingMode.HALF_UP));

        // 反向对
        BigDecimal reverse = directRates.get(to + "/" + from);
        if (reverse != null && reverse.signum() != 0) {
            return Optional.of(ONE.divide(reverse, SCALE, RoundingMode.HALF_UP));
        }

        // USD pivot
        BigDecimal fromToUsd = currencyToUsd(directRates, from);
        BigDecimal usdToTo = usdToCurrency(directRates, to);
        if (fromToUsd == null || usdToTo == null) return Optional.empty();

        return Optional.of(fromToUsd.multiply(usdToTo).setScale(SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal currencyToUsd(Map<String, BigDecimal> directRates, String currency) {
        if (isUsdEquivalent(currency)) return ONE;
        BigDecimal direct = directRates.get(currency + "/USD");
        if (direct != null) return direct;
        BigDecimal reverse = directRates.get("USD/" + currency);
        if (reverse != null && reverse.signum() != 0) {
            return ONE.divide(reverse, SCALE + 4, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static BigDecimal usdToCurrency(Map<String, BigDecimal> directRates, String currency) {
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
