package com.falconx.common.error;

/**
 * 通用系统级错误码骨架（{@code 99001-99099} 系统段）。
 *
 * <p>这里只放跨服务都可能复用的最小系统级错误码，
 * 具体业务错误码仍应按 REST 规范放到对应服务的错误码枚举中：
 * <ul>
 *   <li>{@code 1xxxx} identity / gateway 鉴权 / 路由限流</li>
 *   <li>{@code 2xxxx} wallet</li>
 *   <li>{@code 3xxxx} trading</li>
 *   <li>{@code 90001-90899} 管理端（参见 {@code AdminErrorCode}）</li>
 *   <li>{@code 99001-99099} 系统级跨服务通用（本枚举）</li>
 * </ul>
 *
 * <p>历史上本枚举曾占用 {@code 90001-90004}，与管理端鉴权段冲突；
 * 2026-05-09 FX-069 决策迁到 {@code 99xxx} 高位系统段，与 C 端业务段、Admin 段全部隔离。
 */
public enum CommonErrorCode implements ErrorCode {
    INTERNAL_ERROR("99001", "internal error"),
    DEPENDENCY_TIMEOUT("99002", "dependency timeout"),
    EVENT_PUBLISH_FAILED("99003", "event publish failed"),
    INVALID_REQUEST_PAYLOAD("99004", "invalid request payload");

    private final String code;
    private final String message;

    CommonErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
