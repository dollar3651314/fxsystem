package com.falconx.console.symbol;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminFeaturedListResponse;
import com.falconx.console.api.AdminFeaturedReplaceRequest;
import com.falconx.console.internal.InternalRpcClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * 跑马灯热门产品配置 ApplicationService（透传 internal RPC 调 market）。
 *
 * <p>owner 为 market-service（t_featured_symbol），console 不本地存储，只透传。
 */
@Service
public class AdminFeaturedSymbolApplicationService {

    private static final String PATH = "/internal/v1/market/symbols/featured";
    private static final ParameterizedTypeReference<ApiResponse<AdminFeaturedListResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    private final InternalRpcClient internalRpcClient;

    public AdminFeaturedSymbolApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    /** 读全部（含禁用），管理端编辑用。 */
    public AdminFeaturedListResponse list() {
        return internalRpcClient.get(PATH, LIST_TYPE);
    }

    /** 全量替换（顺序即展示序），返回替换后的最新列表。 */
    public AdminFeaturedListResponse replace(AdminFeaturedReplaceRequest request) {
        return internalRpcClient.put(PATH, request, LIST_TYPE);
    }
}
