package com.falconx.console.platformconfig;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.MarginModeConfigView;
import com.falconx.console.api.RiskThresholdView;
import com.falconx.console.api.UpdateCoolingPeriodRequest;
import com.falconx.console.api.UpdateRiskThresholdRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AuditSnapshotHolder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-14D3b Task2：平台风控配置（冷静期 / StopOut & MarginCall 阈值）管理端编排。
 *
 * <p>所有调用经 {@link InternalRpcClient} 透传到 D3a 已冻结的 trading-core internal RPC
 * {@code /internal/v1/trading/console/config/*}（console → gateway → trading-core；
 * gateway 注入 {@code X-Internal-Token}，client 注入 {@code X-Admin-User-Id} + {@code X-Trace-Id}）。
 * 读统一打到 {@code platform-risk}，两个 getter 各取所需字段。写操作（PUT）通过
 * {@link AuditSnapshotHolder} 落 before/after 快照，{@code OperationAuditAspect} 写
 * {@code t_admin_operation_log}。trading 越界返回 {@code 99004} → 翻译 90950（冷静期）/ 90952（阈值）。
 *
 * <p>边界（照搬 C2 tier R9）：console 只透传不直写 trading 业务表；{@code reason} 仅本地审计，不进 RPC body。
 */
@Service
public class AdminPlatformConfigApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminPlatformConfigApplicationService.class);

    private static final String PLATFORM_RISK_PATH = "/internal/v1/trading/console/config/platform-risk";
    private static final String COOLING_PERIOD_PATH = "/internal/v1/trading/console/config/cooling-period";
    private static final String RISK_THRESHOLDS_PATH = "/internal/v1/trading/console/config/risk-thresholds";

    /** trading-core Bean Validation 越界统一返回的下游错误码。 */
    private static final String DOWNSTREAM_VALIDATION_CODE = "99004";

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;

    public AdminPlatformConfigApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    /** 读冷静期：GET platform-risk，取 coolingPeriodSeconds。 */
    public MarginModeConfigView getMarginModeConfig() {
        Map<String, Object> data = fetchPlatformRisk();
        return new MarginModeConfigView(intValue(data.get("coolingPeriodSeconds")));
    }

    /** 更新冷静期（高危）：verifyReason + 审计快照 + 透传 PUT cooling-period（不含 reason）；99004 → 90950。 */
    public void updateCoolingPeriod(UpdateCoolingPeriodRequest request) {
        verifyReason(request.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("coolingPeriodSeconds", request.coolingPeriodSeconds());
        AuditSnapshotHolder.set(null, Map.of("coolingPeriodSeconds", request.coolingPeriodSeconds()));
        try {
            internalRpcClient.put(COOLING_PERIOD_PATH, body, VOID_TYPE);
            log.info("admin.platform-config.cooling-period.updated coolingPeriodSeconds={}",
                    request.coolingPeriodSeconds());
        } catch (InternalRpcException ex) {
            translate(ex, AdminErrorCode.ADMIN_MARGIN_MODE_CONFIG_INVALID);
            throw ex;
        }
    }

    /** 读风险阈值：GET platform-risk，取 stopOutLevel / marginCallLevel。 */
    public RiskThresholdView getRiskThresholds() {
        Map<String, Object> data = fetchPlatformRisk();
        return new RiskThresholdView(
                decimalValue(data.get("stopOutLevel")),
                decimalValue(data.get("marginCallLevel")));
    }

    /** 更新风险阈值（高危）：verifyReason + 审计快照 + 透传 PUT risk-thresholds（不含 reason）；99004 → 90952。 */
    public void updateRiskThresholds(UpdateRiskThresholdRequest request) {
        verifyReason(request.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stopOutLevel", request.stopOutLevel());
        body.put("marginCallLevel", request.marginCallLevel());
        AuditSnapshotHolder.set(null, Map.of(
                "stopOutLevel", request.stopOutLevel(),
                "marginCallLevel", request.marginCallLevel()));
        try {
            internalRpcClient.put(RISK_THRESHOLDS_PATH, body, VOID_TYPE);
            log.info("admin.platform-config.risk-thresholds.updated stopOutLevel={} marginCallLevel={}",
                    request.stopOutLevel(), request.marginCallLevel());
        } catch (InternalRpcException ex) {
            translate(ex, AdminErrorCode.ADMIN_RISK_THRESHOLD_INVALID);
            throw ex;
        }
    }

    // ---- helpers ----

    private Map<String, Object> fetchPlatformRisk() {
        Map<String, Object> data = internalRpcClient.get(PLATFORM_RISK_PATH, MAP_TYPE);
        return data == null ? Map.of() : data;
    }

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

    private int intValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(value.toString());
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return new BigDecimal(value.toString());
    }
}
