package com.falconx.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.TradingCoreServiceApplication;
import com.falconx.trading.entity.FxPauseBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link FxPauseBehaviorRepository} admin 写路径 + 写后失效快照集成测试（STAGE-14D3a Task 6）。
 *
 * <p>用独立隔离库 {@code falconx_trading_fxpause_write_it}（createDatabaseIfNotExist + Flyway 全量迁移，
 * 含 V31 seed 8 行），避免污染共享 {@code falconx_trading_it} 的真实 seed（D2 误删 seed 教训）。
 * 写方法为绝对覆盖；{@code @AfterEach} 把被改 category 复位回 V31 默认值，保证用例间互不影响。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_fxpause_write_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class MybatisFxPauseBehaviorRepositoryWriteTests {

    @Autowired
    private FxPauseBehaviorRepository repository;

    /** 把可能被各用例改动的 category 复位回 V31 默认值（crypto 全允许、forex 仅平仓）。 */
    @AfterEach
    void restoreSeed() {
        repository.updateByCategory(1, true, true, true, null);   // crypto 默认全允许
        repository.updateByCategory(2, false, true, false, null); // forex 默认仅允许平仓
    }

    @Test
    void updateByCategory_thenFindByCategoryReflectsImmediately() {
        // crypto(category=1) 默认 allow_open=true → 改为 false（allow_close 恒 true）
        repository.updateByCategory(1, false, true, false, 999L);

        FxPauseBehavior b = repository.findByCategory(1).orElseThrow();
        assertThat(b.allowOpen()).isFalse();
        assertThat(b.allowClose()).isTrue();
        assertThat(b.allowLiquidation()).isFalse();
    }

    @Test
    void findAll_returns8SeededCategories() {
        assertThat(repository.findAll()).hasSize(8);
    }

    @Test
    void updateByCategory_invalidatesWarmCache_soNextReadSeesNewValue() {
        // 先读触发缓存预热（crypto 默认 allowOpen=true 进入快照）
        assertThat(repository.findByCategory(1).orElseThrow().allowOpen()).isTrue();

        // 写改 crypto allowOpen=false；写后失效快照
        repository.updateByCategory(1, false, true, true, 999L);

        // 立即回读应见新值（证明写后快照失效即时生效，未等 TTL）
        assertThat(repository.findByCategory(1).orElseThrow().allowOpen()).isFalse();
    }
}
