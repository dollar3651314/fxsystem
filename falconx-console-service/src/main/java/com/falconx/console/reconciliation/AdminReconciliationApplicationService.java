package com.falconx.console.reconciliation;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminDepositListResponse;
import com.falconx.console.api.AdminReconciliationItem;
import com.falconx.console.api.AdminReconciliationListResponse;
import com.falconx.console.api.AdminReconciliationMarkResolvedRequest;
import com.falconx.console.api.AdminReconciliationMarkResolvedResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.falconx.console.reconciliation.TradingDepositReconView.Item;
import com.falconx.console.reconciliation.TradingDepositReconView.ListResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-11-OBS-RECON §11.4：管理端入金对账编排服务。
 *
 * <p>聚合 wallet {@code /internal/v1/wallet/console/deposits?status=CONFIRMED|REVERSED} +
 * trading-core {@code /internal/v1/trading/console/deposits?status=CREDITED|REVERSED}，
 * 在 console 内存中按 {@code (chain, tx_hash)} 配对做 diff，分类为 4 种 discrepancy。
 *
 * <p>已 resolved 项凭 {@code t_admin_operation_log.target_type='reconciliation'} 去重。
 *
 * <p>V2 一期 unmatched 上界 5000 项；超过则按设计文档 §6.3 引入 schema 持久化（不在本阶段做）。
 */
