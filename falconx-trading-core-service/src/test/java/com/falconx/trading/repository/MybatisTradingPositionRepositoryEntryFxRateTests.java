package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.repository.mapper.TradingPositionMapper;
import com.falconx.trading.repository.mapper.record.TradingPositionRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * STAGE-14B Task 9a：验证 t_position.entry_fx_rate 串入 record 与 INSERT，
 * 且 SELECT 读回经 toDomain 正确反序列化到实体。
 */
class MybatisTradingPositionRepositoryEntryFxRateTests {

    private static TradingPosition openPosition(BigDecimal entryFxRate) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TradingPosition(
                null,
                100L,
                7L,
                "EURAUD",
                TradingOrderSide.BUY,
                new BigDecimal("0.1"),
                new BigDecimal("1.65000000"),
                entryFxRate,
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("10"),
                new BigDecimal("63.00000000"),
                TradingMarginMode.ISOLATED,
                new BigDecimal("1.60000000"),
                null,
                null,
                null,
                null,
                null,
                TradingPositionStatus.OPEN,
                new BigDecimal("0.0005"),
                "default",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                now,
                null,
                now
        );
    }

    @Test
    void save_insertsEntryFxRateForNonUsdQuote() {
        TradingPositionMapper mapper = mock(TradingPositionMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        when(idGen.nextId()).thenReturn(9001L);
        MybatisTradingPositionRepository repo = new MybatisTradingPositionRepository(mapper, idGen);

        BigDecimal entryFxRate = new BigDecimal("0.63000000");
        TradingPosition saved = repo.save(openPosition(entryFxRate));

        assertEquals(0, saved.entryFxRate().compareTo(entryFxRate), "保存后实体保留 entryFxRate");

        ArgumentCaptor<TradingPositionRecord> captor = ArgumentCaptor.forClass(TradingPositionRecord.class);
        verify(mapper).insertTradingPosition(captor.capture());
        assertEquals(0, captor.getValue().entryFxRate().compareTo(entryFxRate), "INSERT record 携带 entry_fx_rate");
    }

    @Test
    void findByOpeningOrderId_deserializesEntryFxRate() {
        TradingPositionMapper mapper = mock(TradingPositionMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingPositionRepository repo = new MybatisTradingPositionRepository(mapper, idGen);

        BigDecimal entryFxRate = new BigDecimal("0.63000000");
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        TradingPositionRecord record = new TradingPositionRecord(
                9001L, 100L, 7L, "EURAUD", 1,
                new BigDecimal("0.1"), new BigDecimal("1.65000000"),
                entryFxRate,
                new BigDecimal("0.005000"), 1,
                new BigDecimal("10"), new BigDecimal("63.00000000"), 2,
                new BigDecimal("1.60000000"), null, null, null, null, null, 1,
                new BigDecimal("0.0005"), "default", BigDecimal.ZERO, BigDecimal.ZERO,
                now, null, now
        );
        when(mapper.selectByOpeningOrderId(100L)).thenReturn(record);

        TradingPosition position = repo.findByOpeningOrderId(100L).orElseThrow();
        assertEquals(0, position.entryFxRate().compareTo(entryFxRate), "SELECT 读回 entry_fx_rate 反序列化正确");
    }
}
