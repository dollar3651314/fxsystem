package com.falconx.console.repository;

import com.falconx.console.repository.mapper.AdminUserInfoEnrichmentMapper;
import com.falconx.console.repository.mapper.record.AdminUserInfoRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * {@link AdminUserInfoEnrichmentRepository} 的 MyBatis 跨 schema 实现。
 *
 * <p>一次 IN 查询拿全部 userId 的用户基本信息，调用方在 list() 阶段批量装配，避免 N+1。
 */
@Repository
public class MybatisAdminUserInfoEnrichmentRepository implements AdminUserInfoEnrichmentRepository {

    private final AdminUserInfoEnrichmentMapper mapper;

    public MybatisAdminUserInfoEnrichmentRepository(AdminUserInfoEnrichmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<Long, AdminUserInfoRecord> findByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> deduped = new ArrayList<>(new LinkedHashSet<>(
                userIds.stream().filter(Objects::nonNull).toList()));
        if (deduped.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AdminUserInfoRecord> rows = mapper.selectByUserIds(deduped);
        Map<Long, AdminUserInfoRecord> result = new HashMap<>(rows.size() * 2);
        for (AdminUserInfoRecord r : rows) {
            result.put(r.userId(), r);
        }
        return result;
    }
}
