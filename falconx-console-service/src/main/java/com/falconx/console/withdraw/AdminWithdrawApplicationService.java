package com.falconx.console.withdraw;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminWithdrawItem;
import com.falconx.console.api.AdminWithdrawListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminWithdrawEnrichmentRepository;
import com.falconx.console.repository.mapper.record.AdminWithdrawEnrichmentRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-7-WITHDRAW Phase 4：出金审核管理端编排。
 *
 * <p>转发到 trading-core {@code /internal/v1/trading/withdraws}（注意 plural，spec §10.8 写成 singular 是误，
 * 已挂统一问题清单）。trading-core 错误码 30047 / 30049 / 30050 翻译为 console 90500 / 90501 / 90502；
 * console 前置校验缺 reason 直接抛 90503，不走 internal RPC。
 *
 * <p>请求体字段映射：spec §10.3 {@code reviewNote} / §10.4-10.5 {@code reason} → trading-core 内部统一 {@code note}。
 */
@Service
public class AdminWithdrawApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminWithdrawApplicationService.class);

    private static final String BASE_PATH = "/internal/v1/trading/withdraws";

    private static final ParameterizedTypeReference<ApiResponse<AdminWithdrawListResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminWithdrawItem>> ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminWithdrawEnrichmentRepository enrichmentRepository;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminWithdrawApplicationService(InternalRpcClient internalRpcClient,
                                           AdminWithdrawEnrichmentRepository enrichmentRepository,
                                           AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.enrichmentRepository = enrichmentRepository;
        this.userInfoEnricher = userInfoEnricher;
    }

    public AdminWithdrawListResponse list(String status, Long userId, String network,
                                          BigDecimal minAmount, BigDecimal maxAmount,
                                          int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        StringBuilder query = new StringBuilder(BASE_PATH).append("?page=")
                .append(safePage).append("&pageSize=").append(safeSize);
        if (status != null && !status.isBlank()) query.append("&status=").append(status);
        if (userId != null) query.append("&userId=").append(userId);
        if (network != null && !network.isBlank()) query.append("&network=").append(network);
        if (minAmount != null) query.append("&minAmount=").append(minAmount.toPlainString());
        // maxAmount: spec 含但 trading-core 当前 controller 未接，附在 query 不影响（trading-core 忽略未声明的 @RequestParam）
        if (maxAmount != null) query.append("&maxAmount=").append(maxAmount.toPlainString());
        AdminWithdrawListResponse base;
        try {
            base = internalRpcClient.get(query.toString(), LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
        return new AdminWithdrawListResponse(
                base.page(),
                base.pageSize(),
                base.total(),
                enrichItems(base.items()));
    }

    public AdminWithdrawItem detail(long withdrawId) {
        AdminWithdrawItem base;
        try {
            base = internalRpcClient.get(BASE_PATH + "/" + withdrawId, ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
        return enrichItems(List.of(base)).get(0);
    }

    /**
     * 批 enrich：把 items 里所有 userId 一次 SQL 取出 email / kyc_level / 当日累计，
     * 用 withEnrichment 覆写。DB 异常时返回原 items（best-effort，不阻塞 admin 工作流）。
     */
    private List<AdminWithdrawItem> enrichItems(List<AdminWithdrawItem> items) {
        if (items == null || items.isEmpty()) return items;
        List<Long> userIds = new ArrayList<>(items.size());
        for (AdminWithdrawItem it : items) {
            try {
                userIds.add(Long.parseLong(it.userId()));
            } catch (NumberFormatException ignored) {
                // 极端情况：trading-core 返回了非数字 userId，跳过该项
            }
        }
        if (userIds.isEmpty()) return items;
        Map<Long, AdminWithdrawEnrichmentRecord> enrichments;
        try {
            enrichments = enrichmentRepository.findEnrichmentMap(userIds, OffsetDateTime.now(ZoneOffset.UTC));
        } catch (Exception ex) {
            log.warn("admin.withdraw.enrich.failed size={} reason={}", userIds.size(), ex.toString());
            return items;
        }
        List<AdminWithdrawItem> out = new ArrayList<>(items.size());
        for (AdminWithdrawItem it : items) {
            AdminWithdrawEnrichmentRecord r = null;
            try {
                r = enrichments.get(Long.parseLong(it.userId()));
            } catch (NumberFormatException ignored) {
                // 同上跳过
            }
            if (r == null) {
                out.add(it);
            } else {
                out.add(it.withEnrichment(r.kycLevel(), r.email(), r.dailyAccumulatedUsd()));
            }
        }
        // 二次 enrich：补 uid + 姓名（邮箱/kyc/当日累计已由上面填充）。best-effort，失败返回 out。
        return userInfoEnricher.enrich(out, it -> Long.parseLong(it.userId()),
                (it, r) -> it.withUserName(r.uid(), r.fullName()));
    }

    public AdminWithdrawItem approve(long withdrawId, String reviewNote) {
        Map<String, Object> body = new HashMap<>();
        if (reviewNote != null && !reviewNote.isBlank()) {
            body.put("note", reviewNote);  // spec reviewNote → trading-core note
        }
        AdminWithdrawItem beforeItem = safeDetail(withdrawId);
        try {
            AdminWithdrawItem item = internalRpcClient.post(
                    BASE_PATH + "/" + withdrawId + "/approve", body, ITEM_TYPE);
            log.info("admin.withdraw.approve.completed withdrawId={} status={}", withdrawId, item.status());
            captureSnapshot(beforeItem, item, "approve", reviewNote);
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminWithdrawItem reject(long withdrawId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_WITHDRAW_REJECT_REASON_REQUIRED);
        }
        AdminWithdrawItem beforeItem = safeDetail(withdrawId);
        try {
            AdminWithdrawItem item = internalRpcClient.post(
                    BASE_PATH + "/" + withdrawId + "/reject",
                    Map.of("note", reason),  // spec reason → trading-core note
                    ITEM_TYPE);
            log.info("admin.withdraw.reject.completed withdrawId={} status={}", withdrawId, item.status());
            captureSnapshot(beforeItem, item, "reject", reason);
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminWithdrawItem emergencyCancel(long withdrawId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_WITHDRAW_REJECT_REASON_REQUIRED);
        }
        AdminWithdrawItem beforeItem = safeDetail(withdrawId);
        try {
            AdminWithdrawItem item = internalRpcClient.post(
                    BASE_PATH + "/" + withdrawId + "/emergency-cancel",
                    Map.of("note", reason),  // spec reason → trading-core note
                    ITEM_TYPE);
            log.info("admin.withdraw.emergency-cancel.completed withdrawId={} status={}", withdrawId, item.status());
            captureSnapshot(beforeItem, item, "emergency-cancel", reason);
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    /** 在执行变更前先查一遍详情拿 before 状态。查询失败不阻塞主流程（best-effort）。 */
    private AdminWithdrawItem safeDetail(long withdrawId) {
        try {
            return detail(withdrawId);
        } catch (Exception ex) {
            log.warn("admin.withdraw.snapshot.detail.failed withdrawId={} reason={}", withdrawId, ex.toString());
            return null;
        }
    }

    /** 写入审计 ThreadLocal：只挑业务关心的字段，避免泄露 outbox / version 等内部状态。 */
    private void captureSnapshot(AdminWithdrawItem before, AdminWithdrawItem after, String action, String note) {
        java.util.LinkedHashMap<String, Object> beforeMap = before == null ? null : new java.util.LinkedHashMap<>() {{
            put("status", before.status());
            put("amount", before.amount());
            put("targetAddress", before.targetAddress());
            put("network", before.network());
        }};
        java.util.LinkedHashMap<String, Object> afterMap = new java.util.LinkedHashMap<>() {{
            put("status", after.status());
            put("amount", after.amount());
            put("action", action);
            if (note != null && !note.isBlank()) put("note", note);
        }};
        com.falconx.console.security.AuditSnapshotHolder.set(beforeMap, afterMap);
    }

    private void translateError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "30047" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_WITHDRAW_NOT_FOUND);
            case "30049" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_WITHDRAW_NOT_PENDING);
            case "30050" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED);
            default -> { /* 透传其他错误 */ }
        }
    }
}
