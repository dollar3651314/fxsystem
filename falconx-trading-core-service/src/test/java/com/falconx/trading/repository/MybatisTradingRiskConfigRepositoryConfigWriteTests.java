package com.falconx.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.service.model.MarginThresholds;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TradingRiskConfigRepository} 平台行（symbol IS NULL）写方法轻量集成测试（STAGE-14D3a Task 3）。
 *
 * <p>使用真实 falconx_trading_it 库（V15 已 INSERT IGNORE 平台行 id=0/symbol NULL，V31 加
 * stop_out_level/margin_call_level、V37 加 cooling_period_seconds，列均有 DEFAULT）。验证 admin 写方法
 * （冷静期秒数、StopOut/MarginCall 阈值）写平台行后回读反映新值。
 *
 * <p>写方法为绝对覆盖（非增量），用例先写再回读，与执行顺序无关；只动平台行，不影响 symbol 行 IT。
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
class MybatisTradingRiskConfigRepositoryConfigWriteTests {

    @Autowired
    private TradingRiskConfigRepository repository;

    /**
     * 每个测试方法结束后（含异常）将平台行还原为 Flyway 默认值：
     * stop_out_level=0.30（V31 DEFAULT）、margin_call_level=1.00（V31 DEFAULT）、
     * cooling_period_seconds=300（V37 DEFAULT）。
     * 防止写 IT 污染共享 falconx_trading_it 库，消除与 TradingLeverageTierStopOutIntegrationTests
     * it013 阈值断言的执行顺序耦合。
     */
    @AfterEach
    void restorePlatformDefaults() {
        repository.updatePlatformMarginThresholds(new BigDecimal("0.300000"), new BigDecimal("1.000000"));
        repository.updatePlatformCoolingPeriodSeconds(300);
    }

    @Test
    void updateCoolingPeriod_thenReadReflectsNewValue() {
        repository.updatePlatformCoolingPeriodSeconds(600);

        assertThat(repository.findPlatformCoolingPeriodSeconds()).contains(600);
    }

    @Test
    void updateMarginThresholds_thenReadReflectsNewValues() {
        repository.updatePlatformMarginThresholds(new BigDecimal("0.250000"), new BigDecimal("1.200000"));

        MarginThresholds t = repository.findPlatformMarginThresholds().orElseThrow();
        assertThat(t.stopOutLevel()).isEqualByComparingTo("0.25");
        assertThat(t.marginCallLevel()).isEqualByComparingTo("1.20");
    }
}
