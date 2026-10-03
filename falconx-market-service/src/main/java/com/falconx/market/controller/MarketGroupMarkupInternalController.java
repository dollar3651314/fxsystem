package com.falconx.market.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.application.MarketGroupMarkupAdminApplicationService;
import com.falconx.market.application.MarketGroupMarkupAdminApplicationService.BulkItem;
import com.falconx.market.application.MarketGroupMarkupAdminApplicationService.GroupedListResult;
import com.falconx.market.application.MarketGroupMarkupAdminApplicationService.ListResult;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketGroupMarkupListResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * STAGE-12-GROUP-MARKUP: 用户组加点 internal RPC controller。
 *
 * <p>承担两类调用方：
 * <ul>
 *   <li>console-service：管理端 CRUD（透传到该 controller）</li>
 *   <li>trading-core-service：启动全量拉取 + 30s 增量刷新</li>
 * </ul>
 *
 * <p>路径前缀 {@code /internal/v1/market/symbols/group-markup}；
 * filter 校验 {@code X-Internal-Token}，与 {@link MarketSymbolAdminInternalController} 同。
 */
@RestController
@RequestMapping("/internal/v1/market/symbols/group-markup")
public class MarketGroupMarkupInternalController {

    private static final Logger log = LoggerFactory.getLogger(MarketGroupMarkupInternalController.class);

    private final MarketGroupMarkupAdminApplicationService applicationService;

    public MarketGroupMarkupInternalController(MarketGroupMarkupAdminApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 全量列表（trading-core 启动加载 + console 列表查询）。
     */
    @GetMapping
    public ApiResponse<ListResult> list(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String symbolLike,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("market.internal.group-markup.list.received groupCode={} symbolLike={} enabled={} page={} size={}",
                groupCode, symbolLike, enabled, page, size);
        return success(applicationService.list(groupCode, symbolLike, enabled, page, size));
    }

    /**
     * 按组聚合（console grouped 视图）。
     */
    @GetMapping("/grouped")
    public ApiResponse<GroupedListResult> listGrouped() {
        log.info("market.internal.group-markup.grouped.received");
        return success(applicationService.listGrouped());
    }

    /**
     * trading-core 启动加载用：全部 enabled=1 配置。
     */
    @GetMapping("/all-enabled")
    public ApiResponse<MarketGroupMarkupListResponse> allEnabled() {
        log.info("market.internal.group-markup.all-enabled.received");
        List<MarketGroupMarkupItem> items = applicationService.findAllEnabled();
        return success(new MarketGroupMarkupListResponse(items, OffsetDateTime.now()));
    }

    /**
     * trading-core 增量刷新：自指定时刻起变更过的全部配置（含禁用）。
     *
     * @param since epoch millis；trading-core 用上一次响应的 serverTime 推算
     */
    @GetMapping("/changes")
    public ApiResponse<MarketGroupMarkupListResponse> changesSince(@RequestParam("since") long since) {
        OffsetDateTime sinceTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(since), ZoneOffset.UTC);
        log.info("market.internal.group-markup.changes.received since={}", sinceTime);
        List<MarketGroupMarkupItem> items = applicationService.findChangedSince(sinceTime);
        return success(new MarketGroupMarkupListResponse(items, OffsetDateTime.now()));
    }

    /**
     * 单点查询（console 详情）。
     */
    @GetMapping("/{groupCode}/{platformSymbol}")
    public ApiResponse<MarketGroupMarkupItem> detail(@PathVariable String groupCode,
                                                     @PathVariable String platformSymbol) {
        log.info("market.internal.group-markup.detail.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        return success(applicationService.findByPk(groupCode, platformSymbol));
    }

    /**
     * 新建（console POST）。
     */
    @PostMapping
    public ApiResponse<MarketGroupMarkupItem> create(@Valid @RequestBody UpsertRequest request) {
        log.info("market.internal.group-markup.create.received groupCode={} platformSymbol={}",
                request.groupCode(), request.platformSymbol());
        return success(applicationService.create(
                request.groupCode(),
                request.platformSymbol(),
                request.bidExtra(),
                request.askExtra(),
                request.enabled()));
    }

    /**
     * 编辑（console PUT）。
     */
    @PutMapping("/{groupCode}/{platformSymbol}")
    public ApiResponse<MarketGroupMarkupItem> update(
            @PathVariable String groupCode,
            @PathVariable String platformSymbol,
            @Valid @RequestBody UpdateRequest request) {
        log.info("market.internal.group-markup.update.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        return success(applicationService.update(
                groupCode, platformSymbol,
                request.bidExtra(), request.askExtra(), request.enabled()));
    }

    /**
     * 批量 upsert（console PUT bulk）。
     */
    @PutMapping("/{groupCode}/bulk")
    public ApiResponse<List<MarketGroupMarkupItem>> bulkUpsert(
            @PathVariable String groupCode,
            @Valid @RequestBody BulkUpsertRequest request) {
        log.info("market.internal.group-markup.bulk-upsert.received groupCode={} count={}",
                groupCode, request.items() == null ? 0 : request.items().size());
        List<BulkItem> bulkItems = request.items().stream()
                .map(it -> new BulkItem(it.platformSymbol(), it.bidExtra(), it.askExtra(), it.enabled()))
                .toList();
        return success(applicationService.bulkUpsert(groupCode, bulkItems));
    }

    /**
     * 删除（console DELETE）。
     */
    @DeleteMapping("/{groupCode}/{platformSymbol}")
    public ApiResponse<Void> delete(@PathVariable String groupCode,
                                    @PathVariable String platformSymbol) {
        log.info("market.internal.group-markup.delete.received groupCode={} platformSymbol={}",
                groupCode, platformSymbol);
        applicationService.delete(groupCode, platformSymbol);
        return success(null);
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    public record UpsertRequest(
            @jakarta.validation.constraints.NotBlank @Size(max = 64) String groupCode,
            @jakarta.validation.constraints.NotBlank @Size(max = 32) String platformSymbol,
            @NotNull BigDecimal bidExtra,
            @NotNull BigDecimal askExtra,
            @NotNull Integer enabled
    ) {
    }

    public record UpdateRequest(
            @NotNull BigDecimal bidExtra,
            @NotNull BigDecimal askExtra,
            @NotNull Integer enabled
    ) {
    }

    public record BulkUpsertRequest(
            @NotNull @NotEmpty List<@Valid BulkUpsertItem> items
    ) {
    }

    public record BulkUpsertItem(
            @jakarta.validation.constraints.NotBlank @Size(max = 32) String platformSymbol,
            @NotNull BigDecimal bidExtra,
            @NotNull BigDecimal askExtra,
            @NotNull Integer enabled
    ) {
    }
}
