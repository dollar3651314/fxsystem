package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.mapper.TradingTradeMapper;
import com.falconx.trading.repository.mapper.record.TradingTradeRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sprint 3 S6 Task 1：验证 saveWithExplicitId 用调用方提供的 id 写入，不重新生成。
 */
class MybatisTradingTradeRepositorySaveWithExplicitIdTests {

    @Test
    void saveWithExplicitId_insertsWithProvidedId() {
        TradingTradeMapper mapper = mock(TradingTradeMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingTradeRepository repo = new MybatisTradingTradeRepository(mapper, idGen);
        OffsetDateTime now = OffsetDateTime.now();
        TradingTrade trade = new TradingTrade(
                5001L,
                100L,
                200L,
                7L,
                "EURUSD",
                TradingOrderSide.BUY,
                TradingTradeType.CLOSE,
                new BigDecimal("0.01"),
                new BigDecimal("1.10000000"),
                BigDecimal.ZERO.setScale(8),
                new BigDecimal("0.50000000"),
                now
        );

        TradingTrade result = repo.saveWithExplicitId(trade);

        assertSame(trade, result);
        assertEquals(5001L, result.tradeId());

        ArgumentCaptor<TradingTradeRecord> captor = ArgumentCaptor.forClass(TradingTradeRecord.class);
        verify(mapper).insertTradingTrade(captor.capture());
        assertEquals(5001L, captor.getValue().id());
    }

    @Test
    void saveWithExplicitId_rejectsNullId() {
        TradingTradeMapper mapper = mock(TradingTradeMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingTradeRepository repo = new MybatisTradingTradeRepository(mapper, idGen);

        TradingTrade tradeWithNullId = new TradingTrade(
                null,
                100L, 200L, 7L, "EURUSD",
                TradingOrderSide.BUY, TradingTradeType.CLOSE,
                new BigDecimal("0.01"), new BigDecimal("1.10000000"),
                BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                OffsetDateTime.now()
        );

        assertThrows(IllegalArgumentException.class, () -> repo.saveWithExplicitId(tradeWithNullId));
        verify(mapper, never()).insertTradingTrade(any());
    }
}
