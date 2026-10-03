package com.falconx.market.service;

import com.falconx.market.entity.StandardQuote;
import java.util.List;

/**
 * 市场最新报价查询服务接口。
 *
 * <p>该服务用于把北向接口与内部读模型隔离开，
 * 保证后续即使改为直接读 Redis，也不会改变 controller 的依赖方向。
 */
public interface MarketQuoteQueryService {

    /**
     * 查询指定品种的最新报价（含用户组 markup 应用后的 effective + 基准 base，便于 controller
     * 同时透传 base* 字段给公允标记价、markup 对照与诊断视图）。
     *
     * @param symbol 品种代码
     * @param groupCode 用户组代码
     * @return effective + base 报价快照
     */
    QuoteSnapshot getLatestQuote(String symbol, String groupCode);

    /**
     * 查询指定品种最近的历史报价。
     *
     * @param symbol 品种代码
     * @param limit 返回数量
     * @param groupCode 用户组代码
     * @return 历史报价（effective + base 对），按报价时间升序排列
     */
    List<QuoteSnapshot> getRecentQuotes(String symbol, Integer limit, String groupCode);

    /**
     * STAGE-12-GROUP-MARKUP: effective 报价 + 基准报价的快照对。
     * <p>effective 是 markup 应用后的用户视角；base 是平台基准价（不含 markup）。
     * 当用户组无 markup 时 effective === base（{@link MarketGroupMarkupService#applyMarkup}
     * 零分配回退）。
     */
    record QuoteSnapshot(StandardQuote effective, StandardQuote base) {
        public boolean hasMarkup() {
            return effective != base;
        }
    }
}
