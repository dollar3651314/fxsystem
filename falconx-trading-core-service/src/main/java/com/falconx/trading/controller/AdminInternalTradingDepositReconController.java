package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.api.AdminTradingDepositReconListResponse;
import com.falconx.trading.application.AdminTradingDepositReconApplicationService;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-11-OBS-RECON §11.4：trading-core admin 入金对账查询 RPC。
 *
 * <p>路径 {@code /internal/v1/trading/console/deposits}（与既有 admin internal RPC 同 prefix）。
 * 鉴权由 {@code TradingInternalApiTokenFilter} 校验 {@code X-Internal-Token + X-Admin-User-Id}。
 */
@RestController
@RequestMapping("/internal/v1/trading/console")
public class AdminInternalTradingDepositReconController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingDepositReconController.class);

    private final AdminTradingDepositReconApplicationService reconService;

    public AdminInternalTradingDepositReconController(AdminTradingDepositReconApplicationService reconService) {
        this.reconService = reconService;
    }

    @GetMapping("/deposits")
    public ApiResponse<AdminTradingDepositReconListResponse> listForRecon(
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) OffsetDateTime fromCreatedAt,
            @RequestParam(required = false) OffsetDateTime toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "200") int size) {
        log.info("trading.admin.recon.deposits.list.received chain={} token={} status={} page={} size={}",
                chain, token, status, page, size);
        return success(reconService.list(chain, token, status, fromCreatedAt, toCreatedAt, page, size));
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
