package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminDepositListResponse;
import com.falconx.console.deposit.AdminDepositApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-DEPOSIT R9：入金记录管理 console REST。
 *
 * <p>路径：{@code /admin/deposits}，详见 docs/api/管理端接口规范.md §9。纯只读，无写操作。
 */
@RestController
@RequestMapping("/admin/deposits")
public class AdminDepositController {

    private static final Logger log = LoggerFactory.getLogger(AdminDepositController.class);

    private final AdminDepositApplicationService depositService;

    public AdminDepositController(AdminDepositApplicationService depositService) {
        this.depositService = depositService;
    }

    @GetMapping
    @RequiresPermission(value = "deposit:view", description = "查看入金记录列表")
    public ApiResponse<AdminDepositListResponse> listDeposits(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromDetectedAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toDetectedAt,
            @RequestParam(defaultValue = "false") boolean onlyOrphan,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.deposits.list.received page={} size={} onlyOrphan={}", page, size, onlyOrphan);
        return success(depositService.listDeposits(userId, chain, token, status, fromDetectedAt, toDetectedAt, onlyOrphan, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "deposit:view", description = "查看入金记录详情")
    public ApiResponse<AdminDepositListResponse.Item> getDeposit(@PathVariable long id) {
        log.info("admin.http.deposits.detail.received id={}", id);
        return success(depositService.getDeposit(id));
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
