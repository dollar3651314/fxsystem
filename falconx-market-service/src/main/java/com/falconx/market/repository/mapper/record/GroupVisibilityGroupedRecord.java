package com.falconx.market.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 用户组可见性聚合视图记录（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B）。
 *
 * <p>SQL 通过 {@code SET SESSION group_concat_max_len=1048576 + GROUP_CONCAT(symbol)}
 * 聚合返回；service 层把逗号分隔的 visibleSymbols 拆成 List。
 */
public record GroupVisibilityGroupedRecord(
        String groupCode,
        Integer visibleCount,
        String visibleSymbols,
        LocalDateTime lastModifiedAt
) {
}
