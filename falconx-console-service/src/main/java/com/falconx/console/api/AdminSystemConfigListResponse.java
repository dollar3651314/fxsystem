package com.falconx.console.api;

import com.falconx.console.entity.SystemConfigEntry;
import java.time.OffsetDateTime;
import java.util.List;

/** STAGE-13 GET /admin/system-config 列表响应。 */
public record AdminSystemConfigListResponse(List<Item> items) {

    public record Item(
            Long id,
            String configKey,
            String configValue,
            String valueType,
            String category,
            String scope,
            String description,
            String defaultValue,
            String validationRegex,
            boolean isSensitive,
            Long updatedBy,
            OffsetDateTime updatedAt
    ) {
        public static Item from(SystemConfigEntry e) {
            return new Item(
                    e.id(),
                    e.configKey(),
                    e.isSensitive() ? "***" : e.configValue(),
                    e.valueType().name(),
                    e.category().name(),
                    e.scope(),
                    e.description(),
                    e.defaultValue(),
                    e.validationRegex(),
                    e.isSensitive(),
                    e.updatedBy(),
                    e.updatedAt()
            );
        }
    }
}
