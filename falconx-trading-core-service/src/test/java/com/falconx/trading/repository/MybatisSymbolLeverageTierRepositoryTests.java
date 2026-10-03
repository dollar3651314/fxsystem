package com.falconx.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.mapper.SymbolLeverageTierMapper;
import com.falconx.trading.repository.mapper.record.SymbolLeverageTierRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MybatisSymbolLeverageTierRepository} 单元测试（mock mapper）。
 *
 * <p>覆盖：命中指定 group、group 空回退 default、default 也空返回空、按 notional_lower 升序透传；
 * 以及 C2 写扩展 save/update/softDelete/page/count/findById 委托正确。
 */
@ExtendWith(MockitoExtension.class)
class MybatisSymbolLeverageTierRepositoryTests {

    @Mock
    private SymbolLeverageTierMapper mapper;

    @Mock
    private IdGenerator idGenerator;

    @InjectMocks
    private MybatisSymbolLeverageTierRepository repository;

    private static SymbolLeverageTierRecord record(int tierNo, BigDecimal lower, BigDecimal upper, int maxLev) {
        return new SymbolLeverageTierRecord(
                (long) tierNo,
                "EURUSD",
                "vip",
                tierNo,
                lower,
                upper,
                maxLev,
                new BigDecimal("0.002000"),
                1);
    }

    @Test
    void findTiers_命中指定group时直接返回不回退() {
        List<SymbolLeverageTierRecord> rows = List.of(
                record(1, BigDecimal.ZERO, new BigDecimal("100000"), 500),
                record(2, new BigDecimal("100000"), null, 200));
        when(mapper.selectBySymbolAndGroup("EURUSD", "vip")).thenReturn(rows);

        List<SymbolLeverageTier> tiers = repository.findTiers("EURUSD", "vip");

        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).tierNo()).isEqualTo(1);
        assertThat(tiers.get(0).groupCode()).isEqualTo("vip");
        verify(mapper, never()).selectBySymbolAndGroup("EURUSD", "default");
    }

    @Test
    void findTiers_指定group空时回退default组() {
        when(mapper.selectBySymbolAndGroup("EURUSD", "vip")).thenReturn(List.of());
        when(mapper.selectBySymbolAndGroup("EURUSD", "default")).thenReturn(List.of(
                record(1, BigDecimal.ZERO, new BigDecimal("100000"), 500)));

        List<SymbolLeverageTier> tiers = repository.findTiers("EURUSD", "vip");

        assertThat(tiers).hasSize(1);
        verify(mapper).selectBySymbolAndGroup("EURUSD", "default");
    }

    @Test
    void findTiers_default组也空时返回空list() {
        when(mapper.selectBySymbolAndGroup("EURUSD", "vip")).thenReturn(List.of());
        when(mapper.selectBySymbolAndGroup("EURUSD", "default")).thenReturn(List.of());

        List<SymbolLeverageTier> tiers = repository.findTiers("EURUSD", "vip");

        assertThat(tiers).isEmpty();
    }

    @Test
    void findTiers_groupCode本就是default空时不重复查询且返回空() {
        when(mapper.selectBySymbolAndGroup("EURUSD", "default")).thenReturn(List.of());

        List<SymbolLeverageTier> tiers = repository.findTiers("EURUSD", "default");

        assertThat(tiers).isEmpty();
        // groupCode 已是 default，无须再回退查询一次
        verify(mapper).selectBySymbolAndGroup("EURUSD", "default");
    }

    @Test
    void findTiers_保留mapper返回的notionalLower升序() {
        List<SymbolLeverageTierRecord> rows = List.of(
                record(1, BigDecimal.ZERO, new BigDecimal("100000"), 500),
                record(2, new BigDecimal("100000"), new BigDecimal("500000"), 200),
                record(3, new BigDecimal("500000"), null, 100));
        when(mapper.selectBySymbolAndGroup("EURUSD", "default")).thenReturn(rows);

        List<SymbolLeverageTier> tiers = repository.findTiers("EURUSD", "default");

        assertThat(tiers).extracting(SymbolLeverageTier::notionalLower)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("100000"), new BigDecimal("500000"));
    }

    private static SymbolLeverageTier domain(Long id, int tierNo, int maxLev) {
        return new SymbolLeverageTier(
                id,
                "ZZZTIERTEST",
                "default",
                tierNo,
                BigDecimal.ZERO,
                new BigDecimal("100000"),
                maxLev,
                new BigDecimal("0.002000"),
                true);
    }

    @Test
    void save_id为空时雪花生成并透传insert返回带id领域对象() {
        when(idGenerator.nextId()).thenReturn(99001L);

        SymbolLeverageTier saved = repository.save(domain(null, 1, 500));

        assertThat(saved.id()).isEqualTo(99001L);
        ArgumentCaptor<SymbolLeverageTierRecord> captor = ArgumentCaptor.forClass(SymbolLeverageTierRecord.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(99001L);
        assertThat(captor.getValue().symbol()).isEqualTo("ZZZTIERTEST");
        assertThat(captor.getValue().enabled()).isEqualTo(1);
    }

    @Test
    void save_id非空时沿用调用方id不再雪花生成() {
        SymbolLeverageTier saved = repository.save(domain(88001L, 1, 500));

        assertThat(saved.id()).isEqualTo(88001L);
        verify(idGenerator, never()).nextId();
        ArgumentCaptor<SymbolLeverageTierRecord> captor = ArgumentCaptor.forClass(SymbolLeverageTierRecord.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(88001L);
    }

    @Test
    void update_透传updateById返回影响行数() {
        when(mapper.updateById(any(SymbolLeverageTierRecord.class))).thenReturn(1);

        int affected = repository.update(domain(88001L, 1, 300));

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<SymbolLeverageTierRecord> captor = ArgumentCaptor.forClass(SymbolLeverageTierRecord.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(88001L);
        assertThat(captor.getValue().maxLeverage()).isEqualTo(300);
    }

    @Test
    void softDelete_委托softDeleteById返回影响行数() {
        when(mapper.softDeleteById(eq(88001L), any())).thenReturn(1);

        int affected = repository.softDelete(88001L);

        assertThat(affected).isEqualTo(1);
        verify(mapper).softDeleteById(eq(88001L), any());
    }

    @Test
    void findById_命中时映射为领域对象() {
        when(mapper.selectById(88001L)).thenReturn(record(2, BigDecimal.ZERO, new BigDecimal("100000"), 200));

        Optional<SymbolLeverageTier> found = repository.findById(88001L);

        assertThat(found).isPresent();
        assertThat(found.get().tierNo()).isEqualTo(2);
        assertThat(found.get().maxLeverage()).isEqualTo(200);
    }

    @Test
    void findById_未命中时返回empty() {
        when(mapper.selectById(404L)).thenReturn(null);

        assertThat(repository.findById(404L)).isEmpty();
    }

    @Test
    void page_透传selectPage并映射结果() {
        when(mapper.selectPage("ZZZTIERTEST", "default", 0, 20)).thenReturn(List.of(
                record(1, BigDecimal.ZERO, new BigDecimal("100000"), 500)));

        List<SymbolLeverageTier> page = repository.page("ZZZTIERTEST", "default", 0, 20);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).symbol()).isEqualTo("EURUSD");
        verify(mapper).selectPage("ZZZTIERTEST", "default", 0, 20);
    }

    @Test
    void count_透传countBy() {
        when(mapper.countBy("ZZZTIERTEST", null)).thenReturn(3L);

        assertThat(repository.count("ZZZTIERTEST", null)).isEqualTo(3L);
        verify(mapper).countBy("ZZZTIERTEST", null);
    }
}
