package com.falconx.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.mapper.TradingAccountMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MybatisTradingAccountRepository#switchMarginMode} 单元测试（mock mapper）。
 *
 * <p>STAGE-14D1 Task 1：验证 switchMarginMode 把领域枚举编码为 margin_mode 码，
 * 并把 modeChangedAt/modeCoolingUntil 以本地时间透传给 mapper.updateMarginMode，返回影响行数。
 */
@ExtendWith(MockitoExtension.class)
class MybatisTradingAccountRepositorySwitchMarginModeTests {

    @Mock
    private TradingAccountMapper tradingAccountMapper;

    @Mock
    private IdGenerator idGenerator;

    @InjectMocks
    private MybatisTradingAccountRepository repository;

    @Test
    void switchMarginMode_编码枚举并透传时间参数返回影响行数() {
        OffsetDateTime changedAt = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime coolingUntil = changedAt.plusMinutes(5);
        when(tradingAccountMapper.updateMarginMode(
                org.mockito.ArgumentMatchers.eq(900L),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        int affected = repository.switchMarginMode(900L, TradingMarginMode.CROSS, changedAt, coolingUntil);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<Long> accountIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<java.time.LocalDateTime> changedAtCaptor =
                ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        ArgumentCaptor<java.time.LocalDateTime> coolingCaptor =
                ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(tradingAccountMapper).updateMarginMode(
                accountIdCaptor.capture(),
                codeCaptor.capture(),
                changedAtCaptor.capture(),
                coolingCaptor.capture());
        assertThat(accountIdCaptor.getValue()).isEqualTo(900L);
        // CROSS=1（TradingMybatisSupport.toMarginModeCode）
        assertThat(codeCaptor.getValue()).isEqualTo(1);
        assertThat(changedAtCaptor.getValue()).isEqualTo(changedAt.toLocalDateTime());
        assertThat(coolingCaptor.getValue()).isEqualTo(coolingUntil.toLocalDateTime());
    }

    @Test
    void switchMarginMode_切ISOLATED编码为2且允许冷静期为null() {
        OffsetDateTime changedAt = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        when(tradingAccountMapper.updateMarginMode(
                org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(1);

        int affected = repository.switchMarginMode(901L, TradingMarginMode.ISOLATED, changedAt, null);

        assertThat(affected).isEqualTo(1);
        verify(tradingAccountMapper).updateMarginMode(
                org.mockito.ArgumentMatchers.eq(901L),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(changedAt.toLocalDateTime()),
                org.mockito.ArgumentMatchers.isNull());
    }
}
