package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingSymbolCorrelationGroup;
import java.util.List;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.1：跨品种相关性组 Repository。
 *
 * <p>V2 一期仅由 trading-core risk observability evaluator 读，无管理端 CRUD。
 * 组定义维护方式：DB seed（V20 sql + 后续 SQL 脚本 DBA 更新）。
 */
public interface TradingSymbolCorrelationGroupRepository {

    /** 查询所有 enabled=1 的组（含成员）。 */
    List<TradingSymbolCorrelationGroup> findAllEnabled();

    /** 按 symbol 反查所属组（可能为 0 / 1 / N 个）。 */
    List<TradingSymbolCorrelationGroup> findGroupsBySymbol(String symbol);
}
