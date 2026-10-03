package com.falconx.console.repository;

import com.falconx.console.repository.mapper.AdminWithdrawEnrichmentMapper;
import com.falconx.console.repository.mapper.record.AdminWithdrawEnrichmentRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminWithdrawEnrichmentRepository implements AdminWithdrawEnrichmentRepository {

    private final AdminWithdrawEnrichmentMapper mapper;

    public MybatisAdminWithdrawEnrichmentRepository(AdminWithdrawEnrichmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<Long, AdminWithdrawEnrichmentRecord> findEnrichmentMap(List<Long> userIds, OffsetDateTime dayUtc) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> deduped = new java.util.ArrayList<>(new LinkedHashSet<>(userIds));
        // 按调用方传入的 dayUtc 推 UTC 当日 [00:00, +1d)；mapper 入参用 LocalDateTime 以匹配 MySQL DATETIME 列。
        LocalDate day = dayUtc.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        List<AdminWithdrawEnrichmentRecord> rows = mapper.selectEnrichmentByUserIds(deduped, start, end);
        Map<Long, AdminWithdrawEnrichmentRecord> result = new HashMap<>(rows.size() * 2);
        for (AdminWithdrawEnrichmentRecord r : rows) {
            result.put(r.userId(), r);
        }
        return result;
    }
}
