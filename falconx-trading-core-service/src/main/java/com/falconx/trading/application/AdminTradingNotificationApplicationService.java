package com.falconx.trading.application;

import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingNotificationTemplate;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingNotificationRepository;
import com.falconx.trading.repository.TradingNotificationTemplateRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-8-NOTIFICATION Phase 3：管理端通知 + 模板 admin 应用服务。
 *
 * <p>承接 console-service 通过 gateway 转发到的 internal RPC，负责：
 * <ol>
 *   <li>模板 CRUD（list / detail / create / update / softDelete）+ 内置模板保护</li>
 *   <li>通知 list / detail（跨用户 admin 视图）</li>
 *   <li>手动发送通知（带 reason 审计字段，复用 TradingNotificationApplicationService.send）</li>
 * </ol>
 */
@Service
public class AdminTradingNotificationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminTradingNotificationApplicationService.class);

    /** 模板 code 正则：全大写 + 数字 + 下划线，至少 3 字符。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    /** 系统内置模板前缀，禁止删除。 */
    private static final List<String> BUILT_IN_PREFIXES = List.of(
            "PRICE_ALERT_", "POSITION_", "KYC_", "WITHDRAW_", "DEPOSIT_", "RISK_"
    );

    private final TradingNotificationTemplateRepository templateRepository;
    private final TradingNotificationRepository notificationRepository;
    private final NotificationTemplateService templateService;
    private final TradingNotificationApplicationService notificationService;

    public AdminTradingNotificationApplicationService(TradingNotificationTemplateRepository templateRepository,
                                                       TradingNotificationRepository notificationRepository,
                                                       NotificationTemplateService templateService,
                                                       TradingNotificationApplicationService notificationService) {
        this.templateRepository = templateRepository;
        this.notificationRepository = notificationRepository;
        this.templateService = templateService;
        this.notificationService = notificationService;
    }

    // ---------- 模板 CRUD ----------

    public List<TradingNotificationTemplate> listTemplates(Boolean enabled, Integer levelCode, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return templateRepository.findPaginated(enabled, levelCode, (safePage - 1) * safeSize, safeSize);
    }

    public long countTemplates(Boolean enabled, Integer levelCode) {
        return templateRepository.countFiltered(enabled, levelCode);
    }

    public TradingNotificationTemplate getTemplate(String code) {
        return templateService.findAny(code)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND));
    }

    @Transactional
    public TradingNotificationTemplate createTemplate(String code, String titleTemplate, String bodyTemplate,
                                                       TradingNotificationLevel level,
                                                       List<NotificationChannel> channels,
                                                       String description, boolean enabled) {
        validateCode(code);
        if (channels == null || channels.isEmpty()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TradingNotificationTemplate template = new TradingNotificationTemplate(
                code, titleTemplate, bodyTemplate,
                level == null ? TradingNotificationLevel.INFO : level,
                channels, description, enabled, now, now);
        try {
            templateRepository.insert(template);
        } catch (DuplicateKeyException ex) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_CODE_DUPLICATE);
        }
        templateService.evict(code);
        log.info("trading.admin.notification.template.created code={} level={} channels={} enabled={}",
                code, level, channels, enabled);
        return template;
    }

    @Transactional
    public TradingNotificationTemplate updateTemplate(String code, String titleTemplate, String bodyTemplate,
                                                       TradingNotificationLevel level,
                                                       List<NotificationChannel> channels,
                                                       String description, boolean enabled) {
        // 必须存在；不存在不创建
        Optional<TradingNotificationTemplate> existing = templateRepository.findByCode(code);
        if (existing.isEmpty()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        if (channels == null || channels.isEmpty()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TradingNotificationTemplate updated = new TradingNotificationTemplate(
                code, titleTemplate, bodyTemplate,
                level == null ? TradingNotificationLevel.INFO : level,
                channels, description, enabled,
                existing.get().createdAt(), now);
        boolean ok = templateRepository.update(updated);
        if (!ok) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        templateService.evict(code);
        log.info("trading.admin.notification.template.updated code={} level={} enabled={}", code, level, enabled);
        return updated;
    }

    @Transactional
    public void softDeleteTemplate(String code) {
        if (isBuiltInTemplate(code)) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_IN_USE);
        }
        Optional<TradingNotificationTemplate> existing = templateRepository.findByCode(code);
        if (existing.isEmpty()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        boolean ok = templateRepository.softDelete(code);
        if (!ok) {
            // 已是 disabled，幂等返回成功
            log.info("trading.admin.notification.template.softDelete.already-disabled code={}", code);
        }
        templateService.evict(code);
        log.info("trading.admin.notification.template.softDeleted code={}", code);
    }

    // ---------- 通知 list / detail / send ----------

    public List<TradingNotification> listNotifications(Long userId, String type, String templateCode,
                                                       Integer levelCode, Integer statusCode,
                                                       OffsetDateTime fromCreatedAt, OffsetDateTime toCreatedAt,
                                                       int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return notificationRepository.findByAdminFilters(
                userId, type, templateCode, levelCode, statusCode,
                toLocal(fromCreatedAt), toLocal(toCreatedAt),
                (safePage - 1) * safeSize, safeSize);
    }

    public long countNotifications(Long userId, String type, String templateCode,
                                    Integer levelCode, Integer statusCode,
                                    OffsetDateTime fromCreatedAt, OffsetDateTime toCreatedAt) {
        return notificationRepository.countByAdminFilters(
                userId, type, templateCode, levelCode, statusCode,
                toLocal(fromCreatedAt), toLocal(toCreatedAt));
    }

    public TradingNotification getNotification(long id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new TradingBusinessException(TradingErrorCode.NOTIFICATION_NOT_FOUND));
    }

    /**
     * 手动发送：管理端运营测试 / 紧急通知。
     *
     * <p>reason 仅由 console 端审计 AOP 写入 t_admin_operation_log，本服务不负责审计；
     * 但记录到本地日志便于追溯。
     */
    @Transactional
    public TradingNotification sendManual(long userId, String templateCode,
                                           Map<String, String> params, String reason) {
        // trading-core 不持有 t_user（identity 才是 owner），不做 user 存在校验：
        // V2 一期管理端手动发送目标 userId 由运营保证存在；仅 userId<=0 兜底防御。
        if (userId <= 0) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_USER_NOT_FOUND);
        }
        log.info("trading.admin.notification.manual.send userId={} templateCode={} reason={}",
                userId, templateCode, reason);
        return notificationService.send(templateCode, userId, templateCode, params, "ADMIN_MANUAL", null, null);
    }

    // ---------- 内部 ----------

    private void validateCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
    }

    private boolean isBuiltInTemplate(String code) {
        if (code == null) return false;
        for (String prefix : BUILT_IN_PREFIXES) {
            if (code.startsWith(prefix)) return true;
        }
        return false;
    }

    private static LocalDateTime toLocal(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
