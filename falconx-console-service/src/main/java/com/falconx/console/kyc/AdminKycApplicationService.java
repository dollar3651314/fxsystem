package com.falconx.console.kyc;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminKycDetailResponse;
import com.falconx.console.api.AdminKycItem;
import com.falconx.console.api.AdminKycListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-6-KYC Phase 2：KYC 审核管理编排。
 * 转发到 identity /internal/v1/identity/kyc*；
 * 错误码 10044/10045/10046 翻译为 console AdminErrorCode。
 */
@Service
public class AdminKycApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminKycApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminKycListResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminKycDetailResponse>> DETAIL_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminKycItem>> ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminKycApplicationService(InternalRpcClient internalRpcClient,
                                      AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.userInfoEnricher = userInfoEnricher;
    }

    public AdminKycListResponse list(String status, Long userId, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/identity/kyc?page=")
                .append(page).append("&size=").append(size);
        if (status != null && !status.isBlank()) query.append("&status=").append(status);
        if (userId != null) query.append("&userId=").append(userId);
        try {
            AdminKycListResponse resp = internalRpcClient.get(query.toString(), LIST_TYPE);
            List<AdminKycItem> items = userInfoEnricher.enrich(
                    resp.items(), it -> Long.parseLong(it.userId()),
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new AdminKycListResponse(resp.page(), resp.pageSize(), resp.total(), items);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminKycDetailResponse detail(long submissionId) {
        try {
            return internalRpcClient.get("/internal/v1/identity/kyc/" + submissionId, DETAIL_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminKycItem approve(long submissionId) {
        try {
            AdminKycItem item = internalRpcClient.post(
                    "/internal/v1/identity/kyc/" + submissionId + "/approve",
                    Map.of(),
                    ITEM_TYPE);
            log.info("admin.kyc.approve.completed submissionId={} userId={}", submissionId, item.userId());
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("status", "PENDING", "submissionId", submissionId),
                    Map.of(
                            "status", item.status() == null ? "" : item.status(),
                            "level", item.level(),
                            "action", "approve"
                    )
            );
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminKycItem reject(long submissionId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_KYC_REJECT_REASON_REQUIRED);
        }
        try {
            AdminKycItem item = internalRpcClient.post(
                    "/internal/v1/identity/kyc/" + submissionId + "/reject",
                    Map.of("reason", reason),
                    ITEM_TYPE);
            log.info("admin.kyc.reject.completed submissionId={} userId={}", submissionId, item.userId());
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("status", "PENDING", "submissionId", submissionId),
                    Map.of(
                            "status", item.status() == null ? "" : item.status(),
                            "level", item.level(),
                            "action", "reject",
                            "reason", reason
                    )
            );
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    private void translateError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "10044" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_KYC_NOT_FOUND);
            case "10045" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_KYC_NOT_PENDING);
            case "10046" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_KYC_REJECT_REASON_REQUIRED);
            default -> { /* 透传其他错误 */ }
        }
    }
}
