package com.falconx.trading;

import com.falconx.market.contract.SymbolSpec;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2：trading-core 集成测试启动时把默认 SymbolSpec 写入 Redis Hash，
 * 让 IT 测试中的开仓链路不会被 SYMBOL_SPEC_NOT_FOUND 拒单。
 *
 * <p>本类位于 src/test/java，仅在 IT classpath 存在；生产 main 部署不会包含本类。
 */
@Component
public class IntegrationTestSymbolSpecSeeder {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestSymbolSpecSeeder.class);
    private static final String KEY_PREFIX = "falconx:market:symbol-spec:";

    /** 集成测试常用 platform symbols。 */
    private static final List<String> DEFAULT_SYMBOLS = List.of("BTCUSDT", "BTCUSD", "ETHUSDT", "AUDCAD", "EURUSD");

    /**
     * 已知 base currency 前缀（按长度降序匹配，先长后短，避免 BTC/ETH 被三字母 FX 规则误拆）。
     * crypto/金属 base 多为 3 字符，但保留映射以便后续追加非 3 字符 base。
     */
    private static final Map<String, String> KNOWN_BASE_CURRENCIES = Map.of(
            "BTC", "BTC",
            "ETH", "ETH",
            "XRP", "XRP",
            "XAU", "XAU"
    );

    /** quote currency 仅在末尾匹配，覆盖 crypto 稳定币（USDT/USDC）与法币（USD 等）。 */
    private static final List<String> KNOWN_QUOTE_CURRENCIES = List.of("USDT", "USDC", "USD");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public IntegrationTestSymbolSpecSeeder(StringRedisTemplate stringRedisTemplate,
                                            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void seedDefaults() {
        for (String symbol : DEFAULT_SYMBOLS) {
            String[] pair = splitCurrencyPair(symbol);
            SymbolSpec spec = new SymbolSpec(
                    symbol,
                    100,
                    new BigDecimal("0.0005"),
                    BigDecimal.ZERO,
                    new BigDecimal("0.00000001"),
                    new BigDecimal("1000000000"),
                    BigDecimal.ZERO,
                    8,
                    8,
                    pair[0],
                    pair[1],
                    categoryOf(pair[0])
            );
            try {
                stringRedisTemplate.opsForValue().set(KEY_PREFIX + symbol, objectMapper.writeValueAsString(spec));
            } catch (Exception e) {
                throw new IllegalStateException("seed symbol spec failed: " + symbol, e);
            }
        }
        log.info("trading.it.symbol-spec.seeded count={} symbols={}", DEFAULT_SYMBOLS.size(), DEFAULT_SYMBOLS);
    }

    /**
     * 按 symbol 字符串推导 base/quote currency：
     * <ol>
     *   <li>已知 quote 后缀（USDT/USDC/USD）优先从尾部剥离，剩余即 base（覆盖 crypto/金属对 USD 系）；</li>
     *   <li>否则按 6 字符 FX 对 3/3 拆分（如 EURUSD→EUR/USD、AUDCAD→AUD/CAD、EURAUD→EUR/AUD）；</li>
     *   <li>已知 crypto/金属 base 前缀（BTC/ETH/XRP/XAU）兜底校正 base。</li>
     * </ol>
     * 返回 {base, quote}。
     */
    static String[] splitCurrencyPair(String symbol) {
        for (String quote : KNOWN_QUOTE_CURRENCIES) {
            if (symbol.length() > quote.length() && symbol.endsWith(quote)) {
                return new String[] {symbol.substring(0, symbol.length() - quote.length()), quote};
            }
        }
        // 已知 crypto/金属 base 前缀
        for (Map.Entry<String, String> entry : KNOWN_BASE_CURRENCIES.entrySet()) {
            if (symbol.startsWith(entry.getKey())) {
                return new String[] {entry.getValue(), symbol.substring(entry.getKey().length())};
            }
        }
        // 标准 6 字符 FX 对：前 3 / 后 3
        if (symbol.length() == 6) {
            return new String[] {symbol.substring(0, 3), symbol.substring(3)};
        }
        // 兜底：无法可靠拆分时按前 3 / 剩余推导，并告警以便人工核对（不再用 BTC/USDT 硬编码兜底全部）
        log.warn("trading.it.symbol-spec.split-fallback symbol={}", symbol);
        return new String[] {symbol.substring(0, Math.min(3, symbol.length())),
                symbol.length() > 3 ? symbol.substring(3) : symbol};
    }

    /**
     * STAGE-14C2 Task 1：按 base currency 推导品种类目编码（1=crypto/2=forex/3=metal）。
     * crypto base（BTC/ETH/XRP）→1，金属 base（XAU）→3，其余按外汇→2。IT 默认 symbol 仅含这三类。
     */
    static Integer categoryOf(String base) {
        if (KNOWN_BASE_CURRENCIES.containsKey(base) && !"XAU".equals(base)) {
            return 1;
        }
        if ("XAU".equals(base)) {
            return 3;
        }
        return 2;
    }
}
