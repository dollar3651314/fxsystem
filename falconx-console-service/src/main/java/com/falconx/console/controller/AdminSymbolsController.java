package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminFeaturedListResponse;
import com.falconx.console.api.AdminFeaturedReplaceRequest;
import com.falconx.console.api.AdminMarketHolidayListResponse;
import com.falconx.console.api.AdminMarketHolidayUpsertRequest;
import com.falconx.console.api.AdminSwapRateResponse;
import com.falconx.console.api.AdminSwapRateUpdateRequest;
import com.falconx.console.api.AdminSymbolTradingRuleDeleteRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupBulkUpsertRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupCreateRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupDeleteRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupGroupedListResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupListResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupUpdateRequest;
import com.falconx.console.api.AdminSymbolGroupVisibilityBulkUpdateRequest;
import com.falconx.console.api.AdminSymbolGroupVisibilityGroupedListResponse;
import com.falconx.console.api.AdminSymbolGroupVisibilityListResponse;
import com.falconx.console.api.AdminSymbolGroupVisibilityUpdateRequest;
import com.falconx.console.api.AdminSymbolDetailResponse;
import com.falconx.console.api.AdminSymbolListResponse;
import com.falconx.console.api.AdminSymbolQuoteMappingCreateRequest;
import com.falconx.console.api.AdminSymbolQuoteMappingListResponse;
import com.falconx.console.api.AdminSymbolQuoteMappingUpdateRequest;
import com.falconx.console.api.AdminSymbolStatusRequest;
import com.falconx.console.api.AdminSymbolUpdateRequest;
import com.falconx.console.api.AdminTradingHoursResponse;
import com.falconx.console.api.AdminTradingExceptionUpsertRequest;
import com.falconx.console.api.AdminTradingSessionUpsertRequest;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.symbol.AdminFeaturedSymbolApplicationService;
import com.falconx.console.symbol.AdminGroupMarkupApplicationService;
import com.falconx.console.symbol.AdminSymbolApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
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
 * STAGE-2-SYMBOL: 行情品种管理 controller（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §6）。
 */
@RestController
@RequestMapping("/admin/symbols")
public class AdminSymbolsController {

    private static final Logger log = LoggerFactory.getLogger(AdminSymbolsController.class);

    private final AdminSymbolApplicationService applicationService;
    private final AdminGroupMarkupApplicationService groupMarkupService;
    private final AdminFeaturedSymbolApplicationService featuredService;

    public AdminSymbolsController(AdminSymbolApplicationService applicationService,
                                  AdminGroupMarkupApplicationService groupMarkupService,
                                  AdminFeaturedSymbolApplicationService featuredService) {
        this.applicationService = applicationService;
        this.groupMarkupService = groupMarkupService;
        this.featuredService = featuredService;
    }

    // ===== 跑马灯热门产品（FEATURED-TICKER）：单一全局有序列表，全量替换 =====

    @GetMapping("/featured")
    @RequiresPermission(value = "symbol:view", description = "查看跑马灯热门产品列表")
    public ApiResponse<AdminFeaturedListResponse> listFeatured() {
        log.info("admin.http.symbols.featured.list.received");
        return success(featuredService.list());
    }

    @PutMapping("/featured")
    @RequiresPermission(value = "symbol:featured:update", description = "配置跑马灯热门产品（全量替换）")
    public ApiResponse<AdminFeaturedListResponse> replaceFeatured(
            @Valid @RequestBody AdminFeaturedReplaceRequest request) {
        log.info("admin.http.symbols.featured.replace.received count={}",
                request.items() == null ? 0 : request.items().size());
        return success(featuredService.replace(request));
    }

    @GetMapping
    @RequiresPermission(value = "symbol:view", description = "查看 symbol 列表")
    public ApiResponse<AdminSymbolListResponse> list(
            @RequestParam(required = false) Integer category,
            @RequestParam(required = false) String marketCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String lpCode,
            @RequestParam(required = false) String symbolLike,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.list.received page={} size={}", page, size);
        return success(applicationService.listSymbols(category, marketCode, status, lpCode, symbolLike, page, size));
    }

