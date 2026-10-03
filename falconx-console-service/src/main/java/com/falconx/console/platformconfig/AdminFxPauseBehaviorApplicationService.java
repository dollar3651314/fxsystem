package com.falconx.console.platformconfig;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.FxPauseBehaviorView;
import com.falconx.console.api.UpdateFxPauseBehaviorRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AuditSnapshotHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-14D3b Task3：FX_PAUSED 行为（按商品类目 1-8）管理端编排。
 *
 * <p>与 {@link AdminPlatformConfigApplicationService}（Task2）同模式：所有调用经
 * {@link InternalRpcClient} 透传到 D3a 已冻结的 trading-core internal RPC
 * {@code /internal/v1/trading/console/fx-pause-behavior}（console → gateway → trading-core；
 * gateway 注入 {@code X-Internal-Token}，client 注入 {@code X-Admin-User-Id} + {@code X-Trace-Id}）。
 * 写操作（PUT）通过 {@link AuditSnapshotHolder} 落 before/after 快照，{@code OperationAuditAspect} 写
 * {@code t_admin_operation_log}。trading 越界（category 1-8 / 字段校验）返回 {@code 99004} → 翻译 90951。
 *
 * <p>边界：console 只透传不直写 trading 业务表；{@code reason} 仅本地审计，不进 RPC body。
 */
@Service
public class AdminFxPauseBehaviorApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminFxPauseBehaviorApplicationService.class);

    private static final String FX_PAUSE_BEHAVIOR_PATH = "/internal/v1/trading/console/fx-pause-behavior";

    /** trading-core Bean Validation 越界统一返回的下游错误码。 */
    private static final String DOWNSTREAM_VALIDATION_CODE = "99004";

    private static final ParameterizedTypeReference<ApiResponse<List<FxPauseBehaviorView>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;

    public AdminFxPauseBehaviorApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    /** 读 FX_PAUSED 行为：GET fx-pause-behavior，返回 8 类目行（Jackson 按字段名直接反序列化 record）。 */
    public List<FxPauseBehaviorView> listBehaviors() {
        List<FxPauseBehaviorView> data = internalRpcClient.get(FX_PAUSE_BEHAVIOR_PATH, LIST_TYPE);
        return data == null ? List.of() : data;
    }

    /** 更新某类目 FX_PAUSED 行为（高危）：verifyReason + 审计快照 + 透传 PUT（不含 reason）；99004 → 90951。 */
    public void updateBehavior(int category, UpdateFxPauseBehaviorRequest request) {
        verifyReason(request.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowOpen", request.allowOpen());
        body.put("allowClose", request.allowClose());
        body.put("allowLiquidation", request.allowLiquidation());
        AuditSnapshotHolder.set(null, Map.of(
                "category", category,
                "allowOpen", request.allowOpen(),
                "allowClose", request.allowClose(),
                "allowLiquidation", request.allowLiquidation()));
        try {
            internalRpcClient.put(FX_PAUSE_BEHAVIOR_PATH + "/" + category, body, VOID_TYPE);
            log.info("admin.fx-pause-behavior.updated category={} allowOpen={} allowClose={} allowLiquidation={}",
                    category, request.allowOpen(), request.allowClose(), request.allowLiquidation());
        } catch (InternalRpcException ex) {
            translate(ex, AdminErrorCode.ADMIN_FX_PAUSE_BEHAVIOR_INVALID);
            throw ex;
        }
    }

    // ---- helpers ----

    private void verifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED);
        }
    }

    /** trading-core 99004（Bean Validation 越界）→ 指定 console AdminErrorCode；其他下游错误透传。 */
    private void translate(InternalRpcException ex, AdminErrorCode invalidCode) {
        if (DOWNSTREAM_VALIDATION_CODE.equals(ex.getDownstreamCode())) {
            throw new AdminBusinessException(invalidCode);
        }
    }
}
