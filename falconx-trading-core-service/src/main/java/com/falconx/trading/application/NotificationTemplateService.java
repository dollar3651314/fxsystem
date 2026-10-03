package com.falconx.trading.application;

import com.falconx.trading.entity.TradingNotificationTemplate;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingNotificationTemplateRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-8-NOTIFICATION Phase 1：模板查询 + 缓存 + 渲染。
 *
 * <p>缓存策略：进程内 ConcurrentHashMap，无 TTL；CRUD 时主动 evict。
 * 多实例部署时各实例独立缓存，admin 写后其他实例最长 0 延迟（默认不过期，需重启或显式 evict）。
 * 由于模板 CRUD 频次极低（运营操作），此方案在 V2 一期可接受；后续多实例化时切到 Redis。
 *
 * <p>插值规约：{@code ${variable}} 占位符 + Map&lt;String,String&gt; params + 简单 String.replace。
 * 未提供的变量保留原样（{@code ${var}}）+ log warn。
 */
@Service
public class NotificationTemplateService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateService.class);

    private final TradingNotificationTemplateRepository repository;
    private final ConcurrentHashMap<String, TradingNotificationTemplate> cache = new ConcurrentHashMap<>();

    public NotificationTemplateService(TradingNotificationTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 code 取启用中的模板；未找到或 disabled → 抛 {@link TradingErrorCode#NOTIFICATION_TEMPLATE_NOT_FOUND}。
     */
    public TradingNotificationTemplate getRequiredEnabled(String code) {
        TradingNotificationTemplate cached = cache.get(code);
        if (cached != null) {
            if (!cached.enabled()) {
                throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
            }
            return cached;
        }
        Optional<TradingNotificationTemplate> loaded = repository.findByCode(code);
        if (loaded.isEmpty()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        TradingNotificationTemplate template = loaded.get();
        cache.putIfAbsent(code, template);
        if (!template.enabled()) {
            throw new TradingBusinessException(TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    /** 任意状态查询（含 disabled）；不抛异常。 admin CRUD 用。 */
    public Optional<TradingNotificationTemplate> findAny(String code) {
        TradingNotificationTemplate cached = cache.get(code);
        if (cached != null) return Optional.of(cached);
        Optional<TradingNotificationTemplate> loaded = repository.findByCode(code);
        loaded.ifPresent(t -> cache.putIfAbsent(code, t));
        return loaded;
    }

    /** CRUD 写后清缓存，下次读重新加载（admin 操作低频）。 */
    public void evict(String code) {
        cache.remove(code);
        log.info("trading.notification.template.cache.evicted code={}", code);
    }

    /** 完整 reload（用于启动预热或全量刷新）。 */
    public void evictAll() {
        cache.clear();
        log.info("trading.notification.template.cache.cleared");
    }

    /**
     * 渲染模板：把 {@code ${var}} 占位符替换为 {@code params.get(var)}；
     * 未提供的变量保留原样 + log warn。
     */
    public String render(String template, Map<String, String> params) {
        if (template == null || template.isEmpty()) return template;
        if (params == null || params.isEmpty()) {
            warnUnresolvedPlaceholders(template, params);
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        warnUnresolvedPlaceholders(result, params);
        return result;
    }

    private void warnUnresolvedPlaceholders(String rendered, Map<String, String> params) {
        if (rendered == null) return;
        int idx = 0;
        while ((idx = rendered.indexOf("${", idx)) >= 0) {
            int end = rendered.indexOf('}', idx);
            if (end < 0) break;
            String var = rendered.substring(idx + 2, end);
            log.warn("trading.notification.template.render.missing-param var={} params={}",
                    var, params == null ? "null" : params.keySet());
            idx = end + 1;
        }
    }
}