    @GetMapping("/quote-mappings")
    @RequiresPermission(value = "symbol:view", description = "查看 symbol 报价映射")
    public ApiResponse<AdminSymbolQuoteMappingListResponse> listQuoteMappings(
            @RequestParam(required = false) String platformSymbolLike,
            @RequestParam(required = false) String sourceLpCode,
            @RequestParam(required = false) String sourceSymbolLike,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer lpSubscribeEnabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.quote-mappings.list.received page={} size={}", page, size);
        return success(applicationService.listQuoteMappings(
                platformSymbolLike, sourceLpCode, sourceSymbolLike, enabled, lpSubscribeEnabled, page, size));
    }

    @PostMapping("/quote-mappings")
    @RequiresPermission(value = "symbol:quote-mapping:update", description = "新建 symbol 报价映射（高风险）")
    public ApiResponse<AdminSymbolQuoteMappingListResponse.Item> createQuoteMapping(
            @Valid @RequestBody AdminSymbolQuoteMappingCreateRequest request) {
        log.info("admin.http.symbols.quote-mappings.create.received platformSymbol={}", request.platformSymbol());
        return success(applicationService.createQuoteMapping(request));
    }

    @PutMapping("/quote-mappings/{platformSymbol}")
    @RequiresPermission(value = "symbol:quote-mapping:update", description = "编辑 symbol 报价映射（高风险）")
    public ApiResponse<AdminSymbolQuoteMappingListResponse.Item> updateQuoteMapping(
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminSymbolQuoteMappingUpdateRequest request) {
        log.info("admin.http.symbols.quote-mappings.update.received platformSymbol={}", platformSymbol);
        return success(applicationService.updateQuoteMapping(platformSymbol, request));
    }

    @GetMapping("/group-visibility/grouped")
    @RequiresPermission(value = "symbol:view", description = "查看 symbol 用户组可见性聚合视图")
    public ApiResponse<AdminSymbolGroupVisibilityGroupedListResponse> listGroupVisibilityGrouped(
            @RequestParam(required = false) String groupCodeLike,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.group-visibility.grouped.received page={} size={}", page, size);
        return success(applicationService.listGroupVisibilityGrouped(groupCodeLike, page, size));
    }

    @GetMapping("/group-visibility")
    @RequiresPermission(value = "symbol:view", description = "查看 symbol 用户组可见性")
    public ApiResponse<AdminSymbolGroupVisibilityListResponse> listGroupVisibility(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String symbolLike,
            @RequestParam(required = false) Integer visible,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.group-visibility.list.received page={} size={}", page, size);
        return success(applicationService.listGroupVisibility(groupCode, symbolLike, visible, page, size));
    }

    @PutMapping("/group-visibility/{groupCode}/{symbol}")
    @RequiresPermission(value = "symbol:group-visibility:update", description = "编辑 symbol 用户组可见性（高风险）")
    public ApiResponse<AdminSymbolGroupVisibilityListResponse.Item> upsertGroupVisibility(
            @PathVariable String groupCode,
            @PathVariable String symbol,
            @Valid @RequestBody AdminSymbolGroupVisibilityUpdateRequest request) {
        log.info("admin.http.symbols.group-visibility.upsert.received groupCode={} symbol={} visible={}",
                groupCode, symbol, request.visible());
        return success(applicationService.upsertGroupVisibility(groupCode, symbol, request));
    }

    @PutMapping("/group-visibility/{groupCode}/bulk")
    @RequiresPermission(value = "symbol:group-visibility:update", description = "批量编辑 symbol 用户组可见性（高风险）")
    public ApiResponse<List<AdminSymbolGroupVisibilityListResponse.Item>> bulkUpsertGroupVisibility(
            @PathVariable String groupCode,
            @Valid @RequestBody AdminSymbolGroupVisibilityBulkUpdateRequest request) {
        log.info("admin.http.symbols.group-visibility.bulk-upsert.received groupCode={} count={} visible={}",
                groupCode, request.symbols().size(), request.visible());
        return success(applicationService.bulkUpsertGroupVisibility(groupCode, request));
    }

    // STAGE-12-GROUP-MARKUP：用户组加点 CRUD（管理端接口规范 §6.14）

