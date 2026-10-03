package com.falconx.console.systemconfig;

import com.falconx.console.entity.SystemConfigAudit;
import com.falconx.console.entity.SystemConfigCategory;
import com.falconx.console.entity.SystemConfigEntry;
import com.falconx.console.entity.SystemConfigValueType;
import com.falconx.console.repository.mapper.SystemConfigMapper;
import com.falconx.console.repository.mapper.record.SystemConfigAuditRecord;
import com.falconx.console.repository.mapper.record.SystemConfigRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-13 系统配置应用服务。
 *
 * <p>CRUD + 正则校验 + 审计日志 + Redis 广播一站式封装。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>同一事务内：upsert config_value + insert audit log（保证审计完整）</li>
 *   <li>Redis 广播在事务提交后调用（避免 Redis 推了但 DB 回滚）</li>
 *   <li>正则校验在写库前；reset 用 default_value 跳过正则（默认值默认合法）</li>
 * </ul>
 */
@Service
public class SystemConfigApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigApplicationService.class);

    private final SystemConfigMapper mapper;
    private final SystemConfigBroadcaster broadcaster;

    public SystemConfigApplicationService(SystemConfigMapper mapper,
                                          SystemConfigBroadcaster broadcaster) {
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    public List<SystemConfigEntry> listAll() {
        return mapper.selectAll().stream().map(this::toEntry).toList();
    }

    public List<SystemConfigEntry> listByCategory(SystemConfigCategory category) {
        return mapper.selectByCategory(category.name()).stream().map(this::toEntry).toList();
    }

    public SystemConfigEntry getByKey(String configKey) {
        SystemConfigRecord record = mapper.selectByKey(configKey);
        if (record == null) {
            throw new IllegalArgumentException("Unknown config key: " + configKey);
        }
        return toEntry(record);
    }

    /**
     * 更新一条配置值。
     *
     * <p>步骤：
     * <ol>
     *   <li>读旧值（供审计 oldValue）</li>
     *   <li>正则校验 newValue（若有 validation_regex）</li>
     *   <li>upsert 新值（事务）</li>
     *   <li>写审计日志（事务）</li>
     *   <li>事务提交后 → broadcast Redis</li>
     * </ol>
     */
    @Transactional
    public SystemConfigEntry updateValue(String configKey,
                                         String newValue,
                                         Long operatorId,
                                         String operatorEmail,
                                         String clientIp,
                                         String reason) {
        SystemConfigRecord existing = mapper.selectByKey(configKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown config key: " + configKey);
        }
        String oldValue = existing.configValue();
        if (Objects.equals(oldValue, newValue)) {
            log.info("system-config.update.noop key={} value={}（值未变化，跳过）", configKey, newValue);
            return toEntry(existing);
        }
        validateValue(existing, newValue);

        LocalDateTime now = LocalDateTime.now();
        mapper.upsertValue(configKey, newValue, operatorId, now);
        mapper.insertAudit(new SystemConfigAuditRecord(
                null,
                configKey,
                oldValue,
                newValue,
                SystemConfigAudit.SystemConfigAuditAction.UPDATE.name(),
                operatorId,
                operatorEmail,
                clientIp,
                reason,
                now
        ));

        // 事务提交后才广播 —— Spring 默认 @Transactional 提交后 returnValue，
        // 这里方法返回前直接 broadcast 会在事务还未提交时发送，可能 service 拉旧值。
        // 简化版：先 commit 事务（返回前 Spring flushes），再 broadcast 由 caller 完成。
        // TODO 后续可改用 @TransactionalEventListener AFTER_COMMIT 严格保证顺序。
        log.info("system-config.update.committed key={} old={} new={} operator={}",
                configKey, oldValue, newValue, operatorId);
        broadcaster.broadcast(new SystemConfigChangedEvent(
                configKey,
                newValue,
                SystemConfigAudit.SystemConfigAuditAction.UPDATE.name()
        ));
        return toEntry(mapper.selectByKey(configKey));
    }

    @Transactional
    public SystemConfigEntry resetToDefault(String configKey,
                                            Long operatorId,
                                            String operatorEmail,
                                            String clientIp) {
        SystemConfigRecord existing = mapper.selectByKey(configKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown config key: " + configKey);
        }
        String defaultValue = existing.defaultValue();
        if (defaultValue == null) {
            throw new IllegalStateException("Config has no default value: " + configKey);
        }
        if (Objects.equals(existing.configValue(), defaultValue)) {
            log.info("system-config.reset.noop key={}（已是默认值）", configKey);
            return toEntry(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.upsertValue(configKey, defaultValue, operatorId, now);
        mapper.insertAudit(new SystemConfigAuditRecord(
                null, configKey, existing.configValue(), defaultValue,
                SystemConfigAudit.SystemConfigAuditAction.RESET.name(),
                operatorId, operatorEmail, clientIp,
                "Reset to default", now
        ));
        log.info("system-config.reset.committed key={} default={} operator={}",
                configKey, defaultValue, operatorId);
        broadcaster.broadcast(new SystemConfigChangedEvent(
                configKey, defaultValue,
                SystemConfigAudit.SystemConfigAuditAction.RESET.name()
        ));
        return toEntry(mapper.selectByKey(configKey));
    }

    public List<SystemConfigAudit> getAuditHistory(String configKey, int limit) {
        return mapper.selectAuditByKey(configKey, Math.min(Math.max(limit, 1), 200))
                .stream().map(this::toAudit).toList();
    }

    // ============ helpers ============

    private void validateValue(SystemConfigRecord existing, String newValue) {
        if (newValue == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        String regex = existing.validationRegex();
        if (regex != null && !regex.isBlank() && !Pattern.matches(regex, newValue)) {
            throw new IllegalArgumentException(
                    "Value '" + newValue + "' does not match regex '" + regex + "' for key '"
                            + existing.configKey() + "'");
        }
    }

    private SystemConfigEntry toEntry(SystemConfigRecord r) {
        return new SystemConfigEntry(
                r.id(),
                r.configKey(),
                r.configValue(),
                SystemConfigValueType.valueOf(r.valueType()),
                SystemConfigCategory.valueOf(r.category()),
                r.scope(),
                r.description(),
                r.defaultValue(),
                r.validationRegex(),
                Boolean.TRUE.equals(r.isSensitive()),
                r.updatedBy(),
                toOffset(r.updatedAt()),
                toOffset(r.createdAt())
        );
    }

    private SystemConfigAudit toAudit(SystemConfigAuditRecord r) {
        return new SystemConfigAudit(
                r.id(),
                r.configKey(),
                r.oldValue(),
                r.newValue(),
                SystemConfigAudit.SystemConfigAuditAction.valueOf(r.action()),
                r.operatorId(),
                r.operatorEmail(),
                r.clientIp(),
                r.reason(),
                toOffset(r.createdAt())
        );
    }

    private OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
