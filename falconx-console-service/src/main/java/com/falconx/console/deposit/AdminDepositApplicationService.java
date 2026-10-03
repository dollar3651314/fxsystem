package com.falconx.console.deposit;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminDepositListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import com.falconx.console.repository.AdminDepositCreditEnrichmentRepository;
import com.falconx.console.repository.mapper.record.AdminDepositCreditRecord;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-DEPOSIT R9：入金记录管理编排。
 *
 * <p>纯只读，转发到 wallet-service /internal/v1/wallet/console/deposits*。错误码 90850 翻译。
 */
@Service
public class AdminDepositApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminDepositApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminDepositListResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminDepositListResponse.Item>> ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminDepositCreditEnrichmentRepository creditEnrichmentRepository;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminDepositApplicationService(InternalRpcClient internalRpcClient,
                                           AdminDepositCreditEnrichmentRepository creditEnrichmentRepository,
                                           AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.creditEnrichmentRepository = creditEnrichmentRepository;
        this.userInfoEnricher = userInfoEnricher;
    }

    /** 给 deposit items 批量补用户 uid / 邮箱 / 姓名（best-effort）。 */
    private List<AdminDepositListResponse.Item> enrichUserInfo(List<AdminDepositListResponse.Item> items) {
        return userInfoEnricher.enrich(items, AdminDepositListResponse.Item::userId,
                (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
    }

    public AdminDepositListResponse listDeposits(Long userId, String chain, String token,
                                                  String status, OffsetDateTime fromDetectedAt,
                                                  OffsetDateTime toDetectedAt,
                                                  boolean onlyOrphan, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/wallet/console/deposits?page=")
                .append(page).append("&size=").append(size).append("&onlyOrphan=").append(onlyOrphan);
        if (userId != null) query.append("&userId=").append(userId);
        if (chain != null && !chain.isBlank()) query.append("&chain=").append(chain);
        if (token != null && !token.isBlank()) query.append("&token=").append(token);
        if (status != null && !status.isBlank()) query.append("&status=").append(status);
        if (fromDetectedAt != null) query.append("&fromDetectedAt=").append(formatIso(fromDetectedAt));
        if (toDetectedAt != null) query.append("&toDetectedAt=").append(formatIso(toDetectedAt));
        AdminDepositListResponse rsp;
        try {
            rsp = internalRpcClient.get(query.toString(), LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
        // V23：跨 schema 拼回 trading-core 入账状态（best-effort，DB 异常时返回原 items）
        List<AdminDepositListResponse.Item> enriched = enrichUserInfo(enrichCreditStatus(rsp.items()));
        return new AdminDepositListResponse(enriched, rsp.total(), rsp.page(), rsp.size());
    }

    public AdminDepositListResponse.Item getDeposit(long id) {
        AdminDepositListResponse.Item item;
        try {
            item = internalRpcClient.get("/internal/v1/wallet/console/deposits/" + id, ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
        if (item == null) return null;
        List<AdminDepositListResponse.Item> enriched = enrichUserInfo(enrichCreditStatus(List.of(item)));
        return enriched.isEmpty() ? item : enriched.get(0);
    }

    /**
     * 跨 schema 批量 join falconx_trading.t_deposit，回填 creditStatus + rejectionReason。
     * DB 失败不抛错——只是失去 enrichment，原 wallet 数据保留。
     */
    private List<AdminDepositListResponse.Item> enrichCreditStatus(List<AdminDepositListResponse.Item> items) {
        if (items == null || items.isEmpty()) return items;
        List<Long> walletTxIds = new ArrayList<>(items.size());
        for (AdminDepositListResponse.Item it : items) {
            if (it.id() != null) walletTxIds.add(it.id());
        }
        if (walletTxIds.isEmpty()) return items;
        Map<Long, AdminDepositCreditRecord> creditByTxId;
        try {
            List<AdminDepositCreditRecord> records = creditEnrichmentRepository.findByWalletTxIds(walletTxIds);
            creditByTxId = new HashMap<>(records.size());
            for (AdminDepositCreditRecord r : records) {
                if (r.walletTxId() != null) creditByTxId.put(r.walletTxId(), r);
            }
        } catch (RuntimeException ex) {
            log.warn("admin.deposit.enrichCreditStatus.failed walletTxIdsCount={} reason={}",
                    walletTxIds.size(), ex.toString());
            return items;
        }
        List<AdminDepositListResponse.Item> out = new ArrayList<>(items.size());
        for (AdminDepositListResponse.Item it : items) {
            AdminDepositCreditRecord r = creditByTxId.get(it.id());
            if (r == null) {
                // wallet 端已检测但 trading 端无对应 row：
                // - DETECTED/CONFIRMING（未到 12 确认）：未到达 trading consumer
                // - CONFIRMED 但 trading 还在排队消费 Kafka：极短暂窗口
                out.add(it.withCreditEnrichment("PENDING", null));
            } else {
                String creditStatus = switch (r.statusCode() == null ? 0 : r.statusCode()) {
                    case 1 -> "CREDITED";
                    case 2 -> "REVERSED";
                    case 3 -> "REJECTED";
                    default -> "UNKNOWN";
                };
                out.add(it.withCreditEnrichment(creditStatus, r.rejectionReason()));
            }
        }
        return out;
    }

    private static String formatIso(OffsetDateTime ts) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ts);
    }

    private void translateError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if ("90850".equals(code)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_DEPOSIT_NOT_FOUND);
        }
    }
}
