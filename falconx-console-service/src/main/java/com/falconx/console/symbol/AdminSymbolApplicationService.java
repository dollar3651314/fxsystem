package com.falconx.console.symbol;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminMarketHolidayListResponse;
import com.falconx.console.api.AdminMarketHolidayUpsertRequest;
import com.falconx.console.api.AdminSwapRateResponse;
import com.falconx.console.api.AdminSwapRateUpdateRequest;
import com.falconx.console.api.AdminSymbolTradingRuleDeleteRequest;
import com.falconx.console.api.AdminSymbolGroupVisibilityBulkUpdateRequest;
import com.falconx.console.api.AdminSymbolGroupVisibilityGroupedListResponse;
import com.falconx.console.api.AdminSymbolGroupVisibilityListResponse;
import com.falconx.console.api.AdminSymbolGroupVisibilityUpdateRequest;
import com.falconx.console.api.AdminSymbolDetailResponse;
import com.falconx.console.api.AdminSymbolListResponse;
import com.falconx.console.api.AdminSymbolQuoteMappingCreateRequest;
import com.falconx.console.api.AdminSymbolQuoteMappingListResponse;
import com.falconx.console.api.AdminSymbolQuoteMappingUpdateRequest;
import com.falconx.console.api.AdminSymbolUpdateRequest;
import com.falconx.console.api.AdminTradingHoursResponse;
import com.falconx.console.api.AdminTradingExceptionUpsertRequest;
import com.falconx.console.api.AdminTradingSessionUpsertRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-SYMBOL: 行情品种 ApplicationService（通过 internal RPC 调 market）。
 *
 * <p>错误码翻译：market 服务直接抛 90600-90618（与 console AdminErrorCode 共用号段），
 * 故无须重新映射，只需把 InternalRpcException 翻译成 AdminBusinessException。
 */
