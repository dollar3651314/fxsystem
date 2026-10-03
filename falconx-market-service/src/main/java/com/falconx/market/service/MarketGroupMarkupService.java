package com.falconx.market.service;

import com.falconx.market.entity.MarketSymbolGroupMarkup;
import com.falconx.market.entity.StandardQuote;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户组加点服务。
 *
 * <p>负责维护 {@code t_symbol_group_markup} 的内存快照，
 * 在 WebSocket 推送、REST 出参、跨服务 RPC 等高频路径直接 lookup，
 * 避免每条 tick 打 MySQL。
 *
 * <p>语义：在平台基准 {@link StandardQuote} 之上叠加 Layer 2 组级 bid_extra / ask_extra，
 * 仅影响对外口径，不影响 K 线 / ClickHouse / Kafka 落盘。
 */
public interface MarketGroupMarkupService {

    /**
     * 启动 / 定时刷新内存快照。
     *
     * <p>启动时全量加载；后续按 {@code falconx.market.group-markup.refresh-interval}
     * 周期增量刷新（基于 updated_at &gt; last_refresh）。
     */
    void refresh();

    /**
     * 查询特定组、特定 symbol 的加点配置。
     *
     * @return 命中时返回配置（可能 enabled=0）；不存在时返回空
     */
    Optional<MarketSymbolGroupMarkup> find(String groupCode, String platformSymbol);

    /**
     * 查询全部启用配置（管理端 grouped 视图 / trading-core RPC 全量加载用）。
     */
    List<MarketSymbolGroupMarkup> findAllEnabled();

    /**
     * 查询自指定时刻起变更过的配置（trading-core 增量刷新用）。
     */
    List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since);

    /**
     * 应用组加点到一条标准报价。
     *
     * <p>若无配置或加点全为 0，直接返回原对象（零开销）。
     *
     * @param quote 平台基准报价
     * @param groupCode 用户组（null / 空时按 default 处理）
     * @return 应用加点后的新报价（不修改入参）
     */
    StandardQuote applyMarkup(StandardQuote quote, String groupCode);
}
