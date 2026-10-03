package com.falconx.console.symbol;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupBulkUpsertRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupCreateRequest;
import com.falconx.console.api.AdminSymbolGroupMarkupGroupedListResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupListResponse;
import com.falconx.console.api.AdminSymbolGroupMarkupUpdateRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-12-GROUP-MARKUP: 用户组加点 ApplicationService（通过 internal RPC 调 market）。
 *
 * <p>错误码翻译：market 服务直接抛 90640-90642（与 console AdminErrorCode 共用号段），
 * 故无须重新映射，只需把 InternalRpcException 翻译成 AdminBusinessException。
 */
@Service
public class AdminGroupMarkupApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminGroupMarkupApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupMarkupListResponse>> LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupMarkupGroupedListResponse>> GROUPED_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolGroupMarkupListResponse.Item>> ITEM_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<List<AdminSymbolGroupMarkupListResponse.Item>>> ITEMS_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final InternalRpcClient internalRpcClient;

    public AdminGroupMarkupApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    public AdminSymbolGroupMarkupListResponse list(String groupCode, String symbolLike, Integer enabled,
                                                    int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/market/symbols/group-markup?page=")
                .append(page).append("&size=").append(size);
        if (groupCode != null && !groupCode.isBlank()) query.append("&groupCode=").append(groupCode);
        if (symbolLike != null && !symbolLike.isBlank()) query.append("&symbolLike=").append(symbolLike);
        if (enabled != null) query.append("&enabled=").append(enabled);
        try {
            return internalRpcClient.get(query.toString(), LIST_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupMarkupGroupedListResponse listGrouped() {
        try {
            return internalRpcClient.get(
                    "/internal/v1/market/symbols/group-markup/grouped", GROUPED_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupMarkupListResponse.Item detail(String groupCode, String platformSymbol) {
        try {
            return internalRpcClient.get(
                    "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol,
                    ITEM_RESPONSE_TYPE);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupMarkupListResponse.Item create(AdminSymbolGroupMarkupCreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupCode", request.groupCode());
        body.put("platformSymbol", request.platformSymbol());
        body.put("bidExtra", request.bidExtra());
        body.put("askExtra", request.askExtra());
        // market UpsertRequest 仍是 Integer 1/0，console 端用 Boolean 后转换
        body.put("enabled", Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        try {
            AdminSymbolGroupMarkupListResponse.Item created = internalRpcClient.post(
                    "/internal/v1/market/symbols/group-markup", body, ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.group-markup.created groupCode={} platformSymbol={} reason={}",
                    request.groupCode(), request.platformSymbol(), request.reason());
            return created;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public AdminSymbolGroupMarkupListResponse.Item update(String groupCode, String platformSymbol,
                                                           AdminSymbolGroupMarkupUpdateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bidExtra", request.bidExtra());
        body.put("askExtra", request.askExtra());
        body.put("enabled", Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        try {
            AdminSymbolGroupMarkupListResponse.Item updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol,
                    body, ITEM_RESPONSE_TYPE);
            log.info("admin.symbol.group-markup.updated groupCode={} platformSymbol={} reason={}",
                    groupCode, platformSymbol, request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public List<AdminSymbolGroupMarkupListResponse.Item> bulkUpsert(
            String groupCode, AdminSymbolGroupMarkupBulkUpsertRequest request) {
        // market BulkUpsertItem.enabled 仍是 Integer 1/0；console Item.enabled 是 Boolean → 转换
        java.util.List<Map<String, Object>> items = request.items().stream()
                .map(it -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("platformSymbol", it.platformSymbol());
                    m.put("bidExtra", it.bidExtra());
                    m.put("askExtra", it.askExtra());
                    m.put("enabled", Boolean.TRUE.equals(it.enabled()) ? 1 : 0);
                    return m;
                })
                .toList();
        try {
            List<AdminSymbolGroupMarkupListResponse.Item> updated = internalRpcClient.put(
                    "/internal/v1/market/symbols/group-markup/" + groupCode + "/bulk",
                    Map.of("items", items),
                    ITEMS_RESPONSE_TYPE);
            log.info("admin.symbol.group-markup.bulk-upserted groupCode={} count={} reason={}",
                    groupCode, request.items().size(), request.reason());
            return updated;
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    public void delete(String groupCode, String platformSymbol, String reason) {
        try {
            internalRpcClient.delete(
                    "/internal/v1/market/symbols/group-markup/" + groupCode + "/" + platformSymbol,
                    VOID_RESPONSE_TYPE);
            log.info("admin.symbol.group-markup.deleted groupCode={} platformSymbol={} reason={}",
                    groupCode, platformSymbol, reason);
        } catch (InternalRpcException ex) {
            translateMarketError(ex);
            throw ex;
        }
    }

    /**
     * market 服务 90640-90642 错误码与 console AdminErrorCode 共用号段，1:1 翻译。
     */
    private void translateMarketError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90613" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_NOT_FOUND);
            case "90640" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND);
            case "90641" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_GROUP_MARKUP_INVALID_RANGE);
            case "90642" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_GROUP_MARKUP_DUPLICATE);
            default -> { /* 其他错误透传 */ }
        }
    }
}
