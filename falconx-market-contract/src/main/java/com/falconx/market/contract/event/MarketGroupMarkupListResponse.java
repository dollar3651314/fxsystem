package com.falconx.market.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户组加点配置列表响应契约。
 *
 * <p>供 market-service `/internal/v1/market/symbols/group-markup` 与
 * `.../changes?since=` 两个 endpoint 共用。trading-core 拉取后用 {@link #serverTime()}
 * 作为下次增量请求的 since 参数，保证不漏配置变更。
 *
 * @param items 加点配置项，可能为空（删除清空 / 启动期）
 * @param serverTime 当前服务器时间，trading-core 保存用作下次 since
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketGroupMarkupListResponse(
        @NotNull List<MarketGroupMarkupItem> items,
        @NotNull OffsetDateTime serverTime
) {
}
