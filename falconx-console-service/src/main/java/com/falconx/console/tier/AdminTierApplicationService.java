package com.falconx.console.tier;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminTierCreateRequest;
import com.falconx.console.api.AdminTierListResponse;
import com.falconx.console.api.AdminTierUpdateRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AuditSnapshotHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-14C2 Task 8 R9：杠杆/MM 档位（tier）管理端编排。
 *
 * <p>所有调用经 {@link InternalRpcClient} 透传到 trading-core
 * {@code /internal/v1/trading/console/tier*}（console → gateway → trading-core；
 * gateway 注入 {@code X-Internal-Token}，client 注入 {@code X-Admin-User-Id} + {@code X-Trace-Id}）。
 * 错误码 90930-90932 翻译为 {@link AdminErrorCode}；高危写操作（POST/PUT/DELETE）
 * 通过 {@link AuditSnapshotHolder} 落 before/after 快照，{@code OperationAuditAspect} 写
 * {@code t_admin_operation_log}。
 *
 * <p>R9 边界：console 只透传，不直写 trading 业务表；{@code reason} 仅本地审计，不进 trading RPC body。
 */
@Service
public class AdminTierApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminTierApplicationService.class);

    private static final String TIER_RPC_BASE = "/internal/v1/trading/console/tier";

    private static final ParameterizedTypeReference<ApiResponse<AdminTierListResponse>> TIER_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminTierListResponse.Item>> TIER_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;

    public AdminTierApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    /** 分页查询档位列表（透传 symbol/groupCode/page/size）。 */
    public AdminTierListResponse listTiers(String symbol, String groupCode, int page, int size) {
        StringBuilder query = new StringBuilder(TIER_RPC_BASE)
                .append("?page=").append(page).append("&size=").append(size);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (groupCode != null && !groupCode.isBlank()) query.append("&groupCode=").append(groupCode);
        try {
            return internalRpcClient.get(query.toString(), TIER_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateTierError(ex);
            throw ex;
        }
    }

    /** 新建档位（高危）：写前置审计快照（before=null），透传到 trading（不含 reason）。 */
    public AdminTierListResponse.Item createTier(AdminTierCreateRequest request) {
        verifyReason(request.reason());
        Map<String, Object> body = tradingBody(
                request.symbol(), request.groupCode(), request.tierNo(),
                request.notionalLower(), request.notionalUpper(),
                request.maxLeverage(), request.mmRate());
        AuditSnapshotHolder.set(null, auditAfter("create", request.reason(), body));
        try {
            AdminTierListResponse.Item item = internalRpcClient.post(TIER_RPC_BASE, body, TIER_ITEM_TYPE);
            log.info("admin.tier.created symbol={} groupCode={} tierNo={}",
                    request.symbol(), request.groupCode(), request.tierNo());
            return item;
        } catch (InternalRpcException ex) {
            translateTierError(ex);
            throw ex;
        }
    }

    /** 编辑档位（高危）：写前置审计快照（before=变更请求；after=变更后请求），透传到 trading（不含 reason）。 */
    public AdminTierListResponse.Item updateTier(long id, AdminTierUpdateRequest request) {
        verifyReason(request.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tierNo", request.tierNo());
        body.put("notionalLower", request.notionalLower());
        body.put("notionalUpper", request.notionalUpper());
        body.put("maxLeverage", request.maxLeverage());
        body.put("mmRate", request.mmRate());
        AuditSnapshotHolder.set(
                Map.of("tierId", id),
                auditAfter("update", request.reason(), Map.of("tierId", id, "fields", body)));
        try {
            AdminTierListResponse.Item item = internalRpcClient.put(
                    TIER_RPC_BASE + "/" + id, body, TIER_ITEM_TYPE);
            log.info("admin.tier.updated id={} tierNo={}", id, request.tierNo());
            return item;
        } catch (InternalRpcException ex) {
            translateTierError(ex);
            throw ex;
        }
    }

    /** 软删档位（高危）：写前置审计快照，透传 DELETE 到 trading。 */
    public void deleteTier(long id) {
        AuditSnapshotHolder.set(
                Map.of("tierId", id),
                auditAfter("delete", null, Map.of("tierId", id, "enabled", false)));
        try {
            internalRpcClient.delete(TIER_RPC_BASE + "/" + id, VOID_TYPE);
            log.info("admin.tier.deleted id={}", id);
        } catch (InternalRpcException ex) {
            translateTierError(ex);
            throw ex;
        }
    }

    // ---- helpers ----

    /** 组装透传到 trading RPC 的请求体（字段对齐 trading AdminTierCreateRequest，刻意不含 reason）。 */
    private Map<String, Object> tradingBody(String symbol, String groupCode, Integer tierNo,
                                            Object notionalLower, Object notionalUpper,
                                            Integer maxLeverage, Object mmRate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("groupCode", groupCode);
        body.put("tierNo", tierNo);
        body.put("notionalLower", notionalLower);
        body.put("notionalUpper", notionalUpper);
        body.put("maxLeverage", maxLeverage);
        body.put("mmRate", mmRate);
        return body;
    }

    private Map<String, Object> auditAfter(String action, String reason, Object payload) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("action", action);
        if (reason != null) after.put("reason", reason);
        after.put("payload", payload);
        return after;
    }

    private void verifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED);
        }
    }

    /** trading-core tier CRUD 下游错误码（90930-90932）→ console AdminErrorCode 翻译。 */
    private void translateTierError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90930" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TIER_NOT_FOUND);
            case "90931" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TIER_VALIDATION_FAILED);
            case "90932" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TIER_OVERLAP);
            default -> { /* 其他错误透传 */ }
        }
    }
}
