package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.repository.mapper.TradingLedgerMapper;
import com.falconx.trading.repository.mapper.record.TradingLedgerRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sprint 3 C1 Task 3：验证 {@link MybatisTradingLedgerRepository#batchInsert(List)} 行为。
 */
class MybatisTradingLedgerRepositoryBatchInsertTests {

    @Test
    void batchInsert_emptyList_returnsEmptyAndNoMapperCall() {
        TradingLedgerMapper mapper = mock(TradingLedgerMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingLedgerRepository repo = new MybatisTradingLedgerRepository(mapper, idGen);

        List<TradingLedgerEntry> result = repo.batchInsert(List.of());

        assertTrue(result.isEmpty());
        verify(mapper, never()).batchInsert(anyList());
    }

    @Test
    void batchInsert_threeEntriesWithoutId_fillsIdsAndCallsMapperOnce() {
        TradingLedgerMapper mapper = mock(TradingLedgerMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        when(idGen.nextId()).thenReturn(1001L, 1002L, 1003L);
        when(mapper.batchInsert(anyList())).thenReturn(3);
        MybatisTradingLedgerRepository repo = new MybatisTradingLedgerRepository(mapper, idGen);

        OffsetDateTime now = OffsetDateTime.now();
        List<TradingLedgerEntry> input = List.of(
                buildEntry(null, TradingLedgerBizType.ORDER_MARGIN_RESERVED, new BigDecimal("100.00000000"), "key:reserve", now),
                buildEntry(null, TradingLedgerBizType.ORDER_FEE_CHARGED, new BigDecimal("1.00000000"), "key:fee", now),
                buildEntry(null, TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, new BigDecimal("100.00000000"), "key:confirm", now)
        );

        List<TradingLedgerEntry> result = repo.batchInsert(input);

        assertEquals(3, result.size());
        assertEquals(1001L, result.get(0).ledgerId());
        assertEquals(1002L, result.get(1).ledgerId());
        assertEquals(1003L, result.get(2).ledgerId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TradingLedgerRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsert(captor.capture());
        List<TradingLedgerRecord> records = captor.getValue();
        assertEquals(3, records.size());
        assertNotNull(records.get(0).idempotencyKey());
        assertEquals(1001L, records.get(0).id());
        assertEquals(1002L, records.get(1).id());
        assertEquals(1003L, records.get(2).id());
    }

    @Test
    void batchInsert_entriesWithExplicitId_reusesProvidedId() {
        TradingLedgerMapper mapper = mock(TradingLedgerMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        when(mapper.batchInsert(anyList())).thenReturn(2);
        MybatisTradingLedgerRepository repo = new MybatisTradingLedgerRepository(mapper, idGen);

        OffsetDateTime now = OffsetDateTime.now();
        List<TradingLedgerEntry> input = List.of(
                buildEntry(9001L, TradingLedgerBizType.ORDER_MARGIN_RESERVED, new BigDecimal("100"), "k1", now),
                buildEntry(9002L, TradingLedgerBizType.ORDER_FEE_CHARGED, new BigDecimal("1"), "k2", now)
        );

        List<TradingLedgerEntry> result = repo.batchInsert(input);

        assertEquals(9001L, result.get(0).ledgerId());
        assertEquals(9002L, result.get(1).ledgerId());
        verify(idGen, never()).nextId();
    }

    private TradingLedgerEntry buildEntry(Long ledgerId,
                                          TradingLedgerBizType bizType,
                                          BigDecimal amount,
                                          String idempotencyKey,
                                          OffsetDateTime occurredAt) {
        return new TradingLedgerEntry(
                ledgerId,
                42L,                                  // accountId
                7L,                                   // userId
                bizType,
                amount,
                amount,                               // originalAmount（占位 = amount）
                "USDT",                               // originalCurrency（占位 = 账户币）
                BigDecimal.ONE,                       // fxRateAtSettlement（占位 = 1）
                idempotencyKey,
                "ref-no",
                new BigDecimal("500.00000000"),       // balanceBefore
                new BigDecimal("499.00000000"),       // balanceAfter
                new BigDecimal("0.00000000"),         // frozenBefore
                amount,                               // frozenAfter（占位，与实测不严格对齐）
                new BigDecimal("0.00000000"),         // marginUsedBefore
                new BigDecimal("0.00000000"),         // marginUsedAfter
                occurredAt
        );
    }
}