@Service
public class AdminReconciliationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminReconciliationApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminDepositListResponse>> WALLET_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ListResponse>> TRADING_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    /** 每次单页拉取上限。 */
    private static final int FETCH_PAGE_SIZE = 100;
    /** 单次对账最多迭代页数（5000 项上界）。 */
    private static final int MAX_PAGES = 50;

    private final InternalRpcClient internalRpcClient;
    private final ResolvedAuditQuery resolvedQuery;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminReconciliationApplicationService(InternalRpcClient internalRpcClient,
                                                  ResolvedAuditQuery resolvedQuery,
                                                  AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.resolvedQuery = resolvedQuery;
        this.userInfoEnricher = userInfoEnricher;
    }

    public AdminReconciliationListResponse listUnmatched(String chain,
                                                          String token,
                                                          String discrepancyType,
                                                          OffsetDateTime fromOccurredAt,
                                                          OffsetDateTime toOccurredAt,
                                                          int page,
                                                          int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        Map<String, AdminDepositListResponse.Item> walletByKey = fetchWalletAll(chain, token, fromOccurredAt, toOccurredAt);
        Map<String, Item> tradingByKey = fetchTradingAll(chain, token, fromOccurredAt, toOccurredAt);

        List<AdminReconciliationItem> all = diff(walletByKey, tradingByKey);

        if (discrepancyType != null && !discrepancyType.isBlank()) {
            all = all.stream().filter(it -> discrepancyType.equals(it.discrepancyType())).toList();
        }

        List<AdminReconciliationItem> notResolved = all.stream()
                .filter(it -> !resolvedQuery.isResolved(it.walletTxId()))
                .sorted(Comparator.comparing(AdminReconciliationItem::walletDetectedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        long total = notResolved.size();
        int offset = (safePage - 1) * safeSize;
        if (offset >= notResolved.size()) {
            return new AdminReconciliationListResponse(safePage, safeSize, total, List.of());
        }
        List<AdminReconciliationItem> slice = notResolved.subList(offset, Math.min(offset + safeSize, notResolved.size()));
        // 仅 enrich 当前页（userId 为 String 且可能为 null，idOf 解析失败的项自动跳过）
        List<AdminReconciliationItem> enriched = userInfoEnricher.enrich(
                new ArrayList<>(slice), it -> Long.parseLong(it.userId()),
                (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
        return new AdminReconciliationListResponse(safePage, safeSize, total, enriched);
    }

    public AdminReconciliationMarkResolvedResponse markResolved(long walletTxId,
                                                                 AdminReconciliationMarkResolvedRequest request) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        if (resolvedQuery.isResolved(String.valueOf(walletTxId))) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_RECONCILIATION_ALREADY_RESOLVED);
        }
        boolean stillUnmatched = !findInWallet(walletTxId).matched();
        if (!stillUnmatched) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_RECONCILIATION_NOT_FOUND);
        }
        log.info("admin.reconciliation.mark-resolved walletTxId={} adminUserId={} resolutionType={}",
                walletTxId, principal.adminUserId(), request.resolutionType());
        // 审计落库由 OperationAuditAspect 在 controller @RequiresPermission 自动写。
        return new AdminReconciliationMarkResolvedResponse(
                String.valueOf(walletTxId),
                OffsetDateTime.now(ZoneOffset.UTC),
                String.valueOf(principal.adminUserId()));
    }

    private MatchResult findInWallet(long walletTxId) {
        try {
            AdminDepositListResponse.Item item = internalRpcClient.get(
                    "/internal/v1/wallet/console/deposits/" + walletTxId,
                    new ParameterizedTypeReference<ApiResponse<AdminDepositListResponse.Item>>() {});
            return new MatchResult(item != null);
        } catch (InternalRpcException ex) {
            if (ex.getHttpStatus() == 404 || "90850".equals(ex.getDownstreamCode())) {
                return new MatchResult(false);
            }
            translateWalletError(ex);
            throw ex;
        }
    }

    private Map<String, AdminDepositListResponse.Item> fetchWalletAll(String chain, String token,
                                                                       OffsetDateTime from, OffsetDateTime to) {
        Map<String, AdminDepositListResponse.Item> all = new LinkedHashMap<>();
        for (String status : new String[]{"CONFIRMED", "REVERSED"}) {
            int page = 1;
            while (page <= MAX_PAGES) {
                AdminDepositListResponse resp = fetchWalletPage(chain, token, status, from, to, page);
                if (resp == null || resp.items() == null || resp.items().isEmpty()) {
                    break;
                }
                for (AdminDepositListResponse.Item item : resp.items()) {
                    all.put(keyOf(item.chain(), item.txHash()), item);
                }
                if (resp.items().size() < FETCH_PAGE_SIZE) {
                    break;
                }
                page++;
            }
        }
        return all;
    }

    private AdminDepositListResponse fetchWalletPage(String chain, String token, String status,
                                                     OffsetDateTime from, OffsetDateTime to, int page) {
        StringBuilder q = new StringBuilder("/internal/v1/wallet/console/deposits?page=")
                .append(page).append("&size=").append(FETCH_PAGE_SIZE)
                .append("&status=").append(status);
        if (chain != null && !chain.isBlank()) q.append("&chain=").append(chain);
        if (token != null && !token.isBlank()) q.append("&token=").append(token);
        if (from != null) q.append("&fromDetectedAt=").append(formatIso(from));
        if (to != null) q.append("&toDetectedAt=").append(formatIso(to));
        try {
            return internalRpcClient.get(q.toString(), WALLET_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateWalletError(ex);
            throw ex;
        }
    }

    private Map<String, Item> fetchTradingAll(String chain, String token,
                                                                       OffsetDateTime from, OffsetDateTime to) {
        Map<String, Item> all = new LinkedHashMap<>();
        // V23 起加了 REJECTED 状态（token 不在白名单）— 也是业务决策已落地的终态，
        // 必须纳入对账拉取，否则 wallet=CONFIRMED + trading 端 REJECTED 行会被误标 WALLET_ONLY
        for (String status : new String[]{"CREDITED", "REVERSED", "REJECTED"}) {
            int page = 1;
            while (page <= MAX_PAGES) {
                ListResponse resp = fetchTradingPage(chain, token, status, from, to, page);
                if (resp == null || resp.items() == null || resp.items().isEmpty()) {
                    break;
                }
                for (Item item : resp.items()) {
                    all.put(keyOf(item.chain(), item.txHash()), item);
                }
                if (resp.items().size() < FETCH_PAGE_SIZE) {
                    break;
                }
                page++;
            }
        }
        return all;
    }

    private ListResponse fetchTradingPage(String chain, String token, String status,
                                                                    OffsetDateTime from, OffsetDateTime to, int page) {
        StringBuilder q = new StringBuilder("/internal/v1/trading/console/deposits?page=")
                .append(page).append("&size=").append(FETCH_PAGE_SIZE)
                .append("&status=").append(status);
        if (chain != null && !chain.isBlank()) q.append("&chain=").append(chain);
        if (token != null && !token.isBlank()) q.append("&token=").append(token);
        if (from != null) q.append("&fromCreatedAt=").append(formatIso(from));
        if (to != null) q.append("&toCreatedAt=").append(formatIso(to));
        try {
            return internalRpcClient.get(q.toString(), TRADING_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    private List<AdminReconciliationItem> diff(Map<String, AdminDepositListResponse.Item> walletByKey,
                                                 Map<String, Item> tradingByKey) {
        List<AdminReconciliationItem> out = new ArrayList<>();
        for (Map.Entry<String, AdminDepositListResponse.Item> e : walletByKey.entrySet()) {
            AdminDepositListResponse.Item w = e.getValue();
            Item t = tradingByKey.get(e.getKey());
            String discrepancy = classifyDiscrepancy(w, t);
            if (discrepancy == null) {
                continue;
            }
            out.add(buildItem(w, t, discrepancy));
        }
        for (Map.Entry<String, Item> e : tradingByKey.entrySet()) {
            if (walletByKey.containsKey(e.getKey())) {
                continue;
            }
            out.add(buildItem(null, e.getValue(), "TRADING_ONLY"));
        }
        return out;
    }

    private static String classifyDiscrepancy(AdminDepositListResponse.Item w, Item t) {
        if (w == null && t == null) return null;
        if (t == null) {
            return "CONFIRMED".equalsIgnoreCase(w.status()) ? "WALLET_ONLY" : null;
        }
        String walletStatus = w.status() == null ? "" : w.status().toUpperCase();
        String tradingStatus = t.status() == null ? "" : t.status().toUpperCase();
        // V23 起：trading=REJECTED 是业务主动决策的终态（token 不在白名单等），
        // 跟 CREDITED 一样属于「已处理」，不构成对账差异；amount 不参与比较
        // （因为 REJECTED 行 amount 仍是 wallet 端原始金额，比对会通过但语义无意义）
        if ("REJECTED".equals(tradingStatus)) {
            return null;
        }
        boolean statusDiverged =
                ("REVERSED".equals(walletStatus) && "CREDITED".equals(tradingStatus))
                        || ("CONFIRMED".equals(walletStatus) && "REVERSED".equals(tradingStatus));
        if (statusDiverged) return "STATUS_DIVERGED";
        BigDecimal wa = w.amount();
        BigDecimal ta = t.amount();
        if (wa != null && ta != null && wa.compareTo(ta) != 0) return "AMOUNT_MISMATCH";
        return null;
    }

    private static AdminReconciliationItem buildItem(AdminDepositListResponse.Item w,
                                                      Item t,
                                                      String discrepancy) {
        return new AdminReconciliationItem(
                w != null ? String.valueOf(w.id()) : null,
                t != null ? t.id() : null,
                w != null ? w.chain() : t.chain(),
                w != null ? w.token() : t.token(),
                w != null ? w.txHash() : t.txHash(),
                w != null ? w.amount() : null,
                t != null ? t.amount() : null,
                w != null ? w.status() : null,
                t != null ? t.status() : null,
                discrepancy,
                w != null && w.detectedAt() != null ? w.detectedAt().atOffset(ZoneOffset.UTC) : null,
                w != null && w.confirmedAt() != null ? w.confirmedAt().atOffset(ZoneOffset.UTC) : null,
                w != null ? String.valueOf(w.userId()) : t != null ? t.userId() : null,
                w != null ? w.toAddress() : null,
                null, null, null  // userUid / userEmail / userFullName：在 listUnmatched 分页后统一 enrich
        );
    }

    private static String keyOf(String chain, String txHash) {
        return (chain == null ? "" : chain.toUpperCase()) + "::" + (txHash == null ? "" : txHash);
    }

    private static String formatIso(OffsetDateTime ts) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ts);
    }

    private void translateWalletError(InternalRpcException ex) {
        if (ex.getHttpStatus() == 0 || ex.getHttpStatus() >= 500) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_RECONCILIATION_WALLET_UNREACHABLE);
        }
    }

    private void translateTradingError(InternalRpcException ex) {
        if (ex.getHttpStatus() == 0 || ex.getHttpStatus() >= 500) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_RECONCILIATION_TRADING_UNREACHABLE);
        }
    }

    private record MatchResult(boolean matched) {}
}
