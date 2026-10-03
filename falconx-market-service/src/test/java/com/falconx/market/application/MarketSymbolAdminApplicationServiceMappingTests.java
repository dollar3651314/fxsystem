package com.falconx.market.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.market.error.MarketBusinessException;
import com.falconx.market.provider.MarketQuoteProvider;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolGroupVisibilityAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.MarketSwapRateWarmupService;
import com.falconx.market.service.MarketTradingScheduleWarmupService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6: Symbol 三表管理核心规则测试。
 */
@ExtendWith(MockitoExtension.class)
class MarketSymbolAdminApplicationServiceMappingTests {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Mock
    private MarketSymbolAdminMapper mapper;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private MarketSwapRateWarmupService swapRateWarmupService;
    @Mock
    private MarketTradingScheduleWarmupService tradingScheduleWarmupService;
    @Mock
    private MarketQuoteMappingService quoteMappingService;
    @Mock
    private MarketQuoteProvider quoteProvider;
    @Mock
    private com.falconx.market.service.MarketSymbolSpecWarmupService symbolSpecWarmupService;
    @Mock
    private com.falconx.market.repository.RedisMarketSymbolSpecRepository symbolSpecRepository;
    @Mock
    private com.falconx.market.analytics.mapper.MarketQuoteTickMapper quoteTickMapper;

    private MarketSymbolAdminApplicationService service;

    @BeforeEach
    void setUp() {
        service = new MarketSymbolAdminApplicationService(
                mapper,
                idGenerator,
                swapRateWarmupService,
                tradingScheduleWarmupService,
                quoteMappingService,
                quoteProvider,
                symbolSpecWarmupService,
                symbolSpecRepository,
                quoteTickMapper
        );
    }

    @Test
    void shouldAllowSuffixLikePlatformSymbolWhenSourceSymbolExists() {
        MarketSymbolAdminRecord source = symbol("GODSA", "XAUUSD");
        MarketSymbolQuoteMappingAdminRecord created = mapping("XAUUSD.p", "GODSA", "XAUUSD");
        when(mapper.selectQuoteMappingByPlatformSymbol("XAUUSD.p")).thenReturn(null, created);
        when(mapper.selectSymbolByLpCodeAndSymbol("GODSA", "XAUUSD")).thenReturn(source);
        when(quoteMappingService.sourceSymbols()).thenReturn(List.of("XAUUSD"));

        MarketSymbolQuoteMappingAdminRecord result = service.createQuoteMapping(
                "XAUUSD.p",
                "LP",
                "GODSA",
                "XAUUSD",
                3,
                "METAL",
                BigDecimal.ONE,
                ZERO,
                ZERO,
                1,
                1,
                100,
                new BigDecimal("0.0005"),
                ZERO,
                new BigDecimal("0.01"),
                new BigDecimal("100"),
                new BigDecimal("10"),
                2,
                2
        );

        Assertions.assertEquals("XAUUSD.p", result.platformSymbol());
        verify(mapper).insertQuoteMapping("XAUUSD.p", "LP", "GODSA", "XAUUSD", 3, "METAL", BigDecimal.ONE, ZERO, ZERO, 1, 1,
                100, new BigDecimal("0.0005"), ZERO,
                new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("10"),
                2, 2);
        verify(quoteMappingService).refreshMappings();
        verify(quoteProvider).refreshSymbols(List.of("XAUUSD"));
    }

    @Test
    void shouldRejectQuoteMappingWhenSystemPrecisionMissing() {
        MarketSymbolAdminRecord source = symbol("GODSA", "XAUUSD");
        when(mapper.selectQuoteMappingByPlatformSymbol("AAAUSD")).thenReturn(null);
        when(mapper.selectSymbolByLpCodeAndSymbol("GODSA", "XAUUSD")).thenReturn(source);

        MarketBusinessException error = Assertions.assertThrows(MarketBusinessException.class,
                () -> service.createQuoteMapping("AAAUSD", "LP", "GODSA", "XAUUSD", 3, "METAL",
                        BigDecimal.ONE, ZERO, ZERO, 1, 1,
                        100, new BigDecimal("0.0005"), ZERO,
                        new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("10"),
                        null, 2));

        Assertions.assertEquals("90620", error.getCode());
    }

