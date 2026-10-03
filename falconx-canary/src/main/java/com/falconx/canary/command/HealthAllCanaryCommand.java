package com.falconx.canary.command;

import com.falconx.canary.config.CanaryProperties;
import com.falconx.canary.report.CanaryReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * STAGE-11-OBS-RECON §11.2 canary health-all 命令。
 *
 * <p>并发请求 6 服务 {@code /actuator/health}；全 UP 则 exitCode=0，任一 DOWN/不可达 → exitCode=1。
 */
@Component
public class HealthAllCanaryCommand implements CanaryCommand {

    private static final Logger log = LoggerFactory.getLogger(HealthAllCanaryCommand.class);

    private final CanaryProperties properties;
    private final RestClient restClient;

    public HealthAllCanaryCommand(CanaryProperties properties, RestClient canaryRestClient) {
        this.properties = properties;
        this.restClient = canaryRestClient;
    }

    @Override
    public String name() {
        return "health-all";
    }

    @Override
    public CanaryReport run(List<String> args) {
        Instant startedAt = Instant.now();
        Map<String, String> services = collectServices();
        if (services.isEmpty()) {
            return CanaryReport.fail(name(), "canary.base-url.* 6 服务全未配置");
        }
        Map<String, Object> per = new ConcurrentHashMap<>();
        services.entrySet().parallelStream().forEach(e -> per.put(e.getKey(), probe(e.getValue())));

        long down = per.values().stream()
                .filter(v -> v instanceof Map<?, ?> m && !"UP".equals(m.get("status")))
                .count();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("services", per);
        if (down > 0) {
            return CanaryReport.fail(name(), startedAt, down + " 个服务非 UP", details);
        }
        return CanaryReport.pass(name(), startedAt, details);
    }

    private Map<String, String> collectServices() {
        Map<String, String> services = new LinkedHashMap<>();
        addIfPresent(services, "gateway", properties.getBaseUrl().getGateway());
        addIfPresent(services, "identity", properties.getBaseUrl().getIdentity());
        addIfPresent(services, "market", properties.getBaseUrl().getMarket());
        addIfPresent(services, "trading-core", properties.getBaseUrl().getTradingCore());
        addIfPresent(services, "wallet", properties.getBaseUrl().getWallet());
        addIfPresent(services, "console", properties.getBaseUrl().getConsole());
        return services;
    }

    private static void addIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> probe(String baseUrl) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("baseUrl", baseUrl);
        try {
            Map<?, ?> response = restClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                r.put("status", "DOWN");
                r.put("reason", "response null");
                return r;
            }
            Object status = response.get("status");
            r.put("status", status == null ? "DOWN" : status.toString());
            return r;
        } catch (RestClientResponseException ex) {
            log.warn("canary.health-all.http-error baseUrl={} status={}", baseUrl, ex.getStatusCode());
            r.put("status", "DOWN");
            r.put("httpStatus", ex.getStatusCode().value());
            return r;
        } catch (Exception ex) {
            r.put("status", "DOWN");
            r.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return r;
        }
    }
}