@Service
public class AdminSymbolApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminSymbolApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolListResponse>> LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolDetailResponse>> DETAIL_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolListResponse.Item>> SYMBOL_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSwapRateResponse>> SWAP_RATE_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolDetailResponse.SwapRateItem>> SWAP_RATE_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingHoursResponse>> TRADING_HOURS_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolDetailResponse.TradingSessionItem>> TRADING_SESSION_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingHoursResponse.ExceptionItem>> TRADING_EXCEPTION_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminMarketHolidayListResponse>> HOLIDAY_LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminMarketHolidayListResponse.Item>> HOLIDAY_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolQuoteMappingListResponse>> MAPPING_LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolQuoteMappingListResponse.Item>> MAPPING_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupVisibilityListResponse>> VISIBILITY_LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupVisibilityListResponse.Item>> VISIBILITY_ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<List<AdminSymbolGroupVisibilityListResponse.Item>>> VISIBILITY_ITEMS_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupVisibilityGroupedListResponse>> VISIBILITY_GROUPED_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final InternalRpcClient internalRpcClient;

    public AdminSymbolApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    public AdminSymbolListResponse listSymbols(Integer category, String marketCode, Integer status, String lpCode,
                                                 String symbolLike, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols?page=")
                .append(page).append("&size=").append(size);
        if (category != null) query.append("&category=").append(category);
        if (marketCode != null && !marketCode.isBlank()) query.append("&marketCode=").append(marketCode);
        if (status != null) query.append("&status=").append(status);
        if (lpCode != null && !lpCode.isBlank()) query.append("&lpCode=").append(lpCode);
        if (symbolLike != null && !symbolLike.isBlank()) query.append("&symbolLike=").append(symbolLike);
        try {
            return internalRpcClient.get(query.toString(), LIST_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolDetailResponse getSymbolDetail(long id) {
        try {
            return internalRpcClient.get("/internal/v1/market/symbols/" + id, DETAIL_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    /**
     * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：字段集裁剪，只编辑 source 元数据。
     * 交易参数（leverage/fee/spread/qty）的编辑改走 mapping CRUD。
     */
    public AdminSymbolListResponse.Item updateSymbolConfig(long id, AdminSymbolUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", request.category());
        body.put("marketCode", request.marketCode());
        body.put("pricePrecision", request.pricePrecision());
        body.put("qtyPrecision", request.qtyPrecision());
        try {
            AdminSymbolListResponse.Item updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/" + id, body, SYMBOL_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.update.completed id={} reason={}", id, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    /**
     * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：新建 LP 源 symbol。
     * 调 market POST /internal/v1/market/symbols；market 写入后自动追加 LP 订阅。
     */
    public AdminSymbolListResponse.Item createSource(com.falconx.console.api.AdminSymbolSourceCreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lpCode", request.lpCode());
        body.put("symbol", request.symbol());
        body.put("category", request.category());
        body.put("marketCode", request.marketCode());
        body.put("baseCurrency", request.baseCurrency());
        body.put("quoteCurrency", request.quoteCurrency());
        body.put("pricePrecision", request.pricePrecision());
        body.put("qtyPrecision", request.qtyPrecision());
        try {
            AdminSymbolListResponse.Item created = internalRpcClient.post(
                    "/internal/v1/market/symbols", body, SYMBOL_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.source.created symbol={} reason={}", request.symbol(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolListResponse.Item suspendSymbol(long id, String reason) {
        return setSymbolStatus(id, "SUSPENDED", reason);
    }

    public AdminSymbolListResponse.Item resumeSymbol(long id, String reason) {
        return setSymbolStatus(id, "TRADING", reason);
    }

    private AdminSymbolListResponse.Item setSymbolStatus(long id, String status, String reason) {
        try {
            AdminSymbolListResponse.Item updated = internalRpcClient.post(
                    "/internal/v1/market/symbols/" + id + "/status",
                    Map.of("status", status), SYMBOL_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.status.changed id={} status={} reason={}", id, status, reason);
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSwapRateResponse getSwapRate(String platformSymbol) {
        try {
            return internalRpcClient.get(
                    "/internal/v1/market/symbols/" + platformSymbol + "/swap-rate", SWAP_RATE_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolDetailResponse.SwapRateItem upsertSwapRate(String platformSymbol, AdminSwapRateUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("longRate", request.longRate());
        body.put("shortRate", request.shortRate());
        body.put("rolloverTime", request.rolloverTime() == null ? null : request.rolloverTime().toString());
        body.put("effectiveFrom", request.effectiveFrom().toString());
        try {
            AdminSymbolDetailResponse.SwapRateItem inserted = internalRpcClient.put(
                    "/internal/v1/market/symbols/" + platformSymbol + "/swap-rate",
                    body, SWAP_RATE_ITEM_RESPONSE_TYPE);
            log.info("admin.swap-rate.upsert.completed platformSymbol={} effFrom={} reason={}",
                    platformSymbol, request.effectiveFrom(), request.reason());
            return inserted;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminTradingHoursResponse getTradingHours(String platformSymbol) {
        try {
            return internalRpcClient.get(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours", TRADING_HOURS_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolDetailResponse.TradingSessionItem createTradingSession(
            String platformSymbol,
            AdminTradingSessionUpsertRequest request) {
        try {
            AdminSymbolDetailResponse.TradingSessionItem created = internalRpcClient.post(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/sessions",
                    tradingSessionBody(request),
                    TRADING_SESSION_ITEM_RESPONSE_TYPE);
            log.info("admin.trading-session.created platformSymbol={} sessionId={} reason={}",
                    platformSymbol, created.id(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolDetailResponse.TradingSessionItem updateTradingSession(
            String platformSymbol,
            long sessionId,
            AdminTradingSessionUpsertRequest request) {
        try {
            AdminSymbolDetailResponse.TradingSessionItem updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/sessions/" + sessionId,
                    tradingSessionBody(request),
                    TRADING_SESSION_ITEM_RESPONSE_TYPE);
            log.info("admin.trading-session.updated platformSymbol={} sessionId={} reason={}",
                    platformSymbol, sessionId, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public void deleteTradingSession(String platformSymbol, long sessionId, AdminSymbolTradingRuleDeleteRequest request) {
        try {
            internalRpcClient.delete(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/sessions/" + sessionId,
                    VOID_RESPONSE_TYPE);
            log.info("admin.trading-session.deleted platformSymbol={} sessionId={} reason={}",
                    platformSymbol, sessionId, request.reason());
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminTradingHoursResponse.ExceptionItem createTradingException(
            String platformSymbol,
            AdminTradingExceptionUpsertRequest request) {
        try {
            AdminTradingHoursResponse.ExceptionItem created = internalRpcClient.post(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/exceptions",
                    tradingExceptionBody(request),
                    TRADING_EXCEPTION_ITEM_RESPONSE_TYPE);
            log.info("admin.trading-exception.created platformSymbol={} exceptionId={} reason={}",
                    platformSymbol, created.id(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminTradingHoursResponse.ExceptionItem updateTradingException(
            String platformSymbol,
            long exceptionId,
            AdminTradingExceptionUpsertRequest request) {
        try {
            AdminTradingHoursResponse.ExceptionItem updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/exceptions/" + exceptionId,
                    tradingExceptionBody(request),
                    TRADING_EXCEPTION_ITEM_RESPONSE_TYPE);
            log.info("admin.trading-exception.updated platformSymbol={} exceptionId={} reason={}",
                    platformSymbol, exceptionId, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public void deleteTradingException(String platformSymbol, long exceptionId, AdminSymbolTradingRuleDeleteRequest request) {
        try {
            internalRpcClient.delete(
                    "/internal/v1/market/symbols/" + platformSymbol + "/trading-hours/exceptions/" + exceptionId,
                    VOID_RESPONSE_TYPE);
            log.info("admin.trading-exception.deleted platformSymbol={} exceptionId={} reason={}",
                    platformSymbol, exceptionId, request.reason());
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminMarketHolidayListResponse listMarketHolidays(String marketCode, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols/market-holidays?page=")
                .append(page).append("&size=").append(size);
        if (marketCode != null && !marketCode.isBlank()) {
            query.append("&marketCode=").append(marketCode);
        }
        try {
            return internalRpcClient.get(query.toString(), HOLIDAY_LIST_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminMarketHolidayListResponse.Item createMarketHoliday(AdminMarketHolidayUpsertRequest request) {
        try {
            AdminMarketHolidayListResponse.Item created = internalRpcClient.post(
                    "/internal/v1/market/symbols/market-holidays",
                    marketHolidayBody(request),
                    HOLIDAY_ITEM_RESPONSE_TYPE);
            log.info("admin.market-holiday.created marketCode={} holidayDate={} reason={}",
                    request.marketCode(), request.holidayDate(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminMarketHolidayListResponse.Item updateMarketHoliday(long holidayId, AdminMarketHolidayUpsertRequest request) {
        try {
            AdminMarketHolidayListResponse.Item updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/market-holidays/" + holidayId,
                    marketHolidayBody(request),
                    HOLIDAY_ITEM_RESPONSE_TYPE);
            log.info("admin.market-holiday.updated holidayId={} reason={}", holidayId, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public void deleteMarketHoliday(long holidayId, AdminSymbolTradingRuleDeleteRequest request) {
        try {
            internalRpcClient.delete(
                    "/internal/v1/market/symbols/market-holidays/" + holidayId,
                    VOID_RESPONSE_TYPE);
            log.info("admin.market-holiday.deleted holidayId={} reason={}", holidayId, request.reason());
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    private static Map<String, Object> tradingSessionBody(AdminTradingSessionUpsertRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dayOfWeek", request.dayOfWeek());
        body.put("sessionNo", request.sessionNo());
        body.put("openTime", request.openTime().toString());
        body.put("closeTime", request.closeTime().toString());
        body.put("timezone", request.timezone());
        body.put("enabled", request.enabled());
        body.put("effectiveFrom", request.effectiveFrom().toString());
        body.put("effectiveTo", request.effectiveTo() == null ? null : request.effectiveTo().toString());
        return body;
    }

    private static Map<String, Object> tradingExceptionBody(AdminTradingExceptionUpsertRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tradeDate", request.tradeDate().toString());
        body.put("exceptionType", request.exceptionType());
        body.put("sessionNo", request.sessionNo());
        body.put("openTime", request.openTime() == null ? null : request.openTime().toString());
        body.put("closeTime", request.closeTime() == null ? null : request.closeTime().toString());
        body.put("timezone", request.timezone());
        body.put("reason", request.ruleReason());
        return body;
    }

    private static Map<String, Object> marketHolidayBody(AdminMarketHolidayUpsertRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marketCode", request.marketCode());
        body.put("holidayDate", request.holidayDate().toString());
        body.put("holidayType", request.holidayType());
        body.put("openTime", request.openTime() == null ? null : request.openTime().toString());
        body.put("closeTime", request.closeTime() == null ? null : request.closeTime().toString());
        body.put("timezone", request.timezone());
        body.put("holidayName", request.holidayName());
        body.put("countryCode", request.countryCode());
        return body;
    }

    public AdminSymbolQuoteMappingListResponse listQuoteMappings(String platformSymbolLike, String sourceLpCode, String sourceSymbolLike,
                                                                  Integer enabled, Integer lpSubscribeEnabled,
                                                                  int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols/quote-mappings?page=")
                .append(page).append("&size=").append(size);
        if (platformSymbolLike != null && !platformSymbolLike.isBlank()) {
            query.append("&platformSymbolLike=").append(platformSymbolLike);
        }
        if (sourceLpCode != null && !sourceLpCode.isBlank()) {
            query.append("&sourceLpCode=").append(sourceLpCode);
        }
        if (sourceSymbolLike != null && !sourceSymbolLike.isBlank()) {
            query.append("&sourceSymbolLike=").append(sourceSymbolLike);
        }
        if (enabled != null) query.append("&enabled=").append(enabled);
        if (lpSubscribeEnabled != null) query.append("&lpSubscribeEnabled=").append(lpSubscribeEnabled);
        try {
            return internalRpcClient.get(query.toString(), MAPPING_LIST_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolQuoteMappingListResponse.Item createQuoteMapping(AdminSymbolQuoteMappingCreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platformSymbol", request.platformSymbol());
        body.put("sourceProvider", request.sourceProvider());
        body.put("sourceLpCode", request.sourceLpCode());
        body.put("sourceSymbol", request.sourceSymbol());
        body.put("category", request.category());
        body.put("marketCode", request.marketCode());
        body.put("priceMultiplier", request.priceMultiplier());
        body.put("bidAdjustment", request.bidAdjustment());
        body.put("askAdjustment", request.askAdjustment());
        body.put("enabled", request.enabled());
        body.put("lpSubscribeEnabled", request.lpSubscribeEnabled());
        body.put("maxLeverage", request.maxLeverage());
        body.put("takerFeeRate", request.takerFeeRate());
        body.put("spread", request.spread());
        body.put("minQty", request.minQty());
        body.put("maxQty", request.maxQty());
        body.put("minNotional", request.minNotional());
        body.put("pricePrecision", request.pricePrecision());
        body.put("qtyPrecision", request.qtyPrecision());
        try {
            AdminSymbolQuoteMappingListResponse.Item created = internalRpcClient.post(
                    "/internal/v1/market/symbols/quote-mappings", body, MAPPING_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.quote-mapping.created platformSymbol={} reason={}",
                    request.platformSymbol(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolQuoteMappingListResponse.Item updateQuoteMapping(
            String platformSymbol,
            AdminSymbolQuoteMappingUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceProvider", request.sourceProvider());
        body.put("sourceLpCode", request.sourceLpCode());
        body.put("sourceSymbol", request.sourceSymbol());
        body.put("category", request.category());
        body.put("marketCode", request.marketCode());
        body.put("priceMultiplier", request.priceMultiplier());
        body.put("bidAdjustment", request.bidAdjustment());
        body.put("askAdjustment", request.askAdjustment());
        body.put("enabled", request.enabled());
        body.put("lpSubscribeEnabled", request.lpSubscribeEnabled());
        body.put("maxLeverage", request.maxLeverage());
        body.put("takerFeeRate", request.takerFeeRate());
        body.put("spread", request.spread());
        body.put("minQty", request.minQty());
        body.put("maxQty", request.maxQty());
        body.put("minNotional", request.minNotional());
        body.put("pricePrecision", request.pricePrecision());
        body.put("qtyPrecision", request.qtyPrecision());
        try {
            AdminSymbolQuoteMappingListResponse.Item updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/quote-mappings/" + platformSymbol,
                    body,
                    MAPPING_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.quote-mapping.updated platformSymbol={} reason={}",
                    platformSymbol, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupVisibilityGroupedListResponse listGroupVisibilityGrouped(
            String groupCodeLike, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols/group-visibility/grouped?page=")
                .append(page).append("&size=").append(size);
        if (groupCodeLike != null && !groupCodeLike.isBlank()) {
            query.append("&groupCodeLike=").append(groupCodeLike);
        }
        try {
            return internalRpcClient.get(query.toString(), VISIBILITY_GROUPED_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupVisibilityListResponse listGroupVisibility(String groupCode, String symbolLike,
                                                                       Integer visible, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols/group-visibility?page=")
                .append(page).append("&size=").append(size);
        if (groupCode != null && !groupCode.isBlank()) query.append("&groupCode=").append(groupCode);
        if (symbolLike != null && !symbolLike.isBlank()) query.append("&symbolLike=").append(symbolLike);
        if (visible != null) query.append("&visible=").append(visible);
        try {
            return internalRpcClient.get(query.toString(), VISIBILITY_LIST_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupVisibilityListResponse.Item upsertGroupVisibility(
            String groupCode,
            String symbol,
            AdminSymbolGroupVisibilityUpdateRequest request) {
        try {
            AdminSymbolGroupVisibilityListResponse.Item updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/group-visibility/" + groupCode + "/" + symbol,
                    Map.of("visible", request.visible()),
                    VISIBILITY_ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.group-visibility.upserted groupCode={} symbol={} visible={} reason={}",
                    groupCode, symbol, request.visible(), request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public List<AdminSymbolGroupVisibilityListResponse.Item> bulkUpsertGroupVisibility(
            String groupCode,
            AdminSymbolGroupVisibilityBulkUpdateRequest request) {
        try {
            List<AdminSymbolGroupVisibilityListResponse.Item> updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/group-visibility/" + groupCode + "/bulk",
                    Map.of("symbols", request.symbols(), "visible", request.visible()),
                    VISIBILITY_ITEMS_RESPONSE_TYPE);
            log.info("admin.symbol.group-visibility.bulk-upserted groupCode={} count={} visible={} reason={}",
                    groupCode, request.symbols().size(), request.visible(), request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    /**
     * market 服务 90600-90618 错误码与 console AdminErrorCode 共用号段，1:1 翻译。
     */
    private void translateMarketError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90600" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_NOT_FOUND);
            case "90601" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_INVALID_LEVERAGE);
            case "90602" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_INVALID_FEE_RATE);
            case "90603" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_INVALID_SPREAD);
            case "90604" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_INVALID_QTY_RANGE);
            case "90605" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_ALREADY_SUSPENDED);
            case "90606" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_ALREADY_TRADING);
            case "90610" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_SWAP_RATE_OVERLAP_DATE);
            case "90611" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_SWAP_RATE_DATE_INVALID);
            case "90612" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_SWAP_RATE_OUT_OF_RANGE);
            case "90613" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_NOT_FOUND);
            case "90614" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_DUPLICATE);
            case "90615" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_SOURCE_NOT_FOUND);
            case "90616" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_INVALID_PRICE_RULE);
            case "90618" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_GROUP_VISIBILITY_INVALID);
            case "90619" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_SOURCE_DUPLICATE);
            case "90620" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_SOURCE_INVALID);
            case "90621" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_TRADING_SCHEDULE_INVALID);
            case "90622" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_TRADING_SCHEDULE_NOT_FOUND);
            case "90623" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_HOLIDAY_INVALID);
            case "90624" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_HOLIDAY_NOT_FOUND);
            default -> { /* 其他错误透传 */ }
        }
    }
}
