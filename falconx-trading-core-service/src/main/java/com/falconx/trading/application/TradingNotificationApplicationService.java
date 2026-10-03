package com.falconx.trading.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingNotificationStatus;
import com.falconx.trading.entity.TradingNotificationTemplate;
import com.falconx.trading.notification.NotificationChannelDispatcher;
import com.falconx.trading.repository.TradingNotificationRepository;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-8-NOTIFICATION：站内信应用服务。
 *
 * <p>两条写路径：
 * <ul>
 *   <li>{@link #create}（旧）：producer 已拼好 title/body，直接落库 + WS 推送，
 *       适用于 Phase 0 反转前的 5 个调用点（STAGE-4/6/7 顺手实施时已就位）。Phase 1 触发点迁移后逐步淘汰。</li>
 *   <li>{@link #send}（新，Phase 0 R2 引入）：传 templateCode + params，由 NotificationTemplateService 渲染
 *       title/body，再遍历模板 channels 投递（IN_APP 写库 + WS / EMAIL / TELEGRAM stub）。</li>
 * </ul>
 *
 * <p>读路径：直接走 Repository（list / count / unread / markRead / markAllRead）保持不变。
 */
@Service
public class TradingNotificationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingNotificationApplicationService.class);

    private final TradingNotificationRepository repository;
    private final IdGenerator idGenerator;
    private final TradingUserRealtimePushService realtimePushService;
    private final NotificationTemplateService templateService;
    private final List<NotificationChannelDispatcher> dispatchers;

    public TradingNotificationApplicationService(TradingNotificationRepository repository,
                                                  IdGenerator idGenerator,
                                                  TradingUserRealtimePushService realtimePushService,
                                                  NotificationTemplateService templateService,
                                                  List<NotificationChannelDispatcher> dispatchers) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.realtimePushService = realtimePushService;
        this.templateService = templateService;
        this.dispatchers = dispatchers;
    }

    /**
     * 旧路径（V19 sql，title/body 由 producer 拼接）。Phase 0 R2 反转决策后保留兼容，
     * 不主动废弃；现有 5 个调用点（KYC/出金 confirmed/failed/价格告警/持仓平仓）按 Phase 1 计划迁移到 {@link #send}。
     */
    @Transactional
    public TradingNotification create(long userId,
                                       String type,
                                       TradingNotificationLevel level,
                                       String title,
                                       String body,
                                       String relatedKey,
                                       Long relatedId,
                                       String payloadJson) {
        TradingNotification notification = new TradingNotification(
                idGenerator.nextId(),
                userId,
                type,
                null,
                level == null ? TradingNotificationLevel.INFO : level,
                title,
                body,
                relatedKey,
                relatedId,
                payloadJson,
                TradingNotificationStatus.UNREAD,
                null,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        repository.insert(notification);
        log.info("trading.notification.created userId={} id={} type={} level={}",
                userId, notification.id(), type, notification.level());
        try {
            realtimePushService.publishNotificationCreated(notification);
        } catch (RuntimeException ex) {
            // WS 推送失败不影响落库，下次客户端拉列表能补
            log.warn("trading.notification.push-failed id={} reason={}", notification.id(), ex.toString());
        }
        return notification;
    }

    /**
     * 新路径（Phase 0 R2 引入）：模板化发送。
     *
     * <p>流程：
     * <ol>
     *   <li>调 {@link NotificationTemplateService#getRequiredEnabled} 取模板（disabled / 不存在 → NOTIFICATION_TEMPLATE_NOT_FOUND）</li>
     *   <li>渲染 title / body（{@code ${var}} → params 值，未提供保留原样 + log warn）</li>
     *   <li>构造 {@link TradingNotification} 含 {@code templateCode + type + level}（type 默认 = templateCode）</li>
     *   <li>遍历模板 channels，对每个 channel 找到 {@link NotificationChannelDispatcher#supports}=true 的 dispatcher 调 dispatch</li>
     *   <li>返回 IN_APP dispatch 后的 notification（含 id / createdAt）</li>
     * </ol>
     *
     * @param templateCode 模板 code（必须 enabled=1）
     * @param userId       目标用户
     * @param type         业务类型（与 t_notification.type 一致；通常 = templateCode）；null 时默认 templateCode
     * @param params       占位符值；null / 空 Map 时模板内 ${var} 全保留原样 + log warn
     * @param relatedKey   关联业务（PRICE_ALERT / POSITION / WITHDRAW / DEPOSIT / KYC / RISK），用于客户端 deep-link
     * @param relatedId   关联业务实体 ID
     * @param payloadJson 额外结构化数据（symbol / triggeredPrice / direction 等）
     * @return 已落库的 TradingNotification
     */
    @Transactional
    public TradingNotification send(String templateCode,
                                     long userId,
                                     String type,
                                     Map<String, String> params,
                                     String relatedKey,
                                     Long relatedId,
                                     String payloadJson) {
        TradingNotificationTemplate template = templateService.getRequiredEnabled(templateCode);

        String title = templateService.render(template.titleTemplate(), params);
        String body = templateService.render(template.bodyTemplate(), params);

        TradingNotification notification = new TradingNotification(
                idGenerator.nextId(),
                userId,
                type == null ? templateCode : type,
                templateCode,
                template.level(),
                title,
                body,
                relatedKey,
                relatedId,
                payloadJson,
                TradingNotificationStatus.UNREAD,
                null,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        log.info("trading.notification.send.received templateCode={} userId={} channels={}",
                templateCode, userId, template.channels());

        for (NotificationChannel channel : template.channels()) {
            NotificationChannelDispatcher dispatcher = findDispatcher(channel);
            if (dispatcher == null) {
                log.warn("trading.notification.send.no-dispatcher templateCode={} channel={}",
                        templateCode, channel);
                continue;
            }
            dispatcher.dispatch(notification);
        }
        return notification;
    }

    /** 最简 overload：type 默认 templateCode；无 relatedKey/relatedId/payloadJson。 */
    public TradingNotification send(String templateCode, long userId, Map<String, String> params) {
        return send(templateCode, userId, templateCode, params, null, null, null);
    }

    private NotificationChannelDispatcher findDispatcher(NotificationChannel channel) {
        for (NotificationChannelDispatcher d : dispatchers) {
            if (d.supports(channel)) return d;
        }
        return null;
    }

    public List<TradingNotification> list(long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return repository.findByUserId(userId, (safePage - 1) * safeSize, safeSize);
    }

    public long count(long userId) {
        return repository.countByUserId(userId);
    }

    public int countUnread(long userId) {
        return repository.countUnreadByUserId(userId);
    }

    @Transactional
    public boolean markRead(long userId, long id) {
        return repository.markRead(id, userId);
    }

    @Transactional
    public int markAllRead(long userId) {
        return repository.markAllRead(userId);
    }
}
