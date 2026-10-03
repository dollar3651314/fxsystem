package com.falconx.canary.command;

import com.falconx.canary.config.CanaryProperties;
import com.falconx.canary.report.CanaryReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * STAGE-11-OBS-RECON §11.2 canary recon 命令。
 *
 * <p>admin login → GET /admin/reconciliation/deposits/unmatched，
 * total &lt; canary.recon.maxUnmatched 则 PASS，否则 FAIL（告警 unmatched 项过多）。
 */
@Component
public class ReconCanaryCommand implements CanaryCommand {

    private static final Logger log = LoggerFactory.getLogger(ReconCanaryCommand.class);

    private final CanaryProperties properties;
    private final RestClient restClient;

    public ReconCanaryCommand(CanaryProperties properties, RestClient canaryRestClient) {
        this.properties = properties;
        this.restClient = canaryRestClient;
    }

    @Override
    public String name() {
        return "recon";
    }

    @Override
    public CanaryReport run(List<String> args) {
        Instant startedAt = Instant.now();
        String consoleUrl = properties.getBaseUrl().getConsole();
        if (consoleUrl == null || consoleUrl.isBlank()) {
            return CanaryReport.fail(name(), "canary.base-url.console 未配置");
        }
        String adminUser = properties.getAdmin().getUsername();
        String adminPass = properties.getAdmin().getPassword();
        if (adminUser == null || adminPass == null) {
            return CanaryReport.fail(name(), "canary.admin.username/password 未配置");
        }
        String accessToken;
        try {
            accessToken = adminLogin(consoleUrl, adminUser, adminPass);
        } catch (Exception ex) {
            return CanaryReport.fail(name(), startedAt,
                    "admin login failed: " + ex.getMessage(),
                    Map.of("consoleUrl", consoleUrl));
        }
        try {
            Map<?, ?> response = restClient.get()
                    .uri(consoleUrl + "/admin/reconciliation/deposits/unmatched?page=1&size=1")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("data") == null) {
                return CanaryReport.fail(name(), startedAt, "recon 响应 data 为空",
                        Map.of("consoleUrl", consoleUrl));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            Number total = (Number) data.get("total");
            long unmatched = total == null ? 0L : total.longValue();
            int threshold = properties.getRecon().getMaxUnmatched();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("total", unmatched);
            details.put("threshold", threshold);
            if (unmatched >= threshold) {
                return CanaryReport.fail(name(), startedAt,
                        "unmatched 项 " + unmatched + " 已达/超阈值 " + threshold, details);
            }
            return CanaryReport.pass(name(), startedAt, details);
        } catch (RestClientResponseException ex) {
            log.warn("canary.recon.http-error status={}", ex.getStatusCode());
            return CanaryReport.fail(name(), startedAt,
                    "HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(),
                    Map.of("consoleUrl", consoleUrl));
        } catch (Exception ex) {
            return CanaryReport.fail(name(), startedAt, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    Map.of("consoleUrl", consoleUrl));
        }
    }

    private String adminLogin(String consoleUrl, String username, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        Map<?, ?> response = restClient.post()
                .uri(consoleUrl + "/admin/auth/login")
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("data") == null) {
            throw new IllegalStateException("admin login response data null");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        String token = (String) data.get("accessToken");
        if (token == null) {
            throw new IllegalStateException("admin login missing accessToken");
        }
        return token;
    }
}
