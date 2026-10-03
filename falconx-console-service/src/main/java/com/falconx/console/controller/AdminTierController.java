package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminTierCreateRequest;
import com.falconx.console.api.AdminTierListResponse;
import com.falconx.console.api.AdminTierUpdateRequest;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.tier.AdminTierApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14C2 Task 8 R9：杠杆/MM 档位（tier）管理端 REST。
 *
 * <p>路径 {@code /admin/trading/tiers}（master §7.4），RBAC 权限点 {@code tier:view} / {@code tier:edit}
 * 与 V12 seed 一致。写操作（POST/PUT/DELETE）高危，{@link AdminTierApplicationService} 落审计快照，
 * {@code OperationAuditAspect} 自动写 {@code t_admin_operation_log}。所有调用透传 trading-core
 * internal RPC，console 不直写 trading 业务表。
 */
@RestController
@RequestMapping("/admin")
public class AdminTierController {

    private static final Logger log = LoggerFactory.getLogger(AdminTierController.class);

    private final AdminTierApplicationService tierService;

    public AdminTierController(AdminTierApplicationService tierService) {
        this.tierService = tierService;
    }

    @GetMapping("/trading/tiers")
    @RequiresPermission(value = "tier:view", description = "查看杠杆/MM 档位列表")
    public ApiResponse<AdminTierListResponse> listTiers(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String groupCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.tiers.list.received symbol={} groupCode={} page={} size={}",
                symbol, groupCode, page, size);
        return success(tierService.listTiers(symbol, groupCode, page, size));
    }

    @PostMapping("/trading/tiers")
    @RequiresPermission(value = "tier:edit", description = "新建杠杆/MM 档位（高危）")
    public ApiResponse<AdminTierListResponse.Item> createTier(
            @Valid @RequestBody AdminTierCreateRequest request) {
        log.info("admin.http.tiers.create.received symbol={} groupCode={} tierNo={}",
                request.symbol(), request.groupCode(), request.tierNo());
        return success(tierService.createTier(request));
    }

    @PutMapping("/trading/tiers/{id}")
    @RequiresPermission(value = "tier:edit", description = "编辑杠杆/MM 档位（高危）")
    public ApiResponse<AdminTierListResponse.Item> updateTier(
            @PathVariable long id,
            @Valid @RequestBody AdminTierUpdateRequest request) {
        log.info("admin.http.tiers.update.received id={} tierNo={}", id, request.tierNo());
        return success(tierService.updateTier(id, request));
    }

    @DeleteMapping("/trading/tiers/{id}")
    @RequiresPermission(value = "tier:edit", description = "删除杠杆/MM 档位（高危）")
    public ApiResponse<Void> deleteTier(@PathVariable long id) {
        log.info("admin.http.tiers.delete.received id={}", id);
        tierService.deleteTier(id);
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
