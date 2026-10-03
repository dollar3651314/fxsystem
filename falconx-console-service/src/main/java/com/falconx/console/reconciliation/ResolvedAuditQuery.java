package com.falconx.console.reconciliation;

import com.falconx.console.repository.AdminOperationLogRepository;
import org.springframework.stereotype.Component;

/**
 * STAGE-11-OBS-RECON §11.4：判定对账项是否已被 admin 标记 resolved。
 *
 * <p>判定方式：查 {@code t_admin_operation_log} 中
 * {@code permission_code='reconciliation:resolve' AND target_type='reconciliation' AND target_id={walletTxId}}
 * 是否存在 ≥ 1 条记录（由 OperationAuditAspect 在前一次 mark-resolved 自动写入）。
 *
 * <p>抽出独立 Component 是为了让 ApplicationService 单元测试可 mock。
 */
@Component
public class ResolvedAuditQuery {

    private static final String PERMISSION_CODE = "reconciliation:resolve";
    private static final String TARGET_TYPE = "reconciliation";

    private final AdminOperationLogRepository repository;

    public ResolvedAuditQuery(AdminOperationLogRepository repository) {
        this.repository = repository;
    }

    public boolean isResolved(String walletTxId) {
        if (walletTxId == null) {
            return false;
        }
        long count = repository.countByFilters(
                null, PERMISSION_CODE, TARGET_TYPE, walletTxId, null, null, null);
        return count > 0;
    }
}
