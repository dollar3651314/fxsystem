package com.falconx.console.api;

import com.falconx.console.entity.SystemConfigAudit;
import java.time.OffsetDateTime;
import java.util.List;

/** STAGE-13 GET /admin/system-config/{configKey}/history 响应。 */
public record AdminSystemConfigAuditResponse(List<Item> items) {

    public record Item(
            Long id,
            String configKey,
            String oldValue,
            String newValue,
            String action,
            Long operatorId,
            String operatorEmail,
            String clientIp,
            String reason,
            OffsetDateTime createdAt
    ) {
        public static Item from(SystemConfigAudit a) {
            return new Item(
                    a.id(), a.configKey(), a.oldValue(), a.newValue(),
                    a.action().name(), a.operatorId(), a.operatorEmail(),
                    a.clientIp(), a.reason(), a.createdAt()
            );
        }
    }
}
