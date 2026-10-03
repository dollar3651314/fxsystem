package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.api.AdminTierCreateRequest;
import com.falconx.trading.api.AdminTierListResponse;
import com.falconx.trading.api.AdminTierUpdateRequest;
import com.falconx.trading.application.TradingTierAdminApplicationService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14C2 Task 4：杠杆/MM 档位（tier）CRUD internal RPC。
 *
 * <p>路径前缀 {@code /internal/v1/trading/console/tier}（master §7.4），经
 * {@code TradingInternalApiTokenFilter}（X-Internal-Token + X-Admin-User-Id）鉴权，
 * 由 console-service（Task 8）透传。@Transactional 与业务校验在
 * {@link TradingTierAdminApplicationService}。
 *
 * <ul>
 *   <li>{@code GET /tier} 分页列表（含软删行供 admin 查看）</li>
 *   <li>{@code POST /tier} 新建（区间重叠 90932 / CHECK 90931）</li>
 *   <li>{@code PUT /tier/{id}} 编辑（not found 90930）</li>
 *   <li>{@code DELETE /tier/{id}} 软删（enabled=0，not found 90930）</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/trading/console")
public class AdminInternalTradingTierController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingTierController.class);

    private final TradingTierAdminApplicationService tierAdminService;

    public AdminInternalTradingTierController(TradingTierAdminApplicationService tierAdminService) {
        this.tierAdminService = tierAdminService;
    }

    @GetMapping("/tier")
    public ApiResponse<AdminTierListResponse> listTiers(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String groupCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String s = symbol == null || symbol.isBlank() ? null : symbol;
        String g = groupCode == null || groupCode.isBlank() ? null : groupCode;
        return success(tierAdminService.listTiers(s, g, safePage, safeSize));
    }

    @PostMapping("/tier")
    public ApiResponse<AdminTierListResponse.Item> createTier(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminTierCreateRequest request) {
        log.info("trading.internal.tier.create.received symbol={} groupCode={} tierNo={} adminUserId={}",
                request.symbol(), request.groupCode(), request.tierNo(), adminUserId);
        return success(tierAdminService.createTier(
                request.symbol(), request.groupCode(), request.tierNo(),
                request.notionalLower(), request.notionalUpper(),
                request.maxLeverage(), request.mmRate()));
    }

    @PutMapping("/tier/{id}")
    public ApiResponse<AdminTierListResponse.Item> updateTier(
            @PathVariable long id,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminTierUpdateRequest request) {
        log.info("trading.internal.tier.update.received id={} tierNo={} adminUserId={}",
                id, request.tierNo(), adminUserId);
        return success(tierAdminService.updateTier(
                id, request.tierNo(), request.notionalLower(), request.notionalUpper(),
                request.maxLeverage(), request.mmRate()));
    }

    @DeleteMapping("/tier/{id}")
    public ApiResponse<Void> deleteTier(
            @PathVariable long id,
            @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.internal.tier.delete.received id={} adminUserId={}", id, adminUserId);
        tierAdminService.deleteTier(id);
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "0",
                "success",
                data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }
}