    @GetMapping("/group-markup")
    @RequiresPermission(value = "symbol:view", description = "查看用户组加点列表")
    public ApiResponse<AdminSymbolGroupMarkupListResponse> listGroupMarkup(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String symbolLike,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.group-markup.list.received groupCode={} symbolLike={} enabled={} page={} size={}",
                groupCode, symbolLike, enabled, page, size);
        return success(groupMarkupService.list(groupCode, symbolLike, enabled, page, size));
    }

    @GetMapping("/group-markup/grouped")
    @RequiresPermission(value = "symbol:view", description = "查看用户组加点聚合视图")
    public ApiResponse<AdminSymbolGroupMarkupGroupedListResponse> listGroupMarkupGrouped() {
        log.info("admin.http.symbols.group-markup.grouped.received");
        return success(groupMarkupService.listGrouped());
    }

    @GetMapping("/group-markup/{groupCode}/{platformSymbol}")
    @RequiresPermission(value = "symbol:view", description = "查看用户组加点单条详情")
    public ApiResponse<AdminSymbolGroupMarkupListResponse.Item> detailGroupMarkup(
            @PathVariable String groupCode,
            @PathVariable String platformSymbol) {
        log.info("admin.http.symbols.group-markup.detail.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        return success(groupMarkupService.detail(groupCode, platformSymbol));
    }

    @PostMapping("/group-markup")
    @RequiresPermission(value = "symbol:group-markup:update", description = "新建用户组加点（高风险）")
    public ApiResponse<AdminSymbolGroupMarkupListResponse.Item> createGroupMarkup(
            @Valid @RequestBody AdminSymbolGroupMarkupCreateRequest request) {
        log.info("admin.http.symbols.group-markup.create.received groupCode={} platformSymbol={}",
                request.groupCode(), request.platformSymbol());
        return success(groupMarkupService.create(request));
    }

    @PutMapping("/group-markup/{groupCode}/{platformSymbol}")
    @RequiresPermission(value = "symbol:group-markup:update", description = "编辑用户组加点（高风险）")
    public ApiResponse<AdminSymbolGroupMarkupListResponse.Item> updateGroupMarkup(
            @PathVariable String groupCode,
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminSymbolGroupMarkupUpdateRequest request) {
        log.info("admin.http.symbols.group-markup.update.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        return success(groupMarkupService.update(groupCode, platformSymbol, request));
    }

    @PutMapping("/group-markup/{groupCode}/bulk")
    @RequiresPermission(value = "symbol:group-markup:update", description = "批量 upsert 用户组加点（高风险）")
    public ApiResponse<List<AdminSymbolGroupMarkupListResponse.Item>> bulkUpsertGroupMarkup(
            @PathVariable String groupCode,
            @Valid @RequestBody AdminSymbolGroupMarkupBulkUpsertRequest request) {
        log.info("admin.http.symbols.group-markup.bulk-upsert.received groupCode={} count={}",
                groupCode, request.items().size());
        return success(groupMarkupService.bulkUpsert(groupCode, request));
    }

    @DeleteMapping("/group-markup/{groupCode}/{platformSymbol}")
    @RequiresPermission(value = "symbol:group-markup:update", description = "删除用户组加点（高风险）")
    public ApiResponse<Void> deleteGroupMarkup(
            @PathVariable String groupCode,
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminSymbolGroupMarkupDeleteRequest request) {
        log.info("admin.http.symbols.group-markup.delete.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        groupMarkupService.delete(groupCode, platformSymbol, request.reason());
        return success(null);
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "symbol:view", description = "查看 symbol 详情")
    public ApiResponse<AdminSymbolDetailResponse> detail(@PathVariable long id) {
        log.info("admin.http.symbols.detail.received id={}", id);
        return success(applicationService.getSymbolDetail(id));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "symbol:source:update", description = "编辑 LP 源 symbol 元数据（高风险）")
    public ApiResponse<AdminSymbolListResponse.Item> update(@PathVariable long id,
                                                              @Valid @RequestBody AdminSymbolUpdateRequest request) {
        log.info("admin.http.symbols.update.received id={}", id);
        return success(applicationService.updateSymbolConfig(id, request));
    }

    @PostMapping
    @RequiresPermission(value = "symbol:source:create", description = "新建 LP 源 symbol（高风险）")
    public ApiResponse<AdminSymbolListResponse.Item> createSource(
            @Valid @RequestBody com.falconx.console.api.AdminSymbolSourceCreateRequest request) {
        log.info("admin.http.symbols.source.create.received symbol={}", request.symbol());
        return success(applicationService.createSource(request));
    }

    @PostMapping("/{id}/suspend")
    @RequiresPermission(value = "symbol:suspend", description = "暂停 symbol 交易（高风险）")
    public ApiResponse<AdminSymbolListResponse.Item> suspend(@PathVariable long id,
                                                               @Valid @RequestBody AdminSymbolStatusRequest request) {
        log.info("admin.http.symbols.suspend.received id={}", id);
        return success(applicationService.suspendSymbol(id, request.reason()));
    }

    @PostMapping("/{id}/resume")
    @RequiresPermission(value = "symbol:suspend", description = "恢复 symbol 交易（共用 suspend 权限）")
    public ApiResponse<AdminSymbolListResponse.Item> resume(@PathVariable long id,
                                                              @Valid @RequestBody AdminSymbolStatusRequest request) {
        log.info("admin.http.symbols.resume.received id={}", id);
        return success(applicationService.resumeSymbol(id, request.reason()));
    }

    @GetMapping("/{platformSymbol}/swap-rate")
    @RequiresPermission(value = "symbol:view", description = "查看隔夜费率")
    public ApiResponse<AdminSwapRateResponse> getSwapRate(@PathVariable String platformSymbol) {
        log.info("admin.http.symbols.swap-rate.get.received platformSymbol={}", platformSymbol);
        return success(applicationService.getSwapRate(platformSymbol));
    }

    @PutMapping("/{platformSymbol}/swap-rate")
    @RequiresPermission(value = "symbol:swap-rate:update", description = "更新隔夜费率（高风险）")
    public ApiResponse<AdminSymbolDetailResponse.SwapRateItem> putSwapRate(
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminSwapRateUpdateRequest request) {
        log.info("admin.http.symbols.swap-rate.put.received platformSymbol={} effFrom={}",
                platformSymbol, request.effectiveFrom());
        return success(applicationService.upsertSwapRate(platformSymbol, request));
    }

    @GetMapping("/{platformSymbol}/trading-hours")
    @RequiresPermission(value = "symbol:view", description = "查看交易时段")
    public ApiResponse<AdminTradingHoursResponse> getTradingHours(@PathVariable String platformSymbol) {
        log.info("admin.http.symbols.trading-hours.received platformSymbol={}", platformSymbol);
        return success(applicationService.getTradingHours(platformSymbol));
    }

    @PostMapping("/{platformSymbol}/trading-hours/sessions")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "新建 symbol 交易时段（高风险）")
    public ApiResponse<AdminSymbolDetailResponse.TradingSessionItem> createTradingSession(
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminTradingSessionUpsertRequest request) {
        log.info("admin.http.symbols.trading-hours.session.create.received platformSymbol={}", platformSymbol);
        return success(applicationService.createTradingSession(platformSymbol, request));
    }

    @PutMapping("/{platformSymbol}/trading-hours/sessions/{sessionId}")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "编辑 symbol 交易时段（高风险）")
    public ApiResponse<AdminSymbolDetailResponse.TradingSessionItem> updateTradingSession(
            @PathVariable String platformSymbol,
            @PathVariable long sessionId,
            @Valid @RequestBody AdminTradingSessionUpsertRequest request) {
        log.info("admin.http.symbols.trading-hours.session.update.received platformSymbol={} sessionId={}",
                platformSymbol, sessionId);
        return success(applicationService.updateTradingSession(platformSymbol, sessionId, request));
    }

    @DeleteMapping("/{platformSymbol}/trading-hours/sessions/{sessionId}")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "删除 symbol 交易时段（高风险）")
    public ApiResponse<Void> deleteTradingSession(
            @PathVariable String platformSymbol,
            @PathVariable long sessionId,
            @Valid @RequestBody AdminSymbolTradingRuleDeleteRequest request) {
        log.info("admin.http.symbols.trading-hours.session.delete.received platformSymbol={} sessionId={}",
                platformSymbol, sessionId);
        applicationService.deleteTradingSession(platformSymbol, sessionId, request);
        return success(null);
    }

    @PostMapping("/{platformSymbol}/trading-hours/exceptions")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "新建 symbol 特殊交易日（高风险）")
    public ApiResponse<AdminTradingHoursResponse.ExceptionItem> createTradingException(
            @PathVariable String platformSymbol,
            @Valid @RequestBody AdminTradingExceptionUpsertRequest request) {
        log.info("admin.http.symbols.trading-hours.exception.create.received platformSymbol={}", platformSymbol);
        return success(applicationService.createTradingException(platformSymbol, request));
    }

    @PutMapping("/{platformSymbol}/trading-hours/exceptions/{exceptionId}")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "编辑 symbol 特殊交易日（高风险）")
    public ApiResponse<AdminTradingHoursResponse.ExceptionItem> updateTradingException(
            @PathVariable String platformSymbol,
            @PathVariable long exceptionId,
            @Valid @RequestBody AdminTradingExceptionUpsertRequest request) {
        log.info("admin.http.symbols.trading-hours.exception.update.received platformSymbol={} exceptionId={}",
                platformSymbol, exceptionId);
        return success(applicationService.updateTradingException(platformSymbol, exceptionId, request));
    }

    @DeleteMapping("/{platformSymbol}/trading-hours/exceptions/{exceptionId}")
    @RequiresPermission(value = "symbol:trading-hours:update", description = "删除 symbol 特殊交易日（高风险）")
    public ApiResponse<Void> deleteTradingException(
            @PathVariable String platformSymbol,
            @PathVariable long exceptionId,
            @Valid @RequestBody AdminSymbolTradingRuleDeleteRequest request) {
        log.info("admin.http.symbols.trading-hours.exception.delete.received platformSymbol={} exceptionId={}",
                platformSymbol, exceptionId);
        applicationService.deleteTradingException(platformSymbol, exceptionId, request);
        return success(null);
    }

    @GetMapping("/market-holidays")
    @RequiresPermission(value = "symbol:view", description = "查看市场节假日配置")
    public ApiResponse<AdminMarketHolidayListResponse> listMarketHolidays(
            @RequestParam(required = false) String marketCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.symbols.market-holidays.list.received marketCode={} page={} size={}",
                marketCode, page, size);
        return success(applicationService.listMarketHolidays(marketCode, page, size));
    }

    @PostMapping("/market-holidays")
    @RequiresPermission(value = "symbol:holiday:update", description = "新建市场节假日配置（高风险）")
    public ApiResponse<AdminMarketHolidayListResponse.Item> createMarketHoliday(
            @Valid @RequestBody AdminMarketHolidayUpsertRequest request) {
        log.info("admin.http.symbols.market-holidays.create.received marketCode={} holidayDate={}",
                request.marketCode(), request.holidayDate());
        return success(applicationService.createMarketHoliday(request));
    }

    @PutMapping("/market-holidays/{holidayId}")
    @RequiresPermission(value = "symbol:holiday:update", description = "编辑市场节假日配置（高风险）")
    public ApiResponse<AdminMarketHolidayListResponse.Item> updateMarketHoliday(
            @PathVariable long holidayId,
            @Valid @RequestBody AdminMarketHolidayUpsertRequest request) {
        log.info("admin.http.symbols.market-holidays.update.received holidayId={}", holidayId);
        return success(applicationService.updateMarketHoliday(holidayId, request));
    }

    @DeleteMapping("/market-holidays/{holidayId}")
    @RequiresPermission(value = "symbol:holiday:update", description = "删除市场节假日配置（高风险）")
    public ApiResponse<Void> deleteMarketHoliday(
            @PathVariable long holidayId,
            @Valid @RequestBody AdminSymbolTradingRuleDeleteRequest request) {
        log.info("admin.http.symbols.market-holidays.delete.received holidayId={}", holidayId);
        applicationService.deleteMarketHoliday(holidayId, request);
        return success(null);
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
