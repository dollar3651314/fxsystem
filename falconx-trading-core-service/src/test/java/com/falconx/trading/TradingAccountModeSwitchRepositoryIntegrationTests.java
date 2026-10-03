package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TradingAccountRepository#switchMarginMode} 轻量集成测试。
 *
 * <p>STAGE-14D1 Task 1：使用真实 falconx_trading_it 库（含 V33），验证
 * ISOLATED→CROSS 切换后 margin_mode/mode_changed_at/mode_cooling_until 落库，并能读回。
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
class TradingAccountModeSwitchRepositoryIntegrationTests {

    /** 独立测试 userId，避免污染真实账户；@AfterEach 物理清理。 */
    private static final long TEST_USER_ID = 990001001L;
    private static final String CURRENCY = "USDT";

    @Autowired
    private TradingAccountRepository tradingAccountRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM t_account WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER_ID);
            ps.executeUpdate();
        }
    }

    @Test
    void switchMarginMode_ISOLATED切CROSS后落库且读回marginMode与冷静期() {
        // 插入默认 ISOLATED 账户（mode 列默认 null）
        TradingAccount created = tradingAccountRepository.save(new TradingAccount(
                null,
                TEST_USER_ID,
                CURRENCY,
                new BigDecimal("1000.00000000"),
                BigDecimal.ZERO.setScale(8),
                BigDecimal.ZERO.setScale(8),
                TradingMarginMode.ISOLATED,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()));

        TradingAccount before = tradingAccountRepository.findByUserIdAndCurrency(TEST_USER_ID, CURRENCY).orElseThrow();
        assertThat(before.marginMode()).isEqualTo(TradingMarginMode.ISOLATED);
        assertThat(before.modeChangedAt()).isNull();
        assertThat(before.modeCoolingUntil()).isNull();

        OffsetDateTime changedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime coolingUntil = changedAt.plusMinutes(5);

        int affected = tradingAccountRepository.switchMarginMode(
                created.accountId(), TradingMarginMode.CROSS, changedAt, coolingUntil);
        assertThat(affected).isEqualTo(1);

        Optional<TradingAccount> reloaded = tradingAccountRepository.findByUserIdAndCurrency(TEST_USER_ID, CURRENCY);
        assertThat(reloaded).isPresent();
        TradingAccount after = reloaded.get();
        assertThat(after.marginMode()).isEqualTo(TradingMarginMode.CROSS);
        assertThat(after.modeChangedAt()).isNotNull();
        assertThat(after.modeChangedAt().toInstant()).isEqualTo(changedAt.toInstant());
        assertThat(after.modeCoolingUntil()).isNotNull();
        assertThat(after.modeCoolingUntil().toInstant()).isEqualTo(coolingUntil.toInstant());
        // balance/frozen/marginUsed 不受切换影响
        assertThat(after.balance()).isEqualByComparingTo("1000");
        assertThat(after.frozen()).isEqualByComparingTo("0");
        assertThat(after.marginUsed()).isEqualByComparingTo("0");
    }
}
