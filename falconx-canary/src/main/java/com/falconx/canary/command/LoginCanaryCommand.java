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
 * STAGE-11-OBS-RECON §11.2 canary login 命令。
 *
 * <p>调 identity-service POST /api/v1/auth/login，校验返回 access/refresh token 非空。
 */
@Component
public class LoginCanaryCommand implements CanaryCommand {

    private static final Logger log = LoggerFactory.getLogger(LoginCanaryCommand.class);

    private final CanaryProperties properties;
    private final RestClient restClient;

    public LoginCanaryCommand(CanaryProperties properties, RestClient canaryRestClient) {
        this.properties = properties;
        this.restClient = canaryRestClient;
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public CanaryReport run(List<String> args) {
        Instant startedAt = Instant.now();
        String baseUrl = properties.getBaseUrl().getIdentity();
        if (baseUrl == null || baseUrl.isBlank()) {
            return CanaryReport.fail(name(), "canary.base-url.identity 未配置");
        }
        String username = properties.getUser().getUsername();
        String password = properties.getUser().getPassword();
        if (username == null || password == null) {
            return CanaryReport.fail(name(), "canary.user.username/password 未配置");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", username);
        body.put("password", password);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/api/v1/auth/login")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("data") == null) {
                return CanaryReport.fail(name(), startedAt, "login 响应 data 为空", details(baseUrl, username, null));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String accessToken = (String) data.get("accessToken");
            String refreshToken = (String) data.get("refreshToken");
            if (accessToken == null || refreshToken == null) {
                return CanaryReport.fail(name(), startedAt, "login 响应缺 accessToken/refreshToken",
                        details(baseUrl, username, response.get("code")));
            }
            return CanaryReport.pass(name(), startedAt, details(baseUrl, username, "access+refresh OK"));
        } catch (RestClientResponseException ex) {
            log.warn("canary.login.http-error status={} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return CanaryReport.fail(name(), startedAt,
                    "HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(),
                    details(baseUrl, username, ex.getStatusCode().value()));
        } catch (Exception ex) {
            return CanaryReport.fail(name(), startedAt, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    details(baseUrl, username, null));
        }
    }

    private static Map<String, Object> details(String baseUrl, String username, Object extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("baseUrl", baseUrl);
        d.put("username", username);
        if (extra != null) {
            d.put("extra", extra);
        }
        return d;
    }
}
