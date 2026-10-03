package com.falconx.console.internal;

import com.falconx.console.repository.AdminUserInfoEnrichmentRepository;
import com.falconx.console.repository.mapper.record.AdminUserInfoRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 管理端「用户基本信息」通用 enrich 助手。
 *
 * <p>给任意带 userId 的 admin 列表/详情 DTO 批量补充展示用的 uid / 邮箱 / 姓名：一次跨 schema SQL 取全部
 * userId（{@link AdminUserInfoEnrichmentRepository}），再用调用方提供的 applier 把记录装配回各自的 DTO。
 *
 * <p><b>best-effort</b>：enrichment 失败（DB 异常 / 用户不存在）时返回原 items 或保留原值，绝不阻塞 admin
 * 工作流——与出金审核 {@code AdminWithdrawApplicationService.enrichItems} 同口径，只是把批查 + 容错收敛到
 * 一处复用。
 */
@Component
public class AdminUserInfoEnricher {

    private static final Logger log = LoggerFactory.getLogger(AdminUserInfoEnricher.class);

    private final AdminUserInfoEnrichmentRepository repository;

    public AdminUserInfoEnricher(AdminUserInfoEnrichmentRepository repository) {
        this.repository = repository;
    }

    /**
     * 批量 enrich 一组 DTO。
     *
     * @param items   待 enrich 的 DTO 列表（null / 空直接原样返回）
     * @param idOf    从 DTO 取 userId（返回 null 表示该项无关联用户，跳过 enrich）
     * @param applier 给定 (原 DTO, 用户信息记录) 返回补好字段的新 DTO
     * @return 补好用户信息的新列表；失败时返回原 items
     */
    public <T> List<T> enrich(List<T> items,
                              Function<T, Long> idOf,
                              BiFunction<T, AdminUserInfoRecord, T> applier) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        List<Long> userIds = new ArrayList<>(items.size());
        for (T it : items) {
            Long id = safeId(idOf, it);
            if (id != null) {
                userIds.add(id);
            }
        }
        if (userIds.isEmpty()) {
            return items;
        }
        Map<Long, AdminUserInfoRecord> infos;
        try {
            infos = repository.findByUserIds(userIds);
        } catch (Exception ex) {
            log.warn("admin.user-info.enrich.failed size={} reason={}", userIds.size(), ex.toString());
            return items;
        }
        List<T> out = new ArrayList<>(items.size());
        for (T it : items) {
            Long id = safeId(idOf, it);
            AdminUserInfoRecord r = id == null ? null : infos.get(id);
            out.add(r == null ? it : applier.apply(it, r));
        }
        return out;
    }

    /**
     * 单条 enrich（详情场景）：取一个 userId 的用户信息记录，不存在 / 异常时返回 null（调用方保留原值）。
     */
    public AdminUserInfoRecord resolveOne(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return repository.findByUserIds(List.of(userId)).get(userId);
        } catch (Exception ex) {
            log.warn("admin.user-info.resolve.failed userId={} reason={}", userId, ex.toString());
            return null;
        }
    }

    private static <T> Long safeId(Function<T, Long> idOf, T it) {
        try {
            return idOf.apply(it);
        } catch (RuntimeException ignored) {
            // idOf 内部解析失败（如非数字 userId 字符串）跳过该项
            return null;
        }
    }
}
