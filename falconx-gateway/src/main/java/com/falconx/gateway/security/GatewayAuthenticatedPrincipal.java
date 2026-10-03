package com.falconx.gateway.security;

/**
 * gateway 鉴权后的最小主体对象。
 *
 * <p>该对象承载 gateway 从 Access Token 中解析出的用户主键、UID 和状态，
 * 用于向下游服务透传标准用户头信息。
 */
public record GatewayAuthenticatedPrincipal(
        String userId,
        String uid,
        String status,
        String groupCode,
        String jti
) {
}
