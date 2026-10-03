package com.falconx.console.wallet;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminWalletProvisionDlqItem;
import com.falconx.console.api.AdminWalletProvisionDlqListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配 DLQ 管理编排。
 *
 * <p>转发到 wallet-service /internal/v1/wallet/console/provision-dlq*；
 * 错误码 90860/90861 翻译为 console AdminErrorCode；retry 是高危写操作走 OperationAuditAspect。
 */
@Service
public class AdminWalletProvisionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminWalletProvisionApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminWalletProvisionDlqListResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminWalletProvisionDlqItem>> ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;

    public AdminWalletProvisionApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    public AdminWalletProvisionDlqListResponse list(Integer status, Long userId, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/wallet/console/provision-dlq?page=")
                .append(page).append("&size=").append(size);
        if (status != null) query.append("&status=").append(status);
        if (userId != null) query.append("&userId=").append(userId);
        try {
            return internalRpcClient.get(query.toString(), LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminWalletProvisionDlqItem retry(long id, String reason) {
        verifyReason(reason);
        try {
            AdminWalletProvisionDlqItem item = internalRpcClient.post(
                    "/internal/v1/wallet/console/provision-dlq/" + id + "/retry",
                    Map.of("reason", reason),
                    ITEM_TYPE);
            log.info("admin.wallet.provision-dlq.retry.completed id={} status={}", id, item.status());
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    private void verifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_WALLET_PROVISION_REASON_REQUIRED);
        }
    }

    private void translateError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90860" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND);
            case "90861" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED);
            default -> { /* 透传其他错误 */ }
        }
    }
}
