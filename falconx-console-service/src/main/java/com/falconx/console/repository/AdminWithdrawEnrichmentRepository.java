package com.falconx.console.repository;

import com.falconx.console.repository.mapper.record.AdminWithdrawEnrichmentRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * STAGE-7-WITHDRAW Phase 4 §4 commit C：管理端出金审核 enrichment 仓储。
 *
 * <p>暴露按 userId 批查 enrichment，返回 userId → record 的 map 方便 application service 装配 DTO。
 */
public interface AdminWithdrawEnrichmentRepository {

    /**
     * 按 userId 集合批查 enrichment。userIds 为空时立即返回空 map。
     *
     * @param userIds 待 enrich 的用户 ID 集合（自动去重）
     * @param dayUtc  以哪一天的 UTC 当日为窗口；通常是 {@code OffsetDateTime.now(UTC)}
     * @return userId → enrichment 记录
     */
    Map<Long, AdminWithdrawEnrichmentRecord> findEnrichmentMap(List<Long> userIds, OffsetDateTime dayUtc);
}
