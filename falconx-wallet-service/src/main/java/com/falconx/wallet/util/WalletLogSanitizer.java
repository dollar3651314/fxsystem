package com.falconx.wallet.util;

import java.net.URI;

/**
 * wallet-service 日志脱敏工具。
 */
public final class WalletLogSanitizer {

    private static final String REDACTED = "[REDACTED]";

    private WalletLogSanitizer() {
    }

    public static String maskRpcUrl(URI rpcUrl) {
        if (rpcUrl == null) {
            return "none";
        }
        if (rpcUrl.getHost() == null) {
            return REDACTED;
        }

        StringBuilder masked = new StringBuilder();
        if (rpcUrl.getScheme() != null && !rpcUrl.getScheme().isBlank()) {
            masked.append(rpcUrl.getScheme()).append("://");
        }
        masked.append(rpcUrl.getHost());
        if (rpcUrl.getPort() > 0) {
            masked.append(':').append(rpcUrl.getPort());
        }

        String rawPath = rpcUrl.getRawPath();
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            return masked.toString();
        }
        String normalizedPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        String[] segments = normalizedPath.split("/", -1);
        if (segments.length == 0 || segments[0].isBlank()) {
            return masked.append('/').append(REDACTED).toString();
        }
        masked.append('/').append(segments[0]);
        if (segments.length > 1 && !segments[1].isBlank()) {
            masked.append('/').append(REDACTED);
        }
        return masked.toString();
    }
}
