package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link FxPauseBehaviorRepository} 轻量集成测试。
 *
 * <p>使用真实 falconx_trading_it 库（含 V31 seed 8 行），验证：forex(2)/metal(3) 默认停开仓与
 * 停被动强平、crypto(1) 全允许、不存在 category 返回 empty、findAll 返回 8 行。
 * 只读 seed，不写库（无需清理）。
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
class FxPauseBehaviorRepositoryIntegrationTests {

    @Autowired
    private FxPauseBehaviorRepository fxPauseBehaviorRepository;

    @Test
    void findByCategory_forex_停开仓且停被动强平() {
        Optional<FxPauseBehavior> forex = fxPauseBehaviorRepository.findByCategory(2);

        assertThat(forex).isPresent();
        assertThat(forex.get().categoryName()).isEqualTo("forex");
        assertThat(forex.get().allowOpen()).isFalse();
        assertThat(forex.get().allowClose()).isTrue();
        assertThat(forex.get().allowLiquidation()).isFalse();
    }

    @Test
    void findByCategory_metal_停开仓且停被动强平() {
        Optional<FxPauseBehavior> metal = fxPauseBehaviorRepository.findByCategory(3);

        assertThat(metal).isPresent();
        assertThat(metal.get().categoryName()).isEqualTo("metal");
        assertThat(metal.get().allowOpen()).isFalse();
        assertThat(metal.get().allowLiquidation()).isFalse();
    }

    @Test
    void findByCategory_crypto_全部允许() {
        Optional<FxPauseBehavior> crypto = fxPauseBehaviorRepository.findByCategory(1);

        assertThat(crypto).isPresent();
        assertThat(crypto.get().categoryName()).isEqualTo("crypto");
        assertThat(crypto.get().allowOpen()).isTrue();
        assertThat(crypto.get().allowClose()).isTrue();
        assertThat(crypto.get().allowLiquidation()).isTrue();
    }

    @Test
    void findByCategory_不存在category返回empty() {
        assertThat(fxPauseBehaviorRepository.findByCategory(99)).isEmpty();
    }

    @Test
    void findAll_返回8行且按category升序() {
        List<FxPauseBehavior> all = fxPauseBehaviorRepository.findAll();

        assertThat(all).hasSize(8);
        assertThat(all).extracting(FxPauseBehavior::category)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        // forex(2)/metal(3) 停开仓，其余允许
        assertThat(all.get(1).allowOpen()).isFalse();
        assertThat(all.get(2).allowOpen()).isFalse();
        assertThat(all.get(0).allowOpen()).isTrue();
        assertThat(all.get(3).allowOpen()).isTrue();
    }
}
