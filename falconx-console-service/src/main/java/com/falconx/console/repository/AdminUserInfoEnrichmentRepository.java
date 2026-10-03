package com.falconx.console.repository;

import com.falconx.console.repository.mapper.record.AdminUserInfoRecord;
import java.util.List;
import java.util.Map;

/**
 * 管理端通用「用户基本信息」enrichment 仓储。
 *
 * <p>暴露按 userId 批查用户 uid / 邮箱 / 姓名，返回 userId → record 的 map，方便各 application service
 * 给带 userId 的 admin 列表/详情 DTO 装配展示用的姓名 + 邮箱。沿用出金审核
 * {@link AdminWithdrawEnrichmentRepository} 的 best-effort 跨 schema enrichment 范式。
 */
public interface AdminUserInfoEnrichmentRepository {

    /**
     * 按 userId 集合批查用户基本信息。userIds 为空时立即返回空 map。
     *
     * @param userIds 待 enrich 的用户 ID 集合（自动去重，null 元素忽略）
     * @return userId → 用户基本信息记录（不存在的 userId 不在 map 中）
     */
    Map<Long, AdminUserInfoRecord> findByUserIds(List<Long> userIds);
}
