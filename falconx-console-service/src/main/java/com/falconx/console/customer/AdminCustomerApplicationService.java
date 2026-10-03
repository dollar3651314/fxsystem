package com.falconx.console.customer;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminCustomerBalanceAdjustResponse;
import com.falconx.console.api.AdminCustomerDetailResponse;
import com.falconx.console.api.AdminCustomerFreezeResponse;
import com.falconx.console.api.AdminCustomerListResponse;
import com.falconx.console.api.AdminCustomerPatchRequest;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.entity.AdminCustomer;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminCustomerRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-2-CUSTOMER 客户管理编排（GET 列表/详情 + freeze/unfreeze/balance-adjust 5 接口）。
 *
 * <p>跨 schema 只读 → 调用 internal RPC → Redis 限额双写 → 审计 AOP 自动记录（高风险动作）。
 */
@Service
public class AdminCustomerApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminCustomerApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final AdminCustomerRepository adminCustomerRepository;
    private final InternalRpcClient internalRpcClient;
    private final BalanceAdjustQuotaService balanceAdjustQuotaService;
    private final ConsoleServiceProperties properties;

    public AdminCustomerApplicationService(AdminCustomerRepository adminCustomerRepository,
                                           InternalRpcClient internalRpcClient,
                                           BalanceAdjustQuotaService balanceAdjustQuotaService,
                                           ConsoleServiceProperties properties) {
        this.adminCustomerRepository = adminCustomerRepository;
        this.internalRpcClient = internalRpcClient;
        this.balanceAdjustQuotaService = balanceAdjustQuotaService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public AdminCustomerListResponse listCustomers(String email,
                                                   List<String> statusNames,
                                                   OffsetDateTime from,
                                                   OffsetDateTime to,
                                                   int page,
                                                   int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        List<AdminCustomer> customers = adminCustomerRepository.findCustomers(
                email, statusNames, from, to, offset, safeSize);
        long total = adminCustomerRepository.countCustomers(email, statusNames, from, to);
        List<AdminCustomerListResponse.Item> items = customers.stream()
                .map(c -> new AdminCustomerListResponse.Item(
                        c.userId(), c.uid(), c.email(), c.status(), c.emailVerified(),
                        c.groupCode(), c.balance(), c.lastLoginAt(), c.createdAt(),
                        c.fullName(), c.kycLevel()))
                .toList();
        return new AdminCustomerListResponse(items, total, page, safeSize);
    }

    @Transactional(readOnly = true)
    public AdminCustomerDetailResponse getCustomerDetail(long userId) {
        AdminCustomer c = adminCustomerRepository.findCustomerById(userId)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_NOT_FOUND));
        AdminCustomerDetailResponse.Profile profile = adminCustomerRepository
                .findCustomerProfileById(userId)
                .map(p -> new AdminCustomerDetailResponse.Profile(
                        p.firstName(), p.middleName(), p.lastName(),
                        p.birthDate(), p.nationality(), p.gender(),
                        p.residenceCountry(), p.residenceState(), p.residenceCity(),
                        p.residenceAddress(), p.residencePostalCode(),
                        p.phoneCountryCode(), p.phoneNumber(),
                        p.languagePreference(), p.timezone(),
                        p.profileVerified()
                ))
                .orElse(null);
        return new AdminCustomerDetailResponse(
                c.userId(), c.uid(), c.email(), c.status(), c.emailVerified(), c.groupCode(),
                c.activatedAt(), c.lastLoginAt(), c.lastLoginIp(),
                new AdminCustomerDetailResponse.Balance(c.balance(), c.available(), c.marginUsed()),
                c.createdAt(),
                c.kycLevel(),
                profile
        );
    }

    public AdminCustomerFreezeResponse freezeCustomer(long userId, String reason) {
        verifyReason(reason);
        Map<String, Object> data = invokeFreezeUnfreezeRpc(
                "/internal/v1/identity/users/" + userId + "/freeze", reason);
        log.info("admin.customer.freeze.completed userId={} previousStatus={} newStatus={}",
                userId, data.get("previousStatus"), data.get("newStatus"));
        com.falconx.console.security.AuditSnapshotHolder.set(
                Map.of("userId", userId, "status", String.valueOf(data.get("previousStatus"))),
                Map.of("status", String.valueOf(data.get("newStatus")), "action", "freeze", "reason", reason)
        );
        return mapToFreezeResponse(data);
    }

    public AdminCustomerFreezeResponse unfreezeCustomer(long userId, String reason) {
        verifyReason(reason);
        Map<String, Object> data = invokeFreezeUnfreezeRpc(
                "/internal/v1/identity/users/" + userId + "/unfreeze", reason);
        log.info("admin.customer.unfreeze.completed userId={} previousStatus={} newStatus={}",
                userId, data.get("previousStatus"), data.get("newStatus"));
        com.falconx.console.security.AuditSnapshotHolder.set(
                Map.of("userId", userId, "status", String.valueOf(data.get("previousStatus"))),
                Map.of("status", String.valueOf(data.get("newStatus")), "action", "unfreeze", "reason", reason)
        );
        return mapToFreezeResponse(data);
    }

    /**
     * 管理端编辑客户详情。identity / profile 两块独立可选；reason 必填，AuditAspect 自动写审计。
     * 任一 RPC 失败抛 InternalRpcException；profile 失败不会回滚已写入的 identity 字段（identity 服务内
     * 各自事务），UI 应在保存失败时刷新详情看实际落地状态。
     */
    public void editCustomer(long userId, AdminCustomerPatchRequest request) {
        verifyReason(request.reason());
        if (request.identity() != null) {
            try {
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                if (request.identity().email() != null) body.put("email", request.identity().email());
                if (request.identity().emailVerified() != null) body.put("emailVerified", request.identity().emailVerified());
                if (request.identity().groupCode() != null) body.put("groupCode", request.identity().groupCode());
                if (request.identity().kycLevel() != null) body.put("kycLevel", request.identity().kycLevel());
                if (request.identity().status() != null) body.put("status", request.identity().status());
                if (!body.isEmpty()) {
                    internalRpcClient.patch("/internal/v1/identity/users/" + userId, body, MAP_RESPONSE_TYPE);
                    log.info("admin.customer.identity-patch.completed userId={} fields={}", userId, body.keySet());
                }
            } catch (InternalRpcException ex) {
                translateFreezeUnfreezeError(ex);
                throw ex;
            }
        }
        if (request.profile() != null) {
            try {
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                var p = request.profile();
                if (p.firstName() != null) body.put("firstName", p.firstName());
                if (p.middleName() != null) body.put("middleName", p.middleName());
                if (p.lastName() != null) body.put("lastName", p.lastName());
                if (p.birthDate() != null) body.put("birthDate", p.birthDate().toString());
                if (p.nationality() != null) body.put("nationality", p.nationality());
                if (p.gender() != null) body.put("gender", p.gender());
                if (p.residenceCountry() != null) body.put("residenceCountry", p.residenceCountry());
                if (p.residenceState() != null) body.put("residenceState", p.residenceState());
                if (p.residenceCity() != null) body.put("residenceCity", p.residenceCity());
                if (p.residenceAddress() != null) body.put("residenceAddress", p.residenceAddress());
                if (p.residencePostalCode() != null) body.put("residencePostalCode", p.residencePostalCode());
                if (p.phoneCountryCode() != null) body.put("phoneCountryCode", p.phoneCountryCode());
                if (p.phoneNumber() != null) body.put("phoneNumber", p.phoneNumber());
                if (p.languagePreference() != null) body.put("languagePreference", p.languagePreference());
                if (p.timezone() != null) body.put("timezone", p.timezone());
                if (!body.isEmpty()) {
                    internalRpcClient.patch("/internal/v1/identity/users/" + userId + "/profile", body, MAP_RESPONSE_TYPE);
                    log.info("admin.customer.profile-patch.completed userId={} fields={}", userId, body.keySet());
                }
            } catch (InternalRpcException ex) {
                translateFreezeUnfreezeError(ex);
                throw ex;
            }
        }
    }

    public AdminCustomerBalanceAdjustResponse adjustBalance(long userId, BigDecimal deltaUSD, String reason) {
        verifyReason(reason);
        com.falconx.console.security.AdminPrincipal principal =
                com.falconx.console.security.AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        balanceAdjustQuotaService.verifyAndIncrement(
                principal.adminUserId(),
                deltaUSD,
                properties.getBalanceAdjust().getSingleLimitUSD(),
                properties.getBalanceAdjust().getDailyLimitUSD());

        Map<String, Object> body = Map.of("deltaUSD", deltaUSD.toPlainString(), "reason", reason);
        try {
            Map<String, Object> data = internalRpcClient.post(
                    "/internal/v1/trading/accounts/" + userId + "/balance/adjust",
                    body,
                    MAP_RESPONSE_TYPE);
            log.info("admin.customer.balance-adjust.completed userId={} delta={} balanceAfter={}",
                    userId, deltaUSD, data.get("balanceAfter"));
            // 审计快照：before/after 余额 + 操作 delta + 原因
            com.falconx.console.security.AuditSnapshotHolder.set(
                    java.util.Map.of("balance", data.get("balanceBefore"), "userId", userId),
                    new java.util.LinkedHashMap<String, Object>() {{
                        put("balance", data.get("balanceAfter"));
                        put("delta", deltaUSD.toPlainString());
                        put("ledgerEntryId", data.get("ledgerEntryId"));
                        put("reason", reason);
                    }}
            );
            return new AdminCustomerBalanceAdjustResponse(
                    userId,
                    deltaUSD,
                    new BigDecimal(String.valueOf(data.get("balanceBefore"))),
                    new BigDecimal(String.valueOf(data.get("balanceAfter"))),
                    Long.valueOf(String.valueOf(data.get("ledgerEntryId")))
            );
        } catch (InternalRpcException ex) {
            translateBalanceAdjustError(ex);
            throw ex;
        }
    }

    private Map<String, Object> invokeFreezeUnfreezeRpc(String path, String reason) {
        Map<String, Object> body = Map.of("reason", reason);
        try {
            return internalRpcClient.post(path, body, MAP_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateFreezeUnfreezeError(ex);
            throw ex;
        }
    }

    private AdminCustomerFreezeResponse mapToFreezeResponse(Map<String, Object> data) {
        return new AdminCustomerFreezeResponse(
                ((Number) data.get("userId")).longValue(),
                String.valueOf(data.get("previousStatus")),
                String.valueOf(data.get("newStatus"))
        );
    }

    private void verifyReason(String reason) {
        if (reason == null || reason.length() < 10) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_REASON_TOO_SHORT);
        }
    }

    /** 把 identity 业务码翻译为 console 错误码（按管理端接口规范 §3.3-§3.4 错误映射）。 */
    private void translateFreezeUnfreezeError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "10012" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_NOT_FOUND);
            case "10013" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_ALREADY_FROZEN);
            case "10014" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_TERMINAL_STATUS);
            // 10015 USER_NOT_FROZEN：解冻时当前不是 FROZEN，复用 ALREADY_FROZEN 的反向语义不合适；
            // 临时复用 ADMIN_CUSTOMER_TERMINAL_STATUS 提示运营状态不允许（更精确错误码待 R2 三轮扩展）
            case "10015" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_TERMINAL_STATUS);
            default -> { /* 其他错误透传 */ }
        }
    }

    /** 把 trading-core 业务码翻译为 console 错误码。 */
    private void translateBalanceAdjustError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "30030" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_CUSTOMER_NOT_FOUND);
            case "30031" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_BALANCE_INSUFFICIENT_FOR_NEGATIVE_ADJUST);
            default -> { /* 其他错误透传 */ }
        }
    }
}
