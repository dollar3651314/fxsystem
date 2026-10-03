package com.falconx.market.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.market.error.MarketBusinessException;
import com.falconx.market.provider.MarketQuoteProvider;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.GroupVisibilityGroupedRecord;
import com.falconx.market.repository.mapper.record.MarketSwapRateRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolGroupVisibilityAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionExceptionRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.MarketSwapRateWarmupService;
import com.falconx.market.service.MarketTradingScheduleWarmupService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-2-SYMBOL: market admin RPC 业务编排（被 internal controller 调用）。
 *
 * <p>错误码段 90600-90649 与 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md)
 * §6.8 对齐。
 *
 * <p>V15 起 {@code t_symbol_quote_mapping.platform_symbol} 是 FalconX 系统 Symbol，
 * 创建后不可改名；category / marketCode / precision / 交易参数均以 mapping 为系统级真源。
 */
@Service
public class MarketSymbolAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(MarketSymbolAdminApplicationService.class);
    private static final int STATUS_TRADING = 1;
    private static final int STATUS_SUSPENDED = 2;
    private static final String DEFAULT_LP_CODE = "GODSA";

    private final MarketSymbolAdminMapper mapper;
    private final IdGenerator idGenerator;
    private final MarketSwapRateWarmupService swapRateWarmupService;
    private final MarketTradingScheduleWarmupService tradingScheduleWarmupService;
    private final MarketQuoteMappingService quoteMappingService;
    private final MarketQuoteProvider quoteProvider;
    private final com.falconx.market.service.MarketSymbolSpecWarmupService symbolSpecWarmupService;
    private final com.falconx.market.repository.RedisMarketSymbolSpecRepository symbolSpecRepository;
    private final com.falconx.market.analytics.mapper.MarketQuoteTickMapper quoteTickMapper;

    public MarketSymbolAdminApplicationService(MarketSymbolAdminMapper mapper,
                                               IdGenerator idGenerator,
                                               MarketSwapRateWarmupService swapRateWarmupService,
                                               MarketTradingScheduleWarmupService tradingScheduleWarmupService,
                                               MarketQuoteMappingService quoteMappingService,
                                               MarketQuoteProvider quoteProvider,
                                               com.falconx.market.service.MarketSymbolSpecWarmupService symbolSpecWarmupService,
                                               com.falconx.market.repository.RedisMarketSymbolSpecRepository symbolSpecRepository,
                                               com.falconx.market.analytics.mapper.MarketQuoteTickMapper quoteTickMapper) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.swapRateWarmupService = swapRateWarmupService;
        this.tradingScheduleWarmupService = tradingScheduleWarmupService;
        this.quoteMappingService = quoteMappingService;
        this.quoteProvider = quoteProvider;
        this.symbolSpecWarmupService = symbolSpecWarmupService;
        this.symbolSpecRepository = symbolSpecRepository;
        this.quoteTickMapper = quoteTickMapper;
    }

    @Transactional(readOnly = true)
    public ListResult listSymbols(Integer category, String marketCode, Integer status, String lpCode, String symbolLike,
                                    int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        String normalizedLpCode = trimToNull(lpCode);
        List<MarketSymbolAdminRecord> items = mapper.selectSymbolsForList(
                category, marketCode, status, normalizedLpCode, symbolLike, offset, safeSize);
        long total = mapper.countSymbols(category, marketCode, status, normalizedLpCode, symbolLike);
        return new ListResult(items, total, page, safeSize);
    }

    @Transactional(readOnly = true)
    public DetailResult getSymbolDetail(long id) {
        MarketSymbolAdminRecord symbol = mapper.selectSymbolById(id);
        if (symbol == null) {
            throw new MarketBusinessException("90600", "Admin Symbol Not Found");
        }
        // t_symbol 是 LP 源元数据；Swap / 交易时段从 V16 起只挂 platform_symbol。
        return new DetailResult(symbol, null, List.of());
    }

    /**
     * 编辑 source 元数据（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后字段集裁剪）。
     *
     * <p>原 max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional 已下沉到
     * t_symbol_quote_mapping，相关 90601-90604 校验语义搬迁到 mapping 接口（{@code updateQuoteMapping}）。
     */
    @Transactional
    public MarketSymbolAdminRecord updateSymbolConfig(long id, Integer category, String marketCode,
                                                       Integer pricePrecision, Integer qtyPrecision) {
        MarketSymbolAdminRecord existing = mapper.selectSymbolById(id);
        if (existing == null) {
            throw new MarketBusinessException("90600", "Admin Symbol Not Found");
        }
        if (pricePrecision != null && (pricePrecision < 0 || pricePrecision > 10)) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        if (qtyPrecision != null && (qtyPrecision < 0 || qtyPrecision > 10)) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        mapper.updateSymbolConfig(id, category, marketCode, pricePrecision, qtyPrecision);
        log.info("market.symbol.admin.update.completed id={} category={} marketCode={} pricePrecision={} qtyPrecision={}",
                id, category, marketCode, pricePrecision, qtyPrecision);
        return mapper.selectSymbolById(id);
    }

    @Transactional
    public MarketSymbolAdminRecord setStatus(long id, int newStatus) {
        MarketSymbolAdminRecord existing = mapper.selectSymbolById(id);
        if (existing == null) {
            throw new MarketBusinessException("90600", "Admin Symbol Not Found");
        }
        if (newStatus == STATUS_SUSPENDED && existing.status() == STATUS_SUSPENDED) {
            throw new MarketBusinessException("90605", "Admin Symbol Already Suspended");
        }
        if (newStatus == STATUS_TRADING && existing.status() == STATUS_TRADING) {
            throw new MarketBusinessException("90606", "Admin Symbol Already Trading");
        }
        mapper.updateSymbolStatus(id, newStatus);
        log.info("market.symbol.admin.status.changed id={} newStatus={}", id, newStatus);
        return mapper.selectSymbolById(id);
    }

    @Transactional(readOnly = true)
    public SwapRateResult getSwapRate(String symbol) {
        String normalizedSymbol = requireSymbolText(symbol, "90613");
        if (mapper.selectQuoteMappingByPlatformSymbol(normalizedSymbol) == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        MarketSwapRateRecord current = mapper.selectCurrentSwapRate(normalizedSymbol, LocalDate.now());
        List<MarketSwapRateRecord> recent = mapper.selectRecentSwapRates(normalizedSymbol, 5);
        return new SwapRateResult(normalizedSymbol, current, recent);
    }

    @Transactional
    public MarketSwapRateRecord upsertSwapRate(String symbol, BigDecimal longRate, BigDecimal shortRate,
                                                 LocalTime rolloverTime, LocalDate effectiveFrom) {
        String normalizedSymbol = requireSymbolText(symbol, "90613");
        if (mapper.selectQuoteMappingByPlatformSymbol(normalizedSymbol) == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        if (effectiveFrom == null || effectiveFrom.isBefore(LocalDate.now())) {
            throw new MarketBusinessException("90611", "Admin Symbol Swap Rate Date Invalid");
        }
        BigDecimal limit = new BigDecimal("0.01");
        if (longRate.abs().compareTo(limit) > 0 || shortRate.abs().compareTo(limit) > 0) {
            throw new MarketBusinessException("90612", "Admin Symbol Swap Rate Out Of Range");
        }
        if (mapper.countSwapRateBySymbolAndDate(normalizedSymbol, effectiveFrom) > 0) {
            throw new MarketBusinessException("90610", "Admin Symbol Swap Rate Overlap Date");
        }
        long id = idGenerator.nextId();
        LocalTime effRollover = rolloverTime != null ? rolloverTime : LocalTime.of(22, 0);
        mapper.insertSwapRate(id, normalizedSymbol, longRate, shortRate, effRollover, effectiveFrom);
        refreshSwapRateSnapshotAfterCommit(normalizedSymbol);
        log.info("market.swap-rate.admin.inserted id={} symbol={} effFrom={}", id, normalizedSymbol, effectiveFrom);
        return mapper.selectCurrentSwapRate(normalizedSymbol, effectiveFrom);
    }

    @Transactional(readOnly = true)
    public TradingHoursResult getTradingHours(String symbol) {
        String normalizedSymbol = requireSymbolText(symbol, "90613");
        MarketSymbolQuoteMappingAdminRecord mapping = mapper.selectQuoteMappingByPlatformSymbol(normalizedSymbol);
        if (mapping == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        List<MarketTradingSessionRecord> sessions = mapper.selectTradingSessions(normalizedSymbol);
        List<MarketTradingSessionExceptionRecord> exceptions = mapper.selectTradingExceptions(normalizedSymbol);
        List<MarketTradingHolidayRecord> holidays = mapper.selectMarketHolidays(mapping.marketCode());
        return new TradingHoursResult(normalizedSymbol, mapping.marketCode(), sessions, exceptions, holidays);
    }

    @Transactional
    public MarketTradingSessionRecord upsertTradingSession(String symbol,
                                                           Long id,
                                                           Integer dayOfWeek,
                                                           Integer sessionNo,
                                                           LocalTime openTime,
                                                           LocalTime closeTime,
                                                           String timezone,
                                                           Integer enabled,
                                                           LocalDate effectiveFrom,
                                                           LocalDate effectiveTo) {
        String normalizedSymbol = requireMappedPlatformSymbol(symbol);
        Integer normalizedDayOfWeek = requireDayOfWeek(dayOfWeek);
        Integer normalizedSessionNo = requireSessionNo(sessionNo);
        LocalTime normalizedOpen = requireTime(openTime);
        LocalTime normalizedClose = requireTime(closeTime);
        if (normalizedOpen.equals(normalizedClose)) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        String normalizedTimezone = requireTimezone(timezone, "90621");
        Integer normalizedEnabled = normalizeToggle(enabled, "90621");
        LocalDate normalizedEffectiveFrom = requireDate(effectiveFrom, "90621");
        if (effectiveTo != null && effectiveTo.isBefore(normalizedEffectiveFrom)) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        if (id != null && mapper.selectTradingSessionByIdForSymbol(normalizedSymbol, id) == null) {
            throw new MarketBusinessException("90622", "Admin Symbol Trading Schedule Not Found");
        }
        if (mapper.countTradingSessionConflict(
                normalizedSymbol, normalizedDayOfWeek, normalizedSessionNo, normalizedEffectiveFrom, id) > 0) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }

        long rowId = id == null ? idGenerator.nextId() : id;
        if (id == null) {
            mapper.insertTradingSession(
                    rowId,
                    normalizedSymbol,
                    normalizedDayOfWeek,
                    normalizedSessionNo,
                    normalizedOpen,
                    normalizedClose,
                    normalizedTimezone,
                    normalizedEnabled,
                    normalizedEffectiveFrom,
                    effectiveTo
            );
        } else {
            mapper.updateTradingSession(
                    rowId,
                    normalizedSymbol,
                    normalizedDayOfWeek,
                    normalizedSessionNo,
                    normalizedOpen,
                    normalizedClose,
                    normalizedTimezone,
                    normalizedEnabled,
                    normalizedEffectiveFrom,
                    effectiveTo
            );
        }
        refreshTradingScheduleSnapshotAfterCommit(normalizedSymbol);
        log.info("market.trading-session.admin.upserted id={} symbol={} dayOfWeek={} sessionNo={}",
                rowId, normalizedSymbol, normalizedDayOfWeek, normalizedSessionNo);
        return mapper.selectTradingSessionByIdForSymbol(normalizedSymbol, rowId);
    }

    @Transactional
    public void deleteTradingSession(String symbol, long id) {
        String normalizedSymbol = requireMappedPlatformSymbol(symbol);
        int deleted = mapper.deleteTradingSession(normalizedSymbol, id);
        if (deleted == 0) {
            throw new MarketBusinessException("90622", "Admin Symbol Trading Schedule Not Found");
        }
        refreshTradingScheduleSnapshotAfterCommit(normalizedSymbol);
        log.info("market.trading-session.admin.deleted id={} symbol={}", id, normalizedSymbol);
    }

    @Transactional
    public MarketTradingSessionExceptionRecord upsertTradingException(String symbol,
                                                                      Long id,
                                                                      LocalDate tradeDate,
                                                                      Integer exceptionType,
                                                                      Integer sessionNo,
                                                                      LocalTime openTime,
                                                                      LocalTime closeTime,
                                                                      String timezone,
                                                                      String reason) {
        String normalizedSymbol = requireMappedPlatformSymbol(symbol);
        LocalDate normalizedTradeDate = requireDate(tradeDate, "90621");
        Integer normalizedType = requireExceptionType(exceptionType);
        Integer normalizedSessionNo = normalizedType == 1 ? sessionNo : requireSessionNo(sessionNo);
        LocalTime normalizedOpen = openTime;
        LocalTime normalizedClose = closeTime;
        if (normalizedType == 2) {
            normalizedOpen = requireTime(openTime);
            normalizedClose = requireTime(closeTime);
            if (normalizedOpen.equals(normalizedClose)) {
                throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
            }
        }
        String normalizedTimezone = requireTimezone(timezone, "90621");
        String normalizedReason = normalizeReason128(reason);
        if (id != null && mapper.selectTradingExceptionByIdForSymbol(normalizedSymbol, id) == null) {
            throw new MarketBusinessException("90622", "Admin Symbol Trading Schedule Not Found");
        }
        if (mapper.countTradingExceptionConflict(normalizedSymbol, normalizedTradeDate, normalizedSessionNo, id) > 0) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }

        long rowId = id == null ? idGenerator.nextId() : id;
        if (id == null) {
            mapper.insertTradingException(
                    rowId,
                    normalizedSymbol,
                    normalizedTradeDate,
                    normalizedType,
                    normalizedSessionNo,
                    normalizedOpen,
                    normalizedClose,
                    normalizedTimezone,
                    normalizedReason
            );
        } else {
            mapper.updateTradingException(
                    rowId,
                    normalizedSymbol,
                    normalizedTradeDate,
                    normalizedType,
                    normalizedSessionNo,
                    normalizedOpen,
                    normalizedClose,
                    normalizedTimezone,
                    normalizedReason
            );
        }
        refreshTradingScheduleSnapshotAfterCommit(normalizedSymbol);
        log.info("market.trading-exception.admin.upserted id={} symbol={} tradeDate={} type={}",
                rowId, normalizedSymbol, normalizedTradeDate, normalizedType);
        return mapper.selectTradingExceptionByIdForSymbol(normalizedSymbol, rowId);
    }

    @Transactional
    public void deleteTradingException(String symbol, long id) {
        String normalizedSymbol = requireMappedPlatformSymbol(symbol);
        int deleted = mapper.deleteTradingException(normalizedSymbol, id);
        if (deleted == 0) {
            throw new MarketBusinessException("90622", "Admin Symbol Trading Schedule Not Found");
        }
        refreshTradingScheduleSnapshotAfterCommit(normalizedSymbol);
        log.info("market.trading-exception.admin.deleted id={} symbol={}", id, normalizedSymbol);
    }

    @Transactional(readOnly = true)
    public HolidayListResult listMarketHolidays(String marketCode, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        String normalizedMarketCode = trimToNull(marketCode);
        List<MarketTradingHolidayRecord> items = mapper.selectMarketHolidaysForList(normalizedMarketCode, offset, safeSize);
        long total = mapper.countMarketHolidays(normalizedMarketCode);
        return new HolidayListResult(items, total, page, safeSize);
    }

    @Transactional
    public MarketTradingHolidayRecord upsertMarketHoliday(Long id,
                                                          String marketCode,
                                                          LocalDate holidayDate,
                                                          Integer holidayType,
                                                          LocalTime openTime,
                                                          LocalTime closeTime,
                                                          String timezone,
                                                          String holidayName,
                                                          String countryCode) {
        String normalizedMarketCode = requireMarketCode(marketCode);
        LocalDate normalizedHolidayDate = requireDate(holidayDate, "90623");
        Integer normalizedHolidayType = requireHolidayType(holidayType, openTime, closeTime);
        String normalizedTimezone = requireTimezone(timezone, "90623");
        String normalizedHolidayName = trimToNull(holidayName);
        if (normalizedHolidayName == null || normalizedHolidayName.length() > 128) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }
        String normalizedCountryCode = trimToNull(countryCode);
        if (normalizedCountryCode != null && normalizedCountryCode.length() > 16) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }
        if (id != null && mapper.selectMarketHolidayById(id) == null) {
            throw new MarketBusinessException("90624", "Admin Symbol Holiday Not Found");
        }
        if (mapper.countMarketHolidayConflict(normalizedMarketCode, normalizedHolidayDate, id) > 0) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }

        long rowId = id == null ? idGenerator.nextId() : id;
        if (id == null) {
            mapper.insertMarketHoliday(
                    rowId,
                    normalizedMarketCode,
                    normalizedHolidayDate,
                    normalizedHolidayType,
                    openTime,
                    closeTime,
                    normalizedTimezone,
                    normalizedHolidayName,
                    normalizedCountryCode
            );
        } else {
            mapper.updateMarketHoliday(
                    rowId,
                    normalizedMarketCode,
                    normalizedHolidayDate,
                    normalizedHolidayType,
                    openTime,
                    closeTime,
                    normalizedTimezone,
                    normalizedHolidayName,
                    normalizedCountryCode
            );
        }
        refreshTradingScheduleSnapshotAfterCommit(normalizedMarketCode);
        log.info("market.trading-holiday.admin.upserted id={} marketCode={} holidayDate={} type={}",
                rowId, normalizedMarketCode, normalizedHolidayDate, normalizedHolidayType);
        return mapper.selectMarketHolidayById(rowId);
    }

    @Transactional
    public void deleteMarketHoliday(long id) {
        int deleted = mapper.deleteMarketHoliday(id);
        if (deleted == 0) {
            throw new MarketBusinessException("90624", "Admin Symbol Holiday Not Found");
        }
        refreshTradingScheduleSnapshotAfterCommit("holiday:" + id);
        log.info("market.trading-holiday.admin.deleted id={}", id);
    }

    @Transactional(readOnly = true)
    public QuoteMappingListResult listQuoteMappings(String platformSymbolLike, String sourceLpCode, String sourceSymbolLike,
                                                    Integer enabled, Integer lpSubscribeEnabled,
                                                    int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        Integer normalizedEnabled = normalizeToggleOrNull(enabled, "90616");
        Integer normalizedLpSubscribeEnabled = normalizeToggleOrNull(lpSubscribeEnabled, "90616");
        List<MarketSymbolQuoteMappingAdminRecord> items = mapper.selectQuoteMappingsForList(
                trimToNull(platformSymbolLike),
                trimToNull(sourceLpCode),
                trimToNull(sourceSymbolLike),
                normalizedEnabled,
                normalizedLpSubscribeEnabled,
                offset,
                safeSize
        );
        long total = mapper.countQuoteMappings(
                trimToNull(platformSymbolLike),
                trimToNull(sourceLpCode),
                trimToNull(sourceSymbolLike),
                normalizedEnabled,
                normalizedLpSubscribeEnabled
        );
        return new QuoteMappingListResult(items, total, page, safeSize);
    }

    /**
     * 新建报价映射（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B + V15 字段集扩展）。
     *
     * <p>R2 三轮契约：90601-90604 校验语义从 source 接口搬迁至此；
     * 新增 6 交易参数 + 2 precision + category / marketCode 必填校验。
     */
    @Transactional
    public MarketSymbolQuoteMappingAdminRecord createQuoteMapping(String platformSymbol, String sourceProvider,
                                                                  String sourceLpCode,
                                                                  String sourceSymbol,
                                                                  Integer category,
                                                                  String marketCode,
                                                                  BigDecimal priceMultiplier,
                                                                  BigDecimal bidAdjustment,
                                                                  BigDecimal askAdjustment,
                                                                  Integer enabled,
                                                                  Integer lpSubscribeEnabled,
                                                                  Integer maxLeverage,
                                                                  BigDecimal takerFeeRate,
                                                                  BigDecimal spread,
                                                                  BigDecimal minQty,
                                                                  BigDecimal maxQty,
                                                                  BigDecimal minNotional,
                                                                  Integer pricePrecision,
                                                                  Integer qtyPrecision) {
        String normalizedPlatformSymbol = requireSymbolText(platformSymbol, "90614");
        if (mapper.selectQuoteMappingByPlatformSymbol(normalizedPlatformSymbol) != null) {
            throw new MarketBusinessException("90614", "Admin Symbol Mapping Duplicate");
        }
        String normalizedSourceLpCode = requireLpCode(sourceLpCode);
        String normalizedSourceSymbol = requireExistingSourceSymbol(normalizedSourceLpCode, sourceSymbol);
        String normalizedProvider = normalizeSourceProvider(sourceProvider);
        Integer normalizedCategory = requireCategory(category);
        String normalizedMarketCode = requireMarketCode(marketCode);
        BigDecimal normalizedMultiplier = requirePositiveMultiplier(priceMultiplier);
        BigDecimal normalizedBidAdjustment = bidAdjustment == null ? BigDecimal.ZERO : bidAdjustment;
        BigDecimal normalizedAskAdjustment = askAdjustment == null ? BigDecimal.ZERO : askAdjustment;
        Integer normalizedEnabled = normalizeToggle(enabled, "90616");
        Integer normalizedLpSubscribeEnabled = normalizeToggle(lpSubscribeEnabled, "90616");
        Integer normalizedLeverage = requireLeverage(maxLeverage);
        BigDecimal normalizedFee = requireFeeRate(takerFeeRate);
        BigDecimal normalizedSpread = requireNonNegativeSpread(spread);
        BigDecimal normalizedMinQty = requireMinQty(minQty);
        BigDecimal normalizedMaxQty = requireMaxQty(normalizedMinQty, maxQty);
        BigDecimal normalizedMinNotional = requireMinNotional(minNotional);
        Integer normalizedPricePrecision = requirePrecision(pricePrecision);
        Integer normalizedQtyPrecision = requirePrecision(qtyPrecision);

        mapper.insertQuoteMapping(
                normalizedPlatformSymbol,
                normalizedProvider,
                normalizedSourceLpCode,
                normalizedSourceSymbol,
                normalizedCategory,
                normalizedMarketCode,
                normalizedMultiplier,
                normalizedBidAdjustment,
                normalizedAskAdjustment,
                normalizedEnabled,
                normalizedLpSubscribeEnabled,
                normalizedLeverage,
                normalizedFee,
                normalizedSpread,
                normalizedMinQty,
                normalizedMaxQty,
                normalizedMinNotional,
                normalizedPricePrecision,
                normalizedQtyPrecision
        );
        refreshQuoteMappingsAfterCommit(normalizedPlatformSymbol);
        log.info("market.symbol.quote-mapping.created platformSymbol={} sourceLpCode={} sourceSymbol={} maxLeverage={} takerFeeRate={}",
                normalizedPlatformSymbol, normalizedSourceLpCode, normalizedSourceSymbol, normalizedLeverage, normalizedFee);
        return mapper.selectQuoteMappingByPlatformSymbol(normalizedPlatformSymbol);
    }

    @Transactional
    public MarketSymbolQuoteMappingAdminRecord updateQuoteMapping(String platformSymbol, String sourceProvider,
                                                                  String sourceLpCode,
                                                                  String sourceSymbol,
                                                                  Integer category,
                                                                  String marketCode,
                                                                  BigDecimal priceMultiplier,
                                                                  BigDecimal bidAdjustment,
                                                                  BigDecimal askAdjustment,
                                                                  Integer enabled,
                                                                  Integer lpSubscribeEnabled,
                                                                  Integer maxLeverage,
                                                                  BigDecimal takerFeeRate,
                                                                  BigDecimal spread,
                                                                  BigDecimal minQty,
                                                                  BigDecimal maxQty,
                                                                  BigDecimal minNotional,
                                                                  Integer pricePrecision,
                                                                  Integer qtyPrecision) {
        String normalizedPlatformSymbol = requireSymbolText(platformSymbol, "90613");
        MarketSymbolQuoteMappingAdminRecord existing = mapper.selectQuoteMappingByPlatformSymbol(normalizedPlatformSymbol);
        if (existing == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        String effectiveSourceLpCode = sourceLpCode == null ? existing.sourceLpCode() : requireLpCode(sourceLpCode);
        String effectiveSourceSymbol = sourceSymbol == null ? existing.sourceSymbol() : requireSymbolText(sourceSymbol, "90615");
        if (sourceLpCode != null || sourceSymbol != null) {
            requireExistingSourceSymbol(effectiveSourceLpCode, effectiveSourceSymbol);
        }
        String normalizedSourceSymbol = sourceSymbol == null ? null : effectiveSourceSymbol;
        String normalizedSourceLpCode = sourceLpCode == null ? null : effectiveSourceLpCode;
        String normalizedProvider = sourceProvider == null ? null : normalizeSourceProvider(sourceProvider);
        Integer normalizedCategory = category == null ? null : requireCategory(category);
        String normalizedMarketCode = marketCode == null ? null : requireMarketCode(marketCode);
        BigDecimal normalizedMultiplier = priceMultiplier == null ? null : requirePositiveMultiplier(priceMultiplier);
        Integer normalizedEnabled = enabled == null ? null : normalizeToggle(enabled, "90616");
        Integer normalizedLpSubscribeEnabled = lpSubscribeEnabled == null ? null : normalizeToggle(lpSubscribeEnabled, "90616");
        Integer normalizedLeverage = maxLeverage == null ? null : requireLeverage(maxLeverage);
        BigDecimal normalizedFee = takerFeeRate == null ? null : requireFeeRate(takerFeeRate);
        BigDecimal normalizedSpread = spread == null ? null : requireNonNegativeSpread(spread);
        BigDecimal effMinQty = minQty != null ? requireMinQty(minQty) : existing.minQty();
        BigDecimal effMaxQty = maxQty != null ? maxQty : existing.maxQty();
        if (minQty != null || maxQty != null) {
            requireMaxQty(effMinQty, effMaxQty);
        }
        BigDecimal normalizedMinNotional = minNotional == null ? null : requireMinNotional(minNotional);
        Integer normalizedPricePrecision = pricePrecision == null ? existing.pricePrecision() : requirePrecision(pricePrecision);
        Integer normalizedQtyPrecision = qtyPrecision == null ? existing.qtyPrecision() : requirePrecision(qtyPrecision);

        mapper.updateQuoteMapping(
                normalizedPlatformSymbol,
                normalizedProvider,
                normalizedSourceLpCode,
                normalizedSourceSymbol,
                normalizedCategory,
                normalizedMarketCode,
                normalizedMultiplier,
                bidAdjustment,
                askAdjustment,
                normalizedEnabled,
                normalizedLpSubscribeEnabled,
                normalizedLeverage,
                normalizedFee,
                normalizedSpread,
                minQty,
                maxQty,
                normalizedMinNotional,
                normalizedPricePrecision,
                normalizedQtyPrecision
        );
        refreshQuoteMappingsAfterCommit(normalizedPlatformSymbol);
        log.info("market.symbol.quote-mapping.updated platformSymbol={} sourceLpCode={} sourceSymbol={} maxLeverage={} takerFeeRate={}",
                normalizedPlatformSymbol, effectiveSourceLpCode, effectiveSourceSymbol, normalizedLeverage, normalizedFee);
        return mapper.selectQuoteMappingByPlatformSymbol(normalizedPlatformSymbol);
    }

    @Transactional(readOnly = true)
    public GroupVisibilityListResult listGroupVisibility(String groupCode, String symbolLike, Integer visible,
                                                         int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        Integer normalizedVisible = normalizeToggleOrNull(visible, "90618");
        List<MarketSymbolGroupVisibilityAdminRecord> items = mapper.selectGroupVisibilityForList(
                trimToNull(groupCode),
                trimToNull(symbolLike),
                normalizedVisible,
                offset,
                safeSize
        );
        long total = mapper.countGroupVisibility(trimToNull(groupCode), trimToNull(symbolLike), normalizedVisible);
        return new GroupVisibilityListResult(items, total, page, safeSize);
    }

    @Transactional
    public MarketSymbolGroupVisibilityAdminRecord upsertGroupVisibility(String groupCode, String symbol, Integer visible) {
        String normalizedGroupCode = requireGroupCode(groupCode);
        String normalizedSymbol = requireSymbolText(symbol, "90613");
        if (mapper.selectQuoteMappingByPlatformSymbol(normalizedSymbol) == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        Integer normalizedVisible = normalizeToggle(visible, "90618");
        mapper.upsertGroupVisibility(normalizedGroupCode, normalizedSymbol, normalizedVisible);
        log.info("market.symbol.group-visibility.upserted groupCode={} symbol={} visible={}",
                normalizedGroupCode, normalizedSymbol, normalizedVisible);
        return mapper.selectGroupVisibility(normalizedGroupCode, normalizedSymbol);
    }

    @Transactional
    public List<MarketSymbolGroupVisibilityAdminRecord> bulkUpsertGroupVisibility(String groupCode,
                                                                                 List<String> symbols,
                                                                                 Integer visible) {
        String normalizedGroupCode = requireGroupCode(groupCode);
        Integer normalizedVisible = normalizeToggle(visible, "90618");
        if (symbols == null || symbols.isEmpty() || symbols.size() > 500) {
            throw new MarketBusinessException("90618", "Admin Symbol Group Visibility Invalid");
        }
        List<String> normalizedSymbols = symbols.stream()
                .map(symbol -> requireSymbolText(symbol, "90613"))
                .distinct()
                .toList();
        for (String symbol : normalizedSymbols) {
            if (mapper.selectQuoteMappingByPlatformSymbol(symbol) == null) {
                throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
            }
        }
        for (String symbol : normalizedSymbols) {
            mapper.upsertGroupVisibility(normalizedGroupCode, symbol, normalizedVisible);
        }
        log.info("market.symbol.group-visibility.bulk-upserted groupCode={} count={} visible={}",
                normalizedGroupCode, normalizedSymbols.size(), normalizedVisible);
        return normalizedSymbols.stream()
                .map(symbol -> mapper.selectGroupVisibility(normalizedGroupCode, symbol))
                .toList();
    }

    public record ListResult(List<MarketSymbolAdminRecord> items, long total, int page, int size) {
    }

    public record DetailResult(MarketSymbolAdminRecord symbol,
                                MarketSwapRateRecord currentSwapRate,
                                List<MarketTradingSessionRecord> sessions) {
    }

    public record SwapRateResult(String symbol,
                                  MarketSwapRateRecord current,
                                  List<MarketSwapRateRecord> history) {
    }

    public record TradingHoursResult(String symbol,
                                      String marketCode,
                                      List<MarketTradingSessionRecord> sessions,
                                      List<MarketTradingSessionExceptionRecord> exceptions,
                                      List<MarketTradingHolidayRecord> holidays) {
    }

    public record HolidayListResult(List<MarketTradingHolidayRecord> items,
                                    long total,
                                    int page,
                                    int size) {
    }

    public record QuoteMappingListResult(List<MarketSymbolQuoteMappingAdminRecord> items,
                                         long total,
                                         int page,
                                         int size) {
    }

    public record GroupVisibilityListResult(List<MarketSymbolGroupVisibilityAdminRecord> items,
                                            long total,
                                            int page,
                                            int size) {
    }

    private void refreshSwapRateSnapshotAfterCommit(String symbol) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            swapRateWarmupService.refreshAll();
            log.info("market.swap-rate.admin.snapshot.refreshed symbol={} timing=immediate", symbol);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                swapRateWarmupService.refreshAll();
                log.info("market.swap-rate.admin.snapshot.refreshed symbol={} timing=after_commit", symbol);
            }
        });
    }

    private void refreshTradingScheduleSnapshotAfterCommit(String scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            tradingScheduleWarmupService.refreshAll();
            log.info("market.trading-schedule.admin.snapshot.refreshed scope={} timing=immediate", scope);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tradingScheduleWarmupService.refreshAll();
                log.info("market.trading-schedule.admin.snapshot.refreshed scope={} timing=after_commit", scope);
            }
        });
    }

    private void refreshQuoteMappingsAfterCommit(String platformSymbol) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshQuoteMappingsNow(platformSymbol, "immediate");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshQuoteMappingsNow(platformSymbol, "after_commit");
            }
        });
    }

    private void refreshQuoteMappingsNow(String platformSymbol, String timing) {
        quoteMappingService.refreshMappings();
        List<String> sourceSymbols = quoteMappingService.sourceSymbols();
        quoteProvider.refreshSymbols(sourceSymbols);
        // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：同步刷新 SymbolSpec Redis Hash
        symbolSpecWarmupService.refresh(platformSymbol);
        swapRateWarmupService.refreshAll();
        tradingScheduleWarmupService.refreshAll();
        log.info("market.symbol.quote-mapping.snapshot.refreshed platformSymbol={} timing={} sourceSymbolCount={}",
                platformSymbol, timing, sourceSymbols.size());
    }

    private String requireExistingSourceSymbol(String sourceLpCode, String sourceSymbol) {
        String normalizedSourceSymbol = requireSymbolText(sourceSymbol, "90615");
        if (mapper.selectSymbolByLpCodeAndSymbol(sourceLpCode, normalizedSourceSymbol) == null) {
            throw new MarketBusinessException("90615", "Admin Symbol Mapping Source Not Found");
        }
        return normalizedSourceSymbol;
    }

    private String requireMappedPlatformSymbol(String symbol) {
        String normalizedSymbol = requireSymbolText(symbol, "90613");
        if (mapper.selectQuoteMappingByPlatformSymbol(normalizedSymbol) == null) {
            throw new MarketBusinessException("90613", "Admin Symbol Mapping Not Found");
        }
        return normalizedSymbol;
    }

    private static Integer requireDayOfWeek(Integer value) {
        if (value == null || value < 1 || value > 7) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        return value;
    }

    private static Integer requireSessionNo(Integer value) {
        if (value == null || value < 1 || value > 99) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        return value;
    }

    private static LocalTime requireTime(LocalTime value) {
        if (value == null) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        return value;
    }

    private static LocalDate requireDate(LocalDate value, String errorCode) {
        if (value == null) {
            throw new MarketBusinessException(errorCode, "Admin Symbol Date Invalid");
        }
        return value;
    }

    private static String requireTimezone(String value, String errorCode) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > 32) {
            throw new MarketBusinessException(errorCode, "Admin Symbol Timezone Invalid");
        }
        try {
            ZoneId.of(normalized);
        } catch (DateTimeException ex) {
            throw new MarketBusinessException(errorCode, "Admin Symbol Timezone Invalid");
        }
        return normalized;
    }

    private static Integer requireExceptionType(Integer value) {
        if (value == null || (value != 1 && value != 2)) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        return value;
    }

    private static Integer requireHolidayType(Integer value, LocalTime openTime, LocalTime closeTime) {
        if (value == null || value < 1 || value > 3) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }
        if (value == 2 && closeTime == null) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }
        if (value == 3 && openTime == null) {
            throw new MarketBusinessException("90623", "Admin Symbol Holiday Invalid");
        }
        return value;
    }

    private static String normalizeReason128(String reason) {
        String normalized = trimToNull(reason);
        if (normalized != null && normalized.length() > 128) {
            throw new MarketBusinessException("90621", "Admin Symbol Trading Schedule Invalid");
        }
        return normalized;
    }

    private static String normalizeSourceProvider(String sourceProvider) {
        String normalized = trimToNull(sourceProvider);
        return normalized == null ? "LP" : normalized;
    }

    private static String requireLpCode(String lpCode) {
        String normalized = trimToNull(lpCode);
        if (normalized == null) {
            return DEFAULT_LP_CODE;
        }
        if (normalized.length() > 32) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal requirePositiveMultiplier(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MarketBusinessException("90616", "Admin Symbol Mapping Invalid Price Rule");
        }
        return value;
    }

    private static Integer normalizeToggle(Integer value, String errorCode) {
        if (value == null || (value != 0 && value != 1)) {
            throw new MarketBusinessException(errorCode, "Admin Symbol Toggle Invalid");
        }
        return value;
    }

    private static Integer normalizeToggleOrNull(Integer value, String errorCode) {
        if (value == null) {
            return null;
        }
        return normalizeToggle(value, errorCode);
    }

    private static String requireSymbolText(String symbol, String errorCode) {
        String normalized = trimToNull(symbol);
        if (normalized == null || normalized.length() > 32) {
            throw new MarketBusinessException(errorCode, "Admin Symbol Text Invalid");
        }
        return normalized;
    }

    private static String requireGroupCode(String groupCode) {
        String normalized = trimToNull(groupCode);
        if (normalized == null || normalized.length() > 64) {
            throw new MarketBusinessException("90618", "Admin Symbol Invalid Group Code");
        }
        return normalized;
    }

    // ---- STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：新增 4 端点 service 实现 ----

    /**
     * 新建 LP 源 symbol（POST /internal/v1/market/symbols）。
     *
     * <p>仅允许 status=1 创建；如需 suspended 后续走 setStatus 接口。
     * 写入 t_symbol 后触发 LP 订阅刷新 + SymbolSpec Redis warmup 占位（mapping 派生后再装配）。
     */
    @Transactional
    public MarketSymbolAdminRecord createSource(String lpCode, String symbol, Integer category, String marketCode,
                                                 String baseCurrency, String quoteCurrency,
                                                 Integer pricePrecision, Integer qtyPrecision) {
        String normalizedLpCode = requireLpCode(lpCode);
        String normalizedSymbol = requireSymbolText(symbol, "90620");
        if (mapper.selectSymbolByLpCodeAndSymbol(normalizedLpCode, normalizedSymbol) != null) {
            throw new com.falconx.market.error.MarketBusinessException("90619", "Admin Symbol Source Duplicate");
        }
        if (category == null || category < 1 || category > 8) {
            throw new com.falconx.market.error.MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        String normalizedMarketCode = trimToNull(marketCode);
        if (normalizedMarketCode == null || normalizedMarketCode.length() > 32) {
            throw new com.falconx.market.error.MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        String normalizedBase = trimToNull(baseCurrency);
        String normalizedQuote = trimToNull(quoteCurrency);
        if (normalizedBase == null || normalizedBase.length() > 16
                || normalizedQuote == null || normalizedQuote.length() > 16) {
            throw new com.falconx.market.error.MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        if (pricePrecision == null || pricePrecision < 0 || pricePrecision > 10
                || qtyPrecision == null || qtyPrecision < 0 || qtyPrecision > 10) {
            throw new com.falconx.market.error.MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        long id = idGenerator.nextId();
        mapper.insertSymbol(id, normalizedLpCode, normalizedSymbol, category, normalizedMarketCode,
                normalizedBase, normalizedQuote, pricePrecision, qtyPrecision);
        refreshQuoteMappingsAfterCommit(normalizedSymbol);
        log.info("market.symbol.source.created id={} lpCode={} symbol={} category={} marketCode={}",
                id, normalizedLpCode, normalizedSymbol, category, normalizedMarketCode);
        return mapper.selectSymbolById(id);
    }

    /**
     * Redis 读 SymbolSpec（GET /internal/v1/market/symbols/spec/{platformSymbol}）。
     * 缺失返回 null；trading-core 在 SYMBOL_SPEC_NOT_FOUND 拒单。
     */
    @Transactional(readOnly = true)
    public com.falconx.market.contract.SymbolSpec getSymbolSpec(String platformSymbol) {
        return symbolSpecRepository.findByPlatformSymbol(platformSymbol).orElse(null);
    }

    /**
     * 用户组可见性聚合视图（GET /internal/v1/market/symbols/group-visibility/grouped）。
     * <p>service 层先调 enlargeGroupConcatMaxLen 调大 SESSION 上限，避免 5000+ symbol 单组被截断。
     */
    @Transactional(readOnly = true)
    public GroupVisibilityGroupedListResult listGroupVisibilityGrouped(String groupCodeLike, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        String normalizedFilter = trimToNull(groupCodeLike);
        mapper.enlargeGroupConcatMaxLen();
        List<GroupVisibilityGroupedRecord> items = mapper.selectGroupVisibilityGrouped(normalizedFilter, offset, safeSize);
        long total = mapper.countGroupVisibilityGrouped(normalizedFilter);
        List<GroupVisibilityGroupedItem> mapped = items.stream()
                .map(r -> {
                    String csv = r.visibleSymbols() == null ? "" : r.visibleSymbols();
                    List<String> symbols = csv.isEmpty() ? List.of() : List.of(csv.split(","));
                    return new GroupVisibilityGroupedItem(
                            r.groupCode(),
                            r.visibleCount() == null ? 0 : r.visibleCount(),
                            symbols,
                            r.lastModifiedAt() == null ? null : r.lastModifiedAt().atOffset(java.time.ZoneOffset.UTC)
                    );
                })
                .toList();
        return new GroupVisibilityGroupedListResult(mapped, total, page, safeSize);
    }

    /**
     * ClickHouse 查一组 symbol 的最新 tick 时间（GET /internal/v1/market/symbols/last-tick?symbols=...）。
     * 没有 tick 的 symbol 不在返回 Map 中。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, java.time.OffsetDateTime> getLastTickBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return java.util.Map.of();
        }
        if (symbols.size() > 100) {
            throw new com.falconx.market.error.MarketBusinessException("99004", "symbols size exceeds limit");
        }
        return quoteTickMapper.selectLastTickBySymbols(symbols).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.falconx.market.repository.mapper.record.SymbolLastTickRecord::symbol,
                        com.falconx.market.repository.mapper.record.SymbolLastTickRecord::lastTickAt,
                        (a, b) -> a
                ));
    }

    public record GroupVisibilityGroupedItem(String groupCode,
                                              int visibleCount,
                                              List<String> visibleSymbols,
                                              java.time.OffsetDateTime lastModifiedAt) {
    }

    public record GroupVisibilityGroupedListResult(List<GroupVisibilityGroupedItem> items,
                                                   long total,
                                                   int page,
                                                   int size) {
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B + V15：mapping 系统级配置校验 ----

    private static final BigDecimal FEE_RATE_UPPER = new BigDecimal("0.05");

    private static Integer requireLeverage(Integer value) {
        if (value == null || value < 1 || value > 500) {
            throw new MarketBusinessException("90601", "Admin Symbol Invalid Leverage");
        }
        return value;
    }

    private static BigDecimal requireFeeRate(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(FEE_RATE_UPPER) > 0) {
            throw new MarketBusinessException("90602", "Admin Symbol Invalid Fee Rate");
        }
        return value;
    }

    private static BigDecimal requireNonNegativeSpread(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new MarketBusinessException("90603", "Admin Symbol Invalid Spread");
        }
        return value;
    }

    private static BigDecimal requireMinQty(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new MarketBusinessException("90604", "Admin Symbol Invalid Qty Range");
        }
        return value;
    }

    private static BigDecimal requireMaxQty(BigDecimal minQty, BigDecimal value) {
        if (value == null || minQty == null || value.compareTo(minQty) <= 0) {
            throw new MarketBusinessException("90604", "Admin Symbol Invalid Qty Range");
        }
        return value;
    }

    private static BigDecimal requireMinNotional(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new MarketBusinessException("90604", "Admin Symbol Invalid Qty Range");
        }
        return value;
    }

    private static Integer requireCategory(Integer value) {
        if (value == null || value < 1 || value > 8) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        return value;
    }

    private static String requireMarketCode(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > 32) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        return normalized;
    }

    private static Integer requirePrecision(Integer value) {
        if (value == null || value < 0 || value > 10) {
            throw new MarketBusinessException("90620", "Admin Symbol Source Invalid");
        }
        return value;
    }
}
