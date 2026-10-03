package com.falconx.trading.websocket;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 冻结 gateway 透传的用户身份和 trace 元数据。
 */
@Component
public class TradingUserWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTRIBUTE_TRACE_ID = "trading.websocket.traceId";
    static final String ATTRIBUTE_USER_ID = "trading.websocket.userId";
    static final String ATTRIBUTE_UID = "trading.websocket.uid";
    static final String ATTRIBUTE_STATUS = "trading.websocket.status";
    // STAGE-2-REALTIME-DATA Phase 2：admin 分流
    static final String ATTRIBUTE_ADMIN_USER_ID = "trading.websocket.adminUserId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String adminUserId = request.getHeaders().getFirst("X-Admin-User-Id");
        String userId = request.getHeaders().getFirst("X-User-Id");
        if ((adminUserId == null || adminUserId.isBlank())
                && (userId == null || userId.isBlank())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTRIBUTE_TRACE_ID,
                TraceIdSupport.reuseOrCreate(request.getHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER)));
        if (adminUserId != null && !adminUserId.isBlank()) {
            attributes.put(ATTRIBUTE_ADMIN_USER_ID, adminUserId.trim());
        } else {
            attributes.put(ATTRIBUTE_USER_ID, userId.trim());
            copyIfPresent(request, attributes, "X-User-Uid", ATTRIBUTE_UID);
            copyIfPresent(request, attributes, "X-User-Status", ATTRIBUTE_STATUS);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               @Nullable Exception exception) {
    }

    private void copyIfPresent(ServerHttpRequest request,
                               Map<String, Object> attributes,
                               String headerName,
                               String attributeName) {
        String value = request.getHeaders().getFirst(headerName);
        if (value != null && !value.isBlank()) {
            attributes.put(attributeName, value);
        }
    }
}
