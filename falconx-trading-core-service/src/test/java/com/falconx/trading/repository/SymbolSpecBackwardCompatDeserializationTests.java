package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.falconx.market.contract.SymbolSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * STAGE-14B Task 5 Part A：验证 SymbolSpec 在新增 baseCurrency / quoteCurrency 两个 record
 * 组件后，仍能反序列化「市场尚未重发布」时期 Redis 里的旧 JSON 快照（不含这两个字段）。
 *
 * <p>跨服务兼容（AGENTS §3.8）：market 重新写入快照前，trading-core 读到的旧 JSON 必须能
 * 反序列化为新增字段 = null，而不能抛异常导致整条读取链路失败。本测试用 Spring Boot 4 默认
 * Jackson 3 行为（{@link JsonMapper}）作为真值验证。
 */
class SymbolSpecBackwardCompatDeserializationTests {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** 旧 JSON 没有 baseCurrency / quoteCurrency / category：反序列化为 null，不抛异常。 */
    @Test
    void deserialize_legacyJsonWithoutCurrencyFields_yieldsNullCurrenciesWithoutThrowing() {
        String legacyJson = """
                {
                  "platformSymbol": "BTCUSDT",
                  "maxLeverage": 100,
                  "takerFeeRate": 0.0005,
                  "spread": 0,
                  "minQty": 0.00000001,
                  "maxQty": 1000000000,
                  "minNotional": 0,
                  "pricePrecision": 2,
                  "qtyPrecision": 8
                }
                """;

        SymbolSpec spec = objectMapper.readValue(legacyJson, SymbolSpec.class);

        assertEquals("BTCUSDT", spec.platformSymbol());
        assertEquals(100, spec.maxLeverage());
        assertNull(spec.baseCurrency());
        assertNull(spec.quoteCurrency());
        assertNull(spec.category());
    }

    /** 新 JSON 含 baseCurrency / quoteCurrency / category：正常反序列化为真值。 */
    @Test
    void deserialize_newJsonWithCurrencyFields_yieldsCurrencyValues() {
        String newJson = """
                {
                  "platformSymbol": "BTCUSDT",
                  "maxLeverage": 100,
                  "takerFeeRate": 0.0005,
                  "spread": 0,
                  "minQty": 0.00000001,
                  "maxQty": 1000000000,
                  "minNotional": 0,
                  "pricePrecision": 2,
                  "qtyPrecision": 8,
                  "baseCurrency": "BTC",
                  "quoteCurrency": "USDT",
                  "category": 1
                }
                """;

        SymbolSpec spec = objectMapper.readValue(newJson, SymbolSpec.class);

        assertEquals("BTC", spec.baseCurrency());
        assertEquals("USDT", spec.quoteCurrency());
        assertEquals(1, spec.category());
    }

    /**
     * STAGE-14C2 Task 1：含 baseCurrency/quoteCurrency 但缺 category 的「过渡期」快照
     * （14B 已重发布、14C2 尚未重发布）：category 反序列化为 null，不抛异常。
     */
    @Test
    void deserialize_jsonWithCurrenciesButWithoutCategory_yieldsNullCategoryWithoutThrowing() {
        String json = """
                {
                  "platformSymbol": "BTCUSDT",
                  "maxLeverage": 100,
                  "takerFeeRate": 0.0005,
                  "spread": 0,
                  "minQty": 0.00000001,
                  "maxQty": 1000000000,
                  "minNotional": 0,
                  "pricePrecision": 2,
                  "qtyPrecision": 8,
                  "baseCurrency": "BTC",
                  "quoteCurrency": "USDT"
                }
                """;

        SymbolSpec spec = objectMapper.readValue(json, SymbolSpec.class);

        assertEquals("BTC", spec.baseCurrency());
        assertEquals("USDT", spec.quoteCurrency());
        assertNull(spec.category());
    }
}
