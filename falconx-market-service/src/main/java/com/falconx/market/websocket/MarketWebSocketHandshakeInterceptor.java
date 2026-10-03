package com.falconx.market.websocket;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 在 WebSocket 升级前冻结握手元数据，避免 Servlet request 回收后再取 header。
 */
@Component
public class MarketWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTRIBUTE_TRACE_ID = "market.websocket.traceId";
    static final String ATTRIBUTE_USER_ID = "market.websocket.userId";
    static final String ATTRIBUTE_UID = "market.websocket.uid";
    static final String ATTRIBUTE_STATUS = "market.websocket.status";
    static final String ATTRIBUTE_GROUP_CODE = "market.websocket.groupCode";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        attributes.put(ATTRIBUTE_TRACE_ID,
                TraceIdSupport.reuseOrCreate(request.getHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER)));
        copyIfPresent(request, attributes, "X-User-Id", ATTRIBUTE_USER_ID);
        copyIfPresent(request, attributes, "X-User-Uid", ATTRIBUTE_UID);
        copyIfPresent(request, attributes, "X-User-Status", ATTRIBUTE_STATUS);
        copyIfPresent(request, attributes, "X-User-Group-Code", ATTRIBUTE_GROUP_CODE);
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
