package com.falconx.trading.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * STAGE-14C2 Task 4：杠杆/MM 档位分页列表 internal RPC 响应体。
 *
 * <p>管理端列表返回含 {@code enabled=false}（软删）行供 admin 查看历史。
 *
 * @param items 当前页档位
 * @param total 符合过滤条件的总条数
 * @param page 当前页码（从 1 开始）
 * @param size 每页条数
 */
public record AdminTierListResponse(List<Item> items, long total, int page, int size) {

    /**
     * 单档位视图。
     *
     * @param id 主键 ID
     * @param symbol 品种代码
     * @param groupCode 客户组代码
     * @param tierNo 档位序号
     * @param notionalLower 名义价值下界（含）
     * @param notionalUpper 名义价值上界（不含）；{@code null} 表示无上限
     * @param maxLeverage 最大杠杆倍数
     * @param mmRate 维持保证金率
     * @param enabled 是否启用（软删后 false）
     */
    public record Item(
            Long id,
            String symbol,
            String groupCode,
            Integer tierNo,
            BigDecimal notionalLower,
            BigDecimal notionalUpper,
            Integer maxLeverage,
            BigDecimal mmRate,
            Boolean enabled
    ) {
    }
}
