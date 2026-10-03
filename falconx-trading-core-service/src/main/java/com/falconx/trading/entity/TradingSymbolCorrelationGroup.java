package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.util.List;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.1：跨品种相关性组。
 *
 * <p>schema：{@code t_symbol_correlation_group}（V20 migration）。
 * <p>成员（按 weight 加权净敞口 USD 聚合）：{@code t_symbol_correlation_member}。
 *
 * @param groupCode    组编码（PK）：EUR_GROUP / JPY_GROUP / METAL_GROUP / CRYPTO_MAJOR_GROUP
 * @param groupName    组中文名
 * @param thresholdUsd 组合净敞口阈值（USD，绝对值求和后比较）
 * @param enabled      启用状态
 * @param members      成员列表（symbol + weight）
 */
public record TradingSymbolCorrelationGroup(
        String groupCode,
        String groupName,
        BigDecimal thresholdUsd,
        boolean enabled,
        List<TradingSymbolCorrelationMember> members
) {
}
