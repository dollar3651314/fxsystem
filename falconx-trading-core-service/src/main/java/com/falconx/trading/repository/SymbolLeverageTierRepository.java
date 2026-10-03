package com.falconx.trading.repository;

import com.falconx.trading.entity.SymbolLeverageTier;
import java.util.List;
import java.util.Optional;

/**
 * 杠杆/MM 分级档位 Repository（读 + 写）。
 *
 * <p>STAGE-14C1 引入只读 {@link #findTiers}；STAGE-14C2 Task 3 扩展写路径
 * （{@link #save}/{@link #update}/{@link #softDelete}）+ 管理端分页查询
 * （{@link #page}/{@link #count}）+ 单行查询（{@link #findById}）。CRUD RPC 与业务
 * 校验在 console 透传链路（C2 Task 4/8）。
 */
public interface SymbolLeverageTierRepository {

    /**
     * 查询指定品种在指定客户组下的启用档位（按 notional_lower 升序）。
     *
     * <p>先查 (symbol, groupCode)；若该组未单独配置档位（结果为空）且 groupCode 非
     * {@code default}，回退查 (symbol, {@code default}) 组档位。
     *
     * @param symbol 品种代码
     * @param groupCode 客户组代码
     * @return 命中档位（升序）；group 与 default 均无配置时返回空 list
     */
    List<SymbolLeverageTier> findTiers(String symbol, String groupCode);

    /**
     * 按主键查询单档（不过滤 enabled，供更新/软删前查存在性与审计 before 快照）。
     *
     * @param id 主键 ID
     * @return 命中档位；不存在返回 {@link Optional#empty()}
     */
    Optional<SymbolLeverageTier> findById(long id);

    /**
     * 新建档位。{@code tier.id()} 为空时由本层雪花生成；非空时沿用调用方 id。
     *
     * @param tier 待新建档位
     * @return 带最终 id 的已持久化档位
     */
    SymbolLeverageTier save(SymbolLeverageTier tier);

    /**
     * 按主键更新可变档位字段（notional 区间 / 最大杠杆 / mmRate / enabled）。
     *
     * @param tier 待更新档位（id 非空）
     * @return 影响行数
     */
    int update(SymbolLeverageTier tier);

    /**
     * 软删（enabled=0）。master §7.4 规定 tier 删除为软删，不物理删除。
     *
     * @param id 主键 ID
     * @return 影响行数
     */
    int softDelete(long id);

    /**
     * 管理端分页查询档位（symbol/groupCode 可空=不过滤）。
     *
     * @param symbol 品种代码，可空
     * @param groupCode 客户组代码，可空
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页档位
     */
    List<SymbolLeverageTier> page(String symbol, String groupCode, int offset, int limit);

    /**
     * 统计符合过滤条件的档位总数（与 {@link #page} 过滤口径一致）。
     *
     * @param symbol 品种代码，可空
     * @param groupCode 客户组代码，可空
     * @return 总条数
     */
    long count(String symbol, String groupCode);
}
