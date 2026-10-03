package com.falconx.console.security;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 IP 白名单 filter（console 应用级，作为 gateway 路由级 IP 白名单的兜底防线）。
 *
 * <p>仅拦截 {@code /admin/**} 请求；按 {@link ConsoleServiceProperties.Security#isIpWhitelistEnabled()}
 * 决定是否启用：
 *
 * <ul>
 *   <li>{@code dev} / {@code test} profile 下 {@code ipWhitelistEnabled=false}，全放行</li>
 *   <li>{@code prod} / {@code staging} 下必须启用，{@code ipWhitelist} 为空时拒绝所有请求</li>
 * </ul>
 *
 * <p>IP 来源优先级：
 * <ol>
 *   <li>{@code X-Forwarded-For} header 第一段（gateway 透传真实客户端 IP）</li>
 *   <li>{@link HttpServletRequest#getRemoteAddr()}（直连场景兜底）</li>
 * </ol>
 *
 * <p>白名单格式支持：
 * <ul>
 *   <li>具体 IPv4 地址：{@code 10.0.1.5}</li>
 *   <li>CIDR：{@code 10.0.0.0/8}、{@code 192.168.1.0/24}</li>
 *   <li>IPv6 暂不支持（一期管理后台仅内网 IPv4）</li>
 * </ul>
 *
 * <p>失败时直接返回 {@code 90005} JSON，不抛异常。
 */
public class AdminIpWhitelistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminIpWhitelistFilter.class);

    private final ConsoleServiceProperties properties;
    private final ObjectMapper objectMapper;

    public AdminIpWhitelistFilter(ConsoleServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.getSecurity().isIpWhitelistEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientIp = resolveClientIp(request);
        if (!isWhitelisted(clientIp)) {
            log.warn("admin.ip-whitelist.rejected ip={} path={}", clientIp, request.getRequestURI());
            writeForbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma == -1 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isWhitelisted(String clientIp) {
        List<String> whitelist = properties.getSecurity().getIpWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        long clientLong;
        try {
            clientLong = ipv4ToLong(clientIp);
        } catch (UnknownHostException ex) {
            log.warn("admin.ip-whitelist.parse-failure ip={} reason={}", clientIp, ex.getMessage());
            return false;
        }
        for (String entry : whitelist) {
            if (matches(entry, clientLong, clientIp)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String entry, long clientLong, String clientIp) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String trimmed = entry.trim();
        try {
            int slash = trimmed.indexOf('/');
            if (slash == -1) {
                return ipv4ToLong(trimmed) == clientLong;
            }
            String network = trimmed.substring(0, slash);
            int prefix = Integer.parseInt(trimmed.substring(slash + 1));
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long mask = prefix == 0 ? 0L : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
            long networkLong = ipv4ToLong(network) & mask;
            return (clientLong & mask) == networkLong;
        } catch (UnknownHostException | NumberFormatException ex) {
            log.warn("admin.ip-whitelist.entry-invalid entry={} clientIp={} reason={}",
                    entry, clientIp, ex.getMessage());
            return false;
        }
    }

    private long ipv4ToLong(String ip) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ip);
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            throw new UnknownHostException("Not an IPv4 address: " + ip);
        }
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFFL);
        }
        return result;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        ApiResponse<Object> body = new ApiResponse<>(
                AdminErrorCode.ADMIN_IP_NOT_WHITELISTED.code(),
                AdminErrorCode.ADMIN_IP_NOT_WHITELISTED.message(),
                null,
                OffsetDateTime.now(),
                traceId
        );
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (JacksonException ex) {
            log.error("admin.ip-whitelist.serialize.failure code={} message={}",
                    AdminErrorCode.ADMIN_IP_NOT_WHITELISTED.code(), ex.getMessage());
        }

        // 隐式 IP 列表自检（仅 dev 帮助调试，生产环境不打印）
        List<String> entries = new ArrayList<>(properties.getSecurity().getIpWhitelist());
        log.debug("admin.ip-whitelist.config entries={}", entries);
    }
}
