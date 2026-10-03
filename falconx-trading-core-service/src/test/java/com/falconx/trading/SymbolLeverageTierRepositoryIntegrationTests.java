package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.repository.mapper.SymbolLeverageTierMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link SymbolLeverageTierRepository} 轻量集成测试。
 *
 * <p>使用真实 falconx_trading_it 库（含 V30 seed），验证 findTiers 按 symbol+group
 * 查档位、升序、回退 default，并核对 EURUSD / XAUUSD 的 seed 档位与 master §5.1 模板一致。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class SymbolLeverageTierRepositoryIntegrationTests {

    /** 独立测试 symbol，避免污染 V30 seed 的真实 symbol；@AfterEach 物理清理。 */
    private static final String TEST_SYMBOL = "ZZZTIERTEST";

    @Autowired
    private SymbolLeverageTierRepository symbolLeverageTierRepository;

    @Autowired
    private SymbolLeverageTierMapper symbolLeverageTierMapper;

    @Autowired
    private javax.sql.DataSource dataSource;

    /** 清理本测试 symbol 的全部档位（含软删行），避免跨用例 UNIQUE 冲突与 seed 污染。 */
    @AfterEach
    void cleanupTestSymbol() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM t_symbol_leverage_tier WHERE symbol = ?")) {
            ps.setString(1, TEST_SYMBOL);
            ps.executeUpdate();
        }
    }

    private static SymbolLeverageTier newTier(int tierNo, BigDecimal lower, BigDecimal upper,
                                              int maxLeverage, String mmRate) {
        return new SymbolLeverageTier(
                null, TEST_SYMBOL, "default", tierNo, lower, upper, maxLeverage, new BigDecimal(mmRate), true);
    }

    @Test
    void findTiers_EURUSD_default_返回5档且按notionalLower升序() {
        List<SymbolLeverageTier> tiers = symbolLeverageTierRepository.findTiers("EURUSD", "default");

        assertThat(tiers).hasSize(5);
        assertThat(tiers).extracting(SymbolLeverageTier::tierNo)
                .containsExactly(1, 2, 3, 4, 5);
        // 升序
        assertThat(tiers).extracting(SymbolLeverageTier::notionalLower)
                .containsExactly(
                        new BigDecimal("0.00000000"),
                        new BigDecimal("100000.00000000"),
                        new BigDecimal("500000.00000000"),
                        new BigDecimal("2000000.00000000"),
                        new BigDecimal("10000000.00000000"));
        // tier1：500x / 0.2%
        SymbolLeverageTier t1 = tiers.get(0);
        assertThat(t1.maxLeverage()).isEqualTo(500);
        assertThat(t1.mmRate()).isEqualByComparingTo("0.002000");
        // 最高档 tier5：upper 为 NULL（无上限）/ 20x / 5%
        SymbolLeverageTier t5 = tiers.get(4);
        assertThat(t5.notionalUpper()).isNull();
        assertThat(t5.maxLeverage()).isEqualTo(20);
        assertThat(t5.mmRate()).isEqualByComparingTo("0.050000");
    }

    @Test
    void findTiers_XAUUSD_default_tier3为50x且mmRate2pct() {
        List<SymbolLeverageTier> tiers = symbolLeverageTierRepository.findTiers("XAUUSD", "default");

        assertThat(tiers).hasSize(5);
        SymbolLeverageTier tier3 = tiers.get(2);
        assertThat(tier3.tierNo()).isEqualTo(3);
        assertThat(tier3.maxLeverage()).isEqualTo(50);
        assertThat(tier3.mmRate()).isEqualByComparingTo("0.020000");
    }

    @Test
    void findTiers_未配置group回退default组() {
        List<SymbolLeverageTier> tiers = symbolLeverageTierRepository.findTiers("EURUSD", "vip-not-configured");

        // vip 组未单独配置 → 回退 default 组 5 档
        assertThat(tiers).hasSize(5);
        assertThat(tiers).allMatch(t -> "default".equals(t.groupCode()));
    }

    @Test
    void findTiers_不存在symbol返回空list() {
        List<SymbolLeverageTier> tiers = symbolLeverageTierRepository.findTiers("NOSUCHSYMBOL", "default");

        assertThat(tiers).isEmpty();
    }

    @Test
    void save_insert一行后selectById命中且findById字段一致() {
        SymbolLeverageTier saved =
                symbolLeverageTierRepository.save(newTier(1, BigDecimal.ZERO, new BigDecimal("100000"), 100, "0.005000"));

        assertThat(saved.id()).isNotNull();
        Optional<SymbolLeverageTier> found = symbolLeverageTierRepository.findById(saved.id());
        assertThat(found).isPresent();
        assertThat(found.get().symbol()).isEqualTo(TEST_SYMBOL);
        assertThat(found.get().tierNo()).isEqualTo(1);
        assertThat(found.get().maxLeverage()).isEqualTo(100);
        assertThat(found.get().mmRate()).isEqualByComparingTo("0.005000");
        assertThat(found.get().enabled()).isTrue();
    }

    @Test
    void update_修改maxLeverage与mmRate后findById反映新值() {
        SymbolLeverageTier saved =
                symbolLeverageTierRepository.save(newTier(1, BigDecimal.ZERO, new BigDecimal("100000"), 100, "0.005000"));

        SymbolLeverageTier modified = new SymbolLeverageTier(
                saved.id(), TEST_SYMBOL, "default", 1,
                BigDecimal.ZERO, new BigDecimal("200000"), 50, new BigDecimal("0.010000"), true);
        int affected = symbolLeverageTierRepository.update(modified);

        assertThat(affected).isEqualTo(1);
        SymbolLeverageTier reloaded = symbolLeverageTierRepository.findById(saved.id()).orElseThrow();
        assertThat(reloaded.maxLeverage()).isEqualTo(50);
        assertThat(reloaded.mmRate()).isEqualByComparingTo("0.010000");
        assertThat(reloaded.notionalUpper()).isEqualByComparingTo("200000");
    }

    @Test
    void softDelete_置enabled为0后findById仍可查但findTiers不再返回() {
        SymbolLeverageTier saved =
                symbolLeverageTierRepository.save(newTier(1, BigDecimal.ZERO, null, 100, "0.005000"));
        assertThat(symbolLeverageTierRepository.findTiers(TEST_SYMBOL, "default")).hasSize(1);

        int affected = symbolLeverageTierRepository.softDelete(saved.id());

        assertThat(affected).isEqualTo(1);
        // findById（不过滤 enabled）仍可查到，且 enabled=false
        assertThat(symbolLeverageTierRepository.findById(saved.id())).isPresent();
        assertThat(symbolLeverageTierRepository.findById(saved.id()).orElseThrow().enabled()).isFalse();
        // findTiers 只查 enabled=1，软删后不再返回
        assertThat(symbolLeverageTierRepository.findTiers(TEST_SYMBOL, "default")).isEmpty();
    }

    @Test
    void selectPage_按symbol过滤且分页_countBy一致() {
        symbolLeverageTierRepository.save(newTier(1, BigDecimal.ZERO, new BigDecimal("100000"), 100, "0.005000"));
        symbolLeverageTierRepository.save(newTier(2, new BigDecimal("100000"), new BigDecimal("500000"), 50, "0.010000"));
        symbolLeverageTierRepository.save(newTier(3, new BigDecimal("500000"), null, 20, "0.020000"));

        long total = symbolLeverageTierRepository.count(TEST_SYMBOL, null);
        assertThat(total).isEqualTo(3L);

        List<SymbolLeverageTier> firstPage = symbolLeverageTierRepository.page(TEST_SYMBOL, null, 0, 2);
        assertThat(firstPage).hasSize(2);
        List<SymbolLeverageTier> secondPage = symbolLeverageTierRepository.page(TEST_SYMBOL, null, 2, 2);
        assertThat(secondPage).hasSize(1);
        // 全部命中本测试 symbol（不泄漏 seed 真实 symbol）
        assertThat(firstPage).allMatch(t -> TEST_SYMBOL.equals(t.symbol()));

        // group 过滤
        assertThat(symbolLeverageTierRepository.count(TEST_SYMBOL, "default")).isEqualTo(3L);
        assertThat(symbolLeverageTierRepository.count(TEST_SYMBOL, "nogroup")).isZero();
    }

    @Test
    void insert_违反CHECK约束_maxLev乘mmRate大于1_被DB拒绝() {
        // 100 × 0.02 = 2.0 > 1.0，触发 chk_tier_leverage_mm（MySQL CHECK 违反落在 SQLState HY000，
        // Spring 译为 UncategorizedSQLException，均属 DataAccessException；断言到约束名以锁定语义）。
        assertThatThrownBy(() ->
                symbolLeverageTierRepository.save(newTier(1, BigDecimal.ZERO, null, 100, "0.020000")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_tier_leverage_mm");
    }
}
