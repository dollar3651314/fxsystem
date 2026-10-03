package com.falconx.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * STAGE-14B Task 5 收口：toSpec 装配规则 + source 缺失防护单测。
 *
 * <p>toSpec 不依赖 Redis/Mapper/ObjectMapper，可直接传 null 构造仓库后测纯装配逻辑。
 */
class RedisMarketSymbolSpecRepositoryToSpecTests {

    private final RedisMarketSymbolSpecRepository repository =
            new RedisMarketSymbolSpecRepository(null, null, null);

    @Test
    void toSpecCopiesBaseAndQuoteCurrencyFromSource() {
        MarketSymbolQuoteMappingAdminRecord mapping = mapping("EURAUD", "LP1", "EUR/AUD");
        MarketSymbolAdminRecord source = source("EUR", "AUD");

        SymbolSpec spec = repository.toSpec(mapping, source);

        assertThat(spec.platformSymbol()).isEqualTo("EURAUD");
        assertThat(spec.baseCurrency()).isEqualTo("EUR");
        assertThat(spec.quoteCurrency()).isEqualTo("AUD");
        // STAGE-14C2 Task 1：category 从 source（t_symbol）填充（forex=2）
        assertThat(spec.category()).isEqualTo(2);
    }

    @Test
    void toSpecWithNullSourceDoesNotThrowAndLeavesCurrenciesNull() {
        MarketSymbolQuoteMappingAdminRecord mapping = mapping("EURAUD", "LP1", "EUR/AUD");

        assertThatCode(() -> repository.toSpec(mapping, null)).doesNotThrowAnyException();

        SymbolSpec spec = repository.toSpec(mapping, null);
        assertThat(spec.platformSymbol()).isEqualTo("EURAUD");
        assertThat(spec.baseCurrency()).isNull();
        assertThat(spec.quoteCurrency()).isNull();
        // STAGE-14C2 Task 1：source 缺失 → category 同样置 null（过渡期降级，trading 消费方处理）
        assertThat(spec.category()).isNull();
    }

    private static MarketSymbolQuoteMappingAdminRecord mapping(String platformSymbol, String sourceLpCode, String sourceSymbol) {
        return new MarketSymbolQuoteMappingAdminRecord(
                platformSymbol,
                "LP",
                sourceLpCode,
                sourceSymbol,
                3,
                "FX",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                1,
                100,
                new BigDecimal("0.0005"),
                BigDecimal.ZERO,
                new BigDecimal("0.01"),
                new BigDecimal("100"),
                new BigDecimal("10"),
                2,
                2,
                1,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static MarketSymbolAdminRecord source(String baseCurrency, String quoteCurrency) {
        return new MarketSymbolAdminRecord(
                1L,
                "LP1",
                "EUR/AUD",
                2,
                "FX",
                baseCurrency,
                quoteCurrency,
                2,
                2,
                1,
                LocalDateTime.now()
        );
    }
}
