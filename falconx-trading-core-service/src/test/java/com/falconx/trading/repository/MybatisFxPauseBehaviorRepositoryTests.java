package com.falconx.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.mapper.FxPauseBehaviorMapper;
import com.falconx.trading.repository.mapper.record.FxPauseBehaviorRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MybatisFxPauseBehaviorRepository} 单元测试（mock {@link FxPauseBehaviorMapper}）。
 *
 * <p>覆盖：findByCategory 命中/缺失、findAll、全量快照缓存命中不重复查 DB（TTL 内 verify times(1)）、
 * TTL 过期整表重查、TINYINT(1/0) → boolean 转换。
 */
@ExtendWith(MockitoExtension.class)
class MybatisFxPauseBehaviorRepositoryTests {

    @Mock
    private FxPauseBehaviorMapper mapper;

    /** V31 seed 子集：crypto(1) 全允许、forex(2) 停开仓/停被动强平、metal(3) 同 forex。 */
    private static List<FxPauseBehaviorRecord> seedRecords() {
        return List.of(
                new FxPauseBehaviorRecord(1, "crypto", 1, 1, 1),
                new FxPauseBehaviorRecord(2, "forex", 0, 1, 0),
                new FxPauseBehaviorRecord(3, "metal", 0, 1, 0));
    }

    private MybatisFxPauseBehaviorRepository newRepository(Duration ttl, AtomicReference<Instant> clock) {
        return new MybatisFxPauseBehaviorRepository(mapper, ttl, clock::get);
    }

    @Test
    void findByCategory_forex命中_allowOpen与allowLiquidation为false() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var repo = newRepository(Duration.ofSeconds(60), new AtomicReference<>(Instant.EPOCH));

        Optional<FxPauseBehavior> forex = repo.findByCategory(2);

        assertThat(forex).isPresent();
        assertThat(forex.get().category()).isEqualTo(2);
        assertThat(forex.get().categoryName()).isEqualTo("forex");
        assertThat(forex.get().allowOpen()).isFalse();
        assertThat(forex.get().allowClose()).isTrue();
        assertThat(forex.get().allowLiquidation()).isFalse();
    }

    @Test
    void findByCategory_crypto命中_全部允许_TINYINT1转true() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var repo = newRepository(Duration.ofSeconds(60), new AtomicReference<>(Instant.EPOCH));

        FxPauseBehavior crypto = repo.findByCategory(1).orElseThrow();

        assertThat(crypto.allowOpen()).isTrue();
        assertThat(crypto.allowClose()).isTrue();
        assertThat(crypto.allowLiquidation()).isTrue();
    }

    @Test
    void findByCategory_不存在category返回empty() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var repo = newRepository(Duration.ofSeconds(60), new AtomicReference<>(Instant.EPOCH));

        assertThat(repo.findByCategory(99)).isEmpty();
    }

    @Test
    void findAll_返回全部且按category升序() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var repo = newRepository(Duration.ofSeconds(60), new AtomicReference<>(Instant.EPOCH));

        List<FxPauseBehavior> all = repo.findAll();

        assertThat(all).hasSize(3);
        assertThat(all).extracting(FxPauseBehavior::category).containsExactly(1, 2, 3);
    }

    @Test
    void 缓存命中_TTL内多次查询只回源一次() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var repo = newRepository(Duration.ofSeconds(60), clock);

        // 同一 TTL 窗口内多次混合查询，仅触发一次 selectAll
        repo.findByCategory(1);
        repo.findByCategory(2);
        repo.findAll();
        repo.findByCategory(99);

        verify(mapper, times(1)).selectAll();
    }

    @Test
    void 缓存过期_TTL外重查回源第二次() {
        when(mapper.selectAll()).thenReturn(seedRecords());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var repo = newRepository(Duration.ofSeconds(60), clock);

        repo.findByCategory(1);
        // 推进时钟超过 TTL（60s），触发整表重查
        clock.set(Instant.EPOCH.plusSeconds(61));
        repo.findByCategory(1);

        verify(mapper, times(2)).selectAll();
    }

    @Test
    void toDomain_allowLiquidation为0时转false_非1一律false() {
        when(mapper.selectAll()).thenReturn(List.of(
                new FxPauseBehaviorRecord(2, "forex", 0, 1, 0)));
        var repo = newRepository(Duration.ofSeconds(60), new AtomicReference<>(Instant.EPOCH));

        FxPauseBehavior forex = repo.findByCategory(2).orElseThrow();

        assertThat(forex.allowOpen()).isFalse();
        assertThat(forex.allowLiquidation()).isFalse();
        assertThat(forex.allowClose()).isTrue();
    }
}