    @Test
    void shouldRejectQuoteMappingWhenSourceSymbolDoesNotExist() {
        when(mapper.selectQuoteMappingByPlatformSymbol("AAAUSD")).thenReturn(null);
        when(mapper.selectSymbolByLpCodeAndSymbol("GODSA", "NOT_EXIST")).thenReturn(null);

        MarketBusinessException error = Assertions.assertThrows(MarketBusinessException.class,
                () -> service.createQuoteMapping("AAAUSD", "LP", "GODSA", "NOT_EXIST", 3, "METAL",
                        BigDecimal.ONE, ZERO, ZERO, 1, 1,
                        100, new BigDecimal("0.0005"), ZERO,
                        new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("10"),
                        2, 2));

        Assertions.assertEquals("90615", error.getCode());
    }

    @Test
    void shouldCreateQuoteMappingForSelectedLpWhenSameSourceSymbolExistsInMultipleLps() {
        MarketSymbolAdminRecord source = symbol("LP2", "XAUUSD");
        MarketSymbolQuoteMappingAdminRecord created = mapping("XAUUSD-LP2", "LP2", "XAUUSD");
        when(mapper.selectQuoteMappingByPlatformSymbol("XAUUSD-LP2")).thenReturn(null, created);
        when(mapper.selectSymbolByLpCodeAndSymbol("LP2", "XAUUSD")).thenReturn(source);
        when(quoteMappingService.sourceSymbols()).thenReturn(List.of("XAUUSD"));

        MarketSymbolQuoteMappingAdminRecord result = service.createQuoteMapping(
                "XAUUSD-LP2",
                "LP",
                "LP2",
                "XAUUSD",
                3,
                "METAL",
                BigDecimal.ONE,
                ZERO,
                ZERO,
                1,
                1,
                100,
                new BigDecimal("0.0005"),
                ZERO,
                new BigDecimal("0.01"),
                new BigDecimal("100"),
                new BigDecimal("10"),
                2,
                2
        );

        Assertions.assertEquals("LP2", result.sourceLpCode());
        Assertions.assertEquals("XAUUSD", result.sourceSymbol());
        verify(mapper).insertQuoteMapping("XAUUSD-LP2", "LP", "LP2", "XAUUSD", 3, "METAL",
                BigDecimal.ONE, ZERO, ZERO, 1, 1, 100, new BigDecimal("0.0005"), ZERO,
                new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("10"), 2, 2);
    }

    @Test
    void shouldCreateSameSourceSymbolUnderDifferentLpCode() {
        when(mapper.selectSymbolByLpCodeAndSymbol("LP2", "XAUUSD")).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(5001L);
        MarketSymbolAdminRecord created = symbol("LP2", "XAUUSD");
        when(mapper.selectSymbolById(5001L)).thenReturn(created);

        MarketSymbolAdminRecord result = service.createSource(
                "LP2",
                "XAUUSD",
                3,
                "METAL",
                "XAU",
                "USD",
                2,
                2
        );

        Assertions.assertEquals("LP2", result.lpCode());
        verify(mapper).insertSymbol(5001L, "LP2", "XAUUSD", 3, "METAL", "XAU", "USD", 2, 2);
    }

