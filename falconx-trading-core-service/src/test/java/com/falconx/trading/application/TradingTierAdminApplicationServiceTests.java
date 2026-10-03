package com.falconx.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.falconx.trading.api.AdminTierListResponse;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.service.LeverageTierResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * STAGE-14C2 Task 4：{@link TradingTierAdminApplicationService} 单元测试（mock repository + resolver）。
 *
 * <p>覆盖：新建校验通过并失效缓存 / 区间重叠拒 90932 / tierNo 重复拒 90932 /
 * CHECK（max_lev×mm_rate>1）拒 90931 / 区间非法拒 90931 / 编辑 not found 拒 90930 /
 * 软删 not found 拒 90930 / 编辑排除自身区间不误判重叠 / 删成功调 invalidate。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingTierAdminApplicationServiceTests {

    private static final String SYMBOL = "EURUSD";
    private static final String GROUP = "default";

    @Mock
    private SymbolLeverageTierRepository tierRepository;

    @Mock
    private LeverageTierResolver leverageTierResolver;

    private TradingTierAdminApplicationService service() {
        return new TradingTierAdminApplicationService(tierRepository, leverageTierResolver);
    }

    private static SymbolLeverageTier tier(Long id, int tierNo, String lower, String upper,
                                           int maxLev, String mm) {
        return new SymbolLeverageTier(id, SYMBOL, GROUP, tierNo,
                new BigDecimal(lower), upper == null ? null : new BigDecimal(upper),
                maxLev, new BigDecimal(mm), true);
    }

    @Test
    void createTier_校验通过_持久化并失效缓存() {
        Mockito.when(tierRepository.findTiers(SYMBOL, GROUP)).thenReturn(List.of());
        Mockito.when(tierRepository.save(Mockito.any())).thenAnswer(inv -> {
            SymbolLeverageTier in = inv.getArgument(0);
            return new SymbolLeverageTier(99L, in.symbol(), in.groupCode(), in.tierNo(),
                    in.notionalLower(), in.notionalUpper(), in.maxLeverage(), in.mmRate(), true);
        });

        AdminTierListResponse.Item item = service().createTier(
                SYMBOL, GROUP, 1, BigDecimal.ZERO, new BigDecimal("100000"), 100, new BigDecimal("0.005000"));

        assertThat(item.id()).isEqualTo(99L);
        assertThat(item.tierNo()).isEqualTo(1);
        Mockito.verify(tierRepository).save(Mockito.any());
        Mockito.verify(leverageTierResolver).invalidate(SYMBOL, GROUP);
    }

    @Test
    void createTier_区间与现有档重叠_拒90932() {
        // 现有 [0,100000)，新建 [50000,200000) 相交。
        Mockito.when(tierRepository.findTiers(SYMBOL, GROUP))
                .thenReturn(List.of(tier(1L, 1, "0", "100000", 100, "0.005000")));

        assertThatThrownBy(() -> service().createTier(
                SYMBOL, GROUP, 2, new BigDecimal("50000"), new BigDecimal("200000"), 50, new BigDecimal("0.010000")))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_OVERLAP);
        Mockito.verify(tierRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(leverageTierResolver, Mockito.never()).invalidate(Mockito.any(), Mockito.any());
    }

    @Test
    void createTier_相邻不重叠_放行() {
        // 现有 [0,100000)，新建 [100000,500000) 边界相接不相交。
        Mockito.when(tierRepository.findTiers(SYMBOL, GROUP))
                .thenReturn(List.of(tier(1L, 1, "0", "100000", 100, "0.005000")));
        Mockito.when(tierRepository.save(Mockito.any())).thenAnswer(inv -> {
            SymbolLeverageTier in = inv.getArgument(0);
            return new SymbolLeverageTier(2L, in.symbol(), in.groupCode(), in.tierNo(),
                    in.notionalLower(), in.notionalUpper(), in.maxLeverage(), in.mmRate(), true);
        });

        AdminTierListResponse.Item item = service().createTier(
                SYMBOL, GROUP, 2, new BigDecimal("100000"), new BigDecimal("500000"), 50, new BigDecimal("0.010000"));

        assertThat(item.id()).isEqualTo(2L);
        Mockito.verify(leverageTierResolver).invalidate(SYMBOL, GROUP);
    }

    @Test
    void createTier_tierNo重复_拒90932() {
        Mockito.when(tierRepository.findTiers(SYMBOL, GROUP))
                .thenReturn(List.of(tier(1L, 1, "0", "100000", 100, "0.005000")));

        // tierNo=1 与现有重复，区间 [200000, null) 不重叠但 tierNo 冲突。
        assertThatThrownBy(() -> service().createTier(
                SYMBOL, GROUP, 1, new BigDecimal("200000"), null, 20, new BigDecimal("0.020000")))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_OVERLAP);
    }

    @Test
    void createTier_CHECK违反_maxLevMm乘积大于1_拒90931() {
        // 100 × 0.02 = 2.0 > 1.0
        assertThatThrownBy(() -> service().createTier(
                SYMBOL, GROUP, 1, BigDecimal.ZERO, null, 100, new BigDecimal("0.020000")))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED);
        Mockito.verify(tierRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void createTier_区间非法_lower不小于upper_拒90931() {
        assertThatThrownBy(() -> service().createTier(
                SYMBOL, GROUP, 1, new BigDecimal("100000"), new BigDecimal("100000"), 50, new BigDecimal("0.010000")))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED);
    }

    @Test
    void updateTier_id不存在_拒90930() {
        Mockito.when(tierRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateTier(
                404L, 1, BigDecimal.ZERO, new BigDecimal("100000"), 50, new BigDecimal("0.010000")))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_NOT_FOUND);
        Mockito.verify(leverageTierResolver, Mockito.never()).invalidate(Mockito.any(), Mockito.any());
    }

    @Test
    void updateTier_编辑自身区间_排除自身不误判重叠_并失效缓存() {
        SymbolLeverageTier self = tier(1L, 1, "0", "100000", 100, "0.005000");
        Mockito.when(tierRepository.findById(1L)).thenReturn(Optional.of(self));
        // 现有列表含自身；编辑把自身上界调到 120000，应排除自身不报重叠。
        Mockito.when(tierRepository.findTiers(SYMBOL, GROUP)).thenReturn(List.of(self));
        Mockito.when(tierRepository.update(Mockito.any())).thenReturn(1);

        AdminTierListResponse.Item item = service().updateTier(
                1L, 1, BigDecimal.ZERO, new BigDecimal("120000"), 100, new BigDecimal("0.005000"));

        assertThat(item.notionalUpper()).isEqualByComparingTo("120000");
        Mockito.verify(tierRepository).update(Mockito.any());
        Mockito.verify(leverageTierResolver).invalidate(SYMBOL, GROUP);
    }

    @Test
    void deleteTier_存在_软删并失效缓存() {
        Mockito.when(tierRepository.findById(1L))
                .thenReturn(Optional.of(tier(1L, 1, "0", "100000", 100, "0.005000")));
        Mockito.when(tierRepository.softDelete(1L)).thenReturn(1);

        service().deleteTier(1L);

        Mockito.verify(tierRepository).softDelete(1L);
        ArgumentCaptor<String> sym = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> grp = ArgumentCaptor.forClass(String.class);
        Mockito.verify(leverageTierResolver).invalidate(sym.capture(), grp.capture());
        assertThat(sym.getValue()).isEqualTo(SYMBOL);
        assertThat(grp.getValue()).isEqualTo(GROUP);
    }

    @Test
    void deleteTier_id不存在_拒90930() {
        Mockito.when(tierRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteTier(404L))
                .isInstanceOf(TradingBusinessException.class)
                .extracting(e -> ((TradingBusinessException) e).getErrorCode())
                .isEqualTo(TradingErrorCode.ADMIN_TIER_NOT_FOUND);
        Mockito.verify(tierRepository, Mockito.never()).softDelete(Mockito.anyLong());
    }
}
