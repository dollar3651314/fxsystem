package com.falconx.market.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.application.MarketSymbolAdminApplicationService;
import com.falconx.market.application.MarketSymbolAdminApplicationService.DetailResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.GroupVisibilityGroupedListResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.GroupVisibilityListResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.HolidayListResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.ListResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.QuoteMappingListResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.SwapRateResult;
import com.falconx.market.application.MarketSymbolAdminApplicationService.TradingHoursResult;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.repository.mapper.record.MarketSwapRateRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolGroupVisibilityAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionExceptionRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-SYMBOL: market admin internal RPC controller（被 console 通过 gateway 调用）。
 *
 * <p>所有路径前置 {@link com.falconx.market.security.MarketInternalApiTokenFilter} filter
 * 校验 X-Internal-Token；filter 通过后才到达本 controller。
 */
@RestController
@RequestMapping("/internal/v1/market/symbols")
public class MarketSymbolAdminInternalController {

    private static final Logger log = LoggerFactory.getLogger(MarketSymbolAdminInternalController.class);

    private final MarketSymbolAdminApplicationService applicationService;

    public MarketSymbolAdminInternalController(MarketSymbolAdminApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ApiResponse<MarketSymbolAdminRecord> createSource(
            @Valid @RequestBody CreateSourceRequest request) {
        log.info("market.internal.symbols.source.create.received symbol={}", request.symbol());
        return success(applicationService.createSource(
                request.lpCode(),
                request.symbol(),
                request.category(),
                request.marketCode(),
                request.baseCurrency(),
                request.quoteCurrency(),
                request.pricePrecision(),
                request.qtyPrecision()
        ));
    }

    @GetMapping("/spec/{platformSymbol}")
    public ApiResponse<SymbolSpec> getSpec(@PathVariable String platformSymbol) {
        log.info("market.internal.symbols.spec.received platformSymbol={}", platformSymbol);
        return success(applicationService.getSymbolSpec(platformSymbol));
    }

    @GetMapping("/last-tick")
    public ApiResponse<java.util.Map<String, java.time.OffsetDateTime>> getLastTick(
            @RequestParam("symbols") java.util.List<String> symbols) {
        log.info("market.internal.symbols.last-tick.received count={}", symbols == null ? 0 : symbols.size());
        return success(applicationService.getLastTickBySymbols(symbols));
    }

    @GetMapping("/group-visibility/grouped")
    public ApiResponse<GroupVisibilityGroupedListResult> listGroupVisibilityGrouped(
            @RequestParam(required = false) String groupCodeLike,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.symbols.group-visibility.grouped.received page={} size={}", page, size);
        return success(applicationService.listGroupVisibilityGrouped(groupCodeLike, page, size));
    }

    @GetMapping
    public ApiResponse<ListResult> list(@RequestParam(required = false) Integer category,
                                          @RequestParam(required = false) String marketCode,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String lpCode,
                                          @RequestParam(required = false) String symbolLike,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.symbols.list.received page={} size={}", page, size);
        return success(applicationService.listSymbols(category, marketCode, status, lpCode, symbolLike, page, size));
    }

    @GetMapping("/quote-mappings")
    public ApiResponse<QuoteMappingListResult> listQuoteMappings(
            @RequestParam(required = false) String platformSymbolLike,
            @RequestParam(required = false) String sourceLpCode,
            @RequestParam(required = false) String sourceSymbolLike,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer lpSubscribeEnabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.symbols.quote-mappings.list.received page={} size={}", page, size);
        return success(applicationService.listQuoteMappings(
                platformSymbolLike, sourceLpCode, sourceSymbolLike, enabled, lpSubscribeEnabled, page, size));
    }

    @PostMapping("/quote-mappings")
    public ApiResponse<MarketSymbolQuoteMappingAdminRecord> createQuoteMapping(
            @Valid @RequestBody UpsertQuoteMappingRequest request) {
        log.info("market.internal.symbols.quote-mappings.create.received platformSymbol={} sourceLpCode={} sourceSymbol={}",
                request.platformSymbol(), request.sourceLpCode(), request.sourceSymbol());
        return success(applicationService.createQuoteMapping(
                request.platformSymbol(),
                request.sourceProvider(),
                request.sourceLpCode(),
                request.sourceSymbol(),
                request.category(),
                request.marketCode(),
                request.priceMultiplier(),
                request.bidAdjustment(),
                request.askAdjustment(),
                request.enabled(),
                request.lpSubscribeEnabled(),
                request.maxLeverage(),
                request.takerFeeRate(),
                request.spread(),
                request.minQty(),
                request.maxQty(),
                request.minNotional(),
                request.pricePrecision(),
                request.qtyPrecision()
        ));
    }

    @PutMapping("/quote-mappings/{platformSymbol}")
    public ApiResponse<MarketSymbolQuoteMappingAdminRecord> updateQuoteMapping(
            @PathVariable String platformSymbol,
            @Valid @RequestBody UpdateQuoteMappingRequest request) {
        log.info("market.internal.symbols.quote-mappings.update.received platformSymbol={}", platformSymbol);
        return success(applicationService.updateQuoteMapping(
                platformSymbol,
                request.sourceProvider(),
                request.sourceLpCode(),
                request.sourceSymbol(),
                request.category(),
                request.marketCode(),
                request.priceMultiplier(),
                request.bidAdjustment(),
                request.askAdjustment(),
                request.enabled(),
                request.lpSubscribeEnabled(),
                request.maxLeverage(),
                request.takerFeeRate(),
                request.spread(),
                request.minQty(),
                request.maxQty(),
                request.minNotional(),
                request.pricePrecision(),
                request.qtyPrecision()
        ));
    }

    @GetMapping("/group-visibility")
    public ApiResponse<GroupVisibilityListResult> listGroupVisibility(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String symbolLike,
            @RequestParam(required = false) Integer visible,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.symbols.group-visibility.list.received page={} size={}", page, size);
        return success(applicationService.listGroupVisibility(groupCode, symbolLike, visible, page, size));
    }

    @PutMapping("/group-visibility/{groupCode}/{symbol}")
    public ApiResponse<MarketSymbolGroupVisibilityAdminRecord> upsertGroupVisibility(
            @PathVariable String groupCode,
            @PathVariable String symbol,
            @Valid @RequestBody UpsertGroupVisibilityRequest request) {
        log.info("market.internal.symbols.group-visibility.upsert.received groupCode={} symbol={} visible={}",
                groupCode, symbol, request.visible());
        return success(applicationService.upsertGroupVisibility(groupCode, symbol, request.visible()));
    }

    @PutMapping("/group-visibility/{groupCode}/bulk")
    public ApiResponse<List<MarketSymbolGroupVisibilityAdminRecord>> bulkUpsertGroupVisibility(
            @PathVariable String groupCode,
            @Valid @RequestBody BulkUpsertGroupVisibilityRequest request) {
        log.info("market.internal.symbols.group-visibility.bulk-upsert.received groupCode={} count={} visible={}",
                groupCode, request.symbols() == null ? 0 : request.symbols().size(), request.visible());
        return success(applicationService.bulkUpsertGroupVisibility(groupCode, request.symbols(), request.visible()));
    }

    @GetMapping("/{id}")
    public ApiResponse<DetailResult> detail(@PathVariable long id) {
        return success(applicationService.getSymbolDetail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<MarketSymbolAdminRecord> update(@PathVariable long id,
                                                        @Valid @RequestBody UpdateSymbolRequest request) {
        log.info("market.internal.symbols.update.received id={}", id);
        return success(applicationService.updateSymbolConfig(
                id, request.category(), request.marketCode(),
                request.pricePrecision(), request.qtyPrecision()));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<MarketSymbolAdminRecord> setStatus(@PathVariable long id,
                                                            @Valid @RequestBody SetStatusRequest request) {
        int newStatus = "TRADING".equals(request.status()) ? 1 : 2;
        return success(applicationService.setStatus(id, newStatus));
    }

    @GetMapping("/{platformSymbol}/swap-rate")
    public ApiResponse<SwapRateResult> getSwapRate(@PathVariable String platformSymbol) {
        return success(applicationService.getSwapRate(platformSymbol));
    }

    @PutMapping("/{platformSymbol}/swap-rate")
    public ApiResponse<MarketSwapRateRecord> putSwapRate(@PathVariable String platformSymbol,
                                                           @Valid @RequestBody UpsertSwapRateRequest request) {
        log.info("market.internal.swap-rate.put.received platformSymbol={} effFrom={}",
                platformSymbol, request.effectiveFrom());
        return success(applicationService.upsertSwapRate(
                platformSymbol, request.longRate(), request.shortRate(), request.rolloverTime(), request.effectiveFrom()));
    }

    @GetMapping("/{platformSymbol}/trading-hours")
    public ApiResponse<TradingHoursResult> getTradingHours(@PathVariable String platformSymbol) {
        return success(applicationService.getTradingHours(platformSymbol));
    }

    @PostMapping("/{platformSymbol}/trading-hours/sessions")
    public ApiResponse<MarketTradingSessionRecord> createTradingSession(
            @PathVariable String platformSymbol,
            @Valid @RequestBody UpsertTradingSessionRequest request) {
        log.info("market.internal.trading-hours.session.create.received platformSymbol={} dayOfWeek={} sessionNo={}",
                platformSymbol, request.dayOfWeek(), request.sessionNo());
        return success(applicationService.upsertTradingSession(
                platformSymbol,
                null,
                request.dayOfWeek(),
                request.sessionNo(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.enabled(),
                request.effectiveFrom(),
                request.effectiveTo()
        ));
    }

    @PutMapping("/{platformSymbol}/trading-hours/sessions/{sessionId}")
    public ApiResponse<MarketTradingSessionRecord> updateTradingSession(
            @PathVariable String platformSymbol,
            @PathVariable long sessionId,
            @Valid @RequestBody UpsertTradingSessionRequest request) {
        log.info("market.internal.trading-hours.session.update.received platformSymbol={} sessionId={}",
                platformSymbol, sessionId);
        return success(applicationService.upsertTradingSession(
                platformSymbol,
                sessionId,
                request.dayOfWeek(),
                request.sessionNo(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.enabled(),
                request.effectiveFrom(),
                request.effectiveTo()
        ));
    }

    @DeleteMapping("/{platformSymbol}/trading-hours/sessions/{sessionId}")
    public ApiResponse<Void> deleteTradingSession(@PathVariable String platformSymbol,
                                                  @PathVariable long sessionId) {
        log.info("market.internal.trading-hours.session.delete.received platformSymbol={} sessionId={}",
                platformSymbol, sessionId);
        applicationService.deleteTradingSession(platformSymbol, sessionId);
        return success(null);
    }

    @PostMapping("/{platformSymbol}/trading-hours/exceptions")
    public ApiResponse<MarketTradingSessionExceptionRecord> createTradingException(
            @PathVariable String platformSymbol,
            @Valid @RequestBody UpsertTradingExceptionRequest request) {
        log.info("market.internal.trading-hours.exception.create.received platformSymbol={} tradeDate={} type={}",
                platformSymbol, request.tradeDate(), request.exceptionType());
        return success(applicationService.upsertTradingException(
                platformSymbol,
                null,
                request.tradeDate(),
                request.exceptionType(),
                request.sessionNo(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.reason()
        ));
    }

    @PutMapping("/{platformSymbol}/trading-hours/exceptions/{exceptionId}")
    public ApiResponse<MarketTradingSessionExceptionRecord> updateTradingException(
            @PathVariable String platformSymbol,
            @PathVariable long exceptionId,
            @Valid @RequestBody UpsertTradingExceptionRequest request) {
        log.info("market.internal.trading-hours.exception.update.received platformSymbol={} exceptionId={}",
                platformSymbol, exceptionId);
        return success(applicationService.upsertTradingException(
                platformSymbol,
                exceptionId,
                request.tradeDate(),
                request.exceptionType(),
                request.sessionNo(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.reason()
        ));
    }

    @DeleteMapping("/{platformSymbol}/trading-hours/exceptions/{exceptionId}")
    public ApiResponse<Void> deleteTradingException(@PathVariable String platformSymbol,
                                                    @PathVariable long exceptionId) {
        log.info("market.internal.trading-hours.exception.delete.received platformSymbol={} exceptionId={}",
                platformSymbol, exceptionId);
        applicationService.deleteTradingException(platformSymbol, exceptionId);
        return success(null);
    }

    @GetMapping("/market-holidays")
    public ApiResponse<HolidayListResult> listMarketHolidays(
            @RequestParam(required = false) String marketCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.market-holidays.list.received marketCode={} page={} size={}",
                marketCode, page, size);
        return success(applicationService.listMarketHolidays(marketCode, page, size));
    }

    @PostMapping("/market-holidays")
    public ApiResponse<MarketTradingHolidayRecord> createMarketHoliday(
            @Valid @RequestBody UpsertMarketHolidayRequest request) {
        log.info("market.internal.market-holidays.create.received marketCode={} holidayDate={}",
                request.marketCode(), request.holidayDate());
        return success(applicationService.upsertMarketHoliday(
                null,
                request.marketCode(),
                request.holidayDate(),
                request.holidayType(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.holidayName(),
                request.countryCode()
        ));
    }

    @PutMapping("/market-holidays/{holidayId}")
    public ApiResponse<MarketTradingHolidayRecord> updateMarketHoliday(
            @PathVariable long holidayId,
            @Valid @RequestBody UpsertMarketHolidayRequest request) {
        log.info("market.internal.market-holidays.update.received holidayId={}", holidayId);
        return success(applicationService.upsertMarketHoliday(
                holidayId,
                request.marketCode(),
                request.holidayDate(),
                request.holidayType(),
                request.openTime(),
                request.closeTime(),
                request.timezone(),
                request.holidayName(),
                request.countryCode()
        ));
    }

    @DeleteMapping("/market-holidays/{holidayId}")
    public ApiResponse<Void> deleteMarketHoliday(@PathVariable long holidayId) {
        log.info("market.internal.market-holidays.delete.received holidayId={}", holidayId);
        applicationService.deleteMarketHoliday(holidayId);
        return success(null);
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    /**
     * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 字段裁剪：原 maxLeverage/takerFeeRate/spread/min_qty/max_qty/min_notional 已下沉到 mapping
     * 接口（{@code PUT /admin/symbols/quote-mappings/{platformSymbol}}）；本接口仅处理 source 元数据编辑。
     */
    public record UpdateSymbolRequest(
            Integer category,
            String marketCode,
            Integer pricePrecision,
            Integer qtyPrecision
    ) {
    }

    public record SetStatusRequest(@NotNull @Pattern(regexp = "TRADING|SUSPENDED") String status) {
    }

    public record UpsertSwapRateRequest(
            @NotNull BigDecimal longRate,
            @NotNull BigDecimal shortRate,
            LocalTime rolloverTime,
            @NotNull LocalDate effectiveFrom
    ) {
    }

    public record UpsertTradingSessionRequest(
            @NotNull Integer dayOfWeek,
            @NotNull Integer sessionNo,
            @NotNull LocalTime openTime,
            @NotNull LocalTime closeTime,
            @NotBlank @Size(max = 32) String timezone,
            @NotNull Integer enabled,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
    }

    public record UpsertTradingExceptionRequest(
            @NotNull LocalDate tradeDate,
            @NotNull Integer exceptionType,
            Integer sessionNo,
            LocalTime openTime,
            LocalTime closeTime,
            @NotBlank @Size(max = 32) String timezone,
            @Size(max = 128) String reason
    ) {
    }

    public record UpsertMarketHolidayRequest(
            @NotBlank @Size(max = 32) String marketCode,
            @NotNull LocalDate holidayDate,
            @NotNull Integer holidayType,
            LocalTime openTime,
            LocalTime closeTime,
            @NotBlank @Size(max = 32) String timezone,
            @NotBlank @Size(max = 128) String holidayName,
            @Size(max = 16) String countryCode
    ) {
    }

    /**
     * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B + V15 字段集扩展：
     * mapping 创建必填 category / marketCode / 6 交易参数 / 系统级 precision。
     */
    public record UpsertQuoteMappingRequest(
            @NotBlank @Size(max = 32) String platformSymbol,
            @Size(max = 32) String sourceProvider,
            @Size(max = 32) String sourceLpCode,
            @NotBlank @Size(max = 32) String sourceSymbol,
            @NotNull Integer category,
            @NotBlank @Size(max = 32) String marketCode,
            @NotNull BigDecimal priceMultiplier,
            BigDecimal bidAdjustment,
            BigDecimal askAdjustment,
            @NotNull Integer enabled,
            @NotNull Integer lpSubscribeEnabled,
            @NotNull Integer maxLeverage,
            @NotNull BigDecimal takerFeeRate,
            @NotNull BigDecimal spread,
            @NotNull BigDecimal minQty,
            @NotNull BigDecimal maxQty,
            @NotNull BigDecimal minNotional,
            @NotNull Integer pricePrecision,
            @NotNull Integer qtyPrecision
    ) {
    }

    public record UpdateQuoteMappingRequest(
            @Size(max = 32) String sourceProvider,
            @Size(max = 32) String sourceLpCode,
            @Size(max = 32) String sourceSymbol,
            Integer category,
            @Size(max = 32) String marketCode,
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
            Integer qtyPrecision
    ) {
    }

    public record UpsertGroupVisibilityRequest(@NotNull Integer visible) {
    }

    public record BulkUpsertGroupVisibilityRequest(
            @NotNull List<@NotBlank @Size(max = 32) String> symbols,
            @NotNull Integer visible
    ) {
    }

    /**
     * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：新建 LP 源 symbol 请求体。
     */
    public record CreateSourceRequest(
            @Size(max = 32) String lpCode,
            @NotBlank @Size(max = 32) String symbol,
            @NotNull Integer category,
            @NotBlank @Size(max = 32) String marketCode,
            @NotBlank @Size(max = 16) String baseCurrency,
            @NotBlank @Size(max = 16) String quoteCurrency,
            @NotNull Integer pricePrecision,
            @NotNull Integer qtyPrecision
    ) {
    }
}