    @Test
    void shouldValidateExistingSourceSymbolWhenOnlyUpdatingSourceLpCode() {
        MarketSymbolQuoteMappingAdminRecord existing = mapping("XAUUSD.p", "GODSA", "XAUUSD");
        MarketSymbolQuoteMappingAdminRecord updated = mapping("XAUUSD.p", "LP2", "XAUUSD");
        when(mapper.selectQuoteMappingByPlatformSymbol("XAUUSD.p")).thenReturn(existing, updated);
        when(mapper.selectSymbolByLpCodeAndSymbol("LP2", "XAUUSD")).thenReturn(symbol("LP2", "XAUUSD"));
        when(quoteMappingService.sourceSymbols()).thenReturn(List.of("XAUUSD"));

        MarketSymbolQuoteMappingAdminRecord result = service.updateQuoteMapping(
                "XAUUSD.p",
                null,
                "LP2",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Assertions.assertEquals("LP2", result.sourceLpCode());
        verify(mapper).updateQuoteMapping("XAUUSD.p", null, "LP2", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 2, 2);
    }

    @Test
    void shouldUpsertGroupVisibilityForMappedPlatformSymbol() {
        MarketSymbolQuoteMappingAdminRecord mapping = mapping("AAAUSD", "GODSA", "XAUUSD");
        MarketSymbolGroupVisibilityAdminRecord visible = visibility("vip", "AAAUSD", 1);
        when(mapper.selectQuoteMappingByPlatformSymbol("AAAUSD")).thenReturn(mapping);
        when(mapper.selectGroupVisibility("vip", "AAAUSD")).thenReturn(visible);

        MarketSymbolGroupVisibilityAdminRecord result = service.upsertGroupVisibility("vip", "AAAUSD", 1);

        Assertions.assertEquals("vip", result.groupCode());
        Assertions.assertEquals("AAAUSD", result.symbol());
        Assertions.assertEquals(1, result.visible());
        verify(mapper).upsertGroupVisibility("vip", "AAAUSD", 1);
    }

    @Test
    void shouldRejectSwapRateQueryWhenPlatformMappingDoesNotExist() {
        when(mapper.selectQuoteMappingByPlatformSymbol("NOT_EXIST")).thenReturn(null);

        MarketBusinessException error = Assertions.assertThrows(MarketBusinessException.class,
                () -> service.getSwapRate("NOT_EXIST"));

        Assertions.assertEquals("90613", error.getCode());
    }

    @Test
    void shouldCreateTradingSessionForMappedPlatformSymbolAndRefreshSchedule() {
        MarketSymbolQuoteMappingAdminRecord mapping = mapping("XAUUSD.p", "GODSA", "XAUUSD");
        LocalDate effectiveFrom = LocalDate.now().plusDays(1);
        MarketTradingSessionRecord created = new MarketTradingSessionRecord(
                1001L,
                "XAUUSD.p",
                1,
                1,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                "UTC",
                true,
                effectiveFrom,
                null
        );
        when(mapper.selectQuoteMappingByPlatformSymbol("XAUUSD.p")).thenReturn(mapping);
        when(mapper.countTradingSessionConflict("XAUUSD.p", 1, 1, effectiveFrom, null)).thenReturn(0L);
        when(idGenerator.nextId()).thenReturn(1001L);
        when(mapper.selectTradingSessionByIdForSymbol("XAUUSD.p", 1001L)).thenReturn(created);

        MarketTradingSessionRecord result = service.upsertTradingSession(
                "XAUUSD.p",
                null,
                1,
                1,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                "UTC",
                1,
                effectiveFrom,
                null
        );

        Assertions.assertEquals(1001L, result.id());
        verify(mapper).insertTradingSession(
                1001L,
                "XAUUSD.p",
                1,
                1,
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                "UTC",
                1,
                effectiveFrom,
                null
        );
        verify(tradingScheduleWarmupService).refreshAll();
    }

    @Test
    void shouldUpsertMarketHolidayAndRefreshSchedule() {
        LocalDate holidayDate = LocalDate.now().plusDays(10);
        MarketTradingHolidayRecord created = new MarketTradingHolidayRecord(
                2001L,
                "US_STOCK",
                holidayDate,
                1,
                null,
                null,
                "America/New_York",
                "Market Holiday",
                "US"
        );
        when(mapper.countMarketHolidayConflict("US_STOCK", holidayDate, null)).thenReturn(0L);
        when(idGenerator.nextId()).thenReturn(2001L);
        when(mapper.selectMarketHolidayById(2001L)).thenReturn(created);

        MarketTradingHolidayRecord result = service.upsertMarketHoliday(
                null,
                "US_STOCK",
                holidayDate,
                1,
                null,
                null,
                "America/New_York",
                "Market Holiday",
                "US"
        );

        Assertions.assertEquals("US_STOCK", result.marketCode());
        verify(mapper).insertMarketHoliday(
                2001L,
                "US_STOCK",
                holidayDate,
                1,
                null,
                null,
                "America/New_York",
                "Market Holiday",
                "US"
        );
        verify(tradingScheduleWarmupService).refreshAll();
    }

    private static MarketSymbolAdminRecord symbol(String lpCode, String symbol) {
        return new MarketSymbolAdminRecord(
                1L,
                lpCode,
                symbol,
                3,
                "METAL",
                "XAU",
                "USD",
                2,
                2,
                1,
                LocalDateTime.now()
        );
    }

    private static MarketSymbolQuoteMappingAdminRecord mapping(String platformSymbol, String sourceLpCode, String sourceSymbol) {
        return new MarketSymbolQuoteMappingAdminRecord(
                platformSymbol,
                "LP",
                sourceLpCode,
                sourceSymbol,
                3,
                "METAL",
                BigDecimal.ONE,
                ZERO,
                ZERO,
                1,
                1,
                100,
                new BigDecimal("0.0005"),
                ZERO,
                new BigDecimal("0.01"),
                new BigDecimal("100"),
                new BigDecimal("10"),
                2,
                2,
                1,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static MarketSymbolGroupVisibilityAdminRecord visibility(String groupCode, String symbol, int visible) {
        return new MarketSymbolGroupVisibilityAdminRecord(
                groupCode,
                symbol,
                visible,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
