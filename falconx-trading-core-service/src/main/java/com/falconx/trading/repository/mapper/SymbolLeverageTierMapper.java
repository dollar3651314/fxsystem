package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.SymbolLeverageTierRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 杠杆/MM 分级档位 MyBatis Mapper（读 + 写）。
 *
 * <p>STAGE-14C1 仅提供只读 {@link #selectBySymbolAndGroup}；STAGE-14C2 Task 3 扩展
 * 写路径（insert/updateById/softDeleteById）+ 分页查询（selectPage/countBy）+ 单行查询
 * （selectById）。CRUD RPC 与业务校验在 console 透传链路（C2 Task 4/8）。
 */
@Mapper
public interface SymbolLeverageTierMapper {

    /**
     * 按 symbol + group_code 查询启用中的档位，按 notional_lower 升序。
     *
     * @param symbol 品种代码
     * @param groupCode 客户组代码
     * @return 该 symbol+group 的启用档位（升序）；无配置返回空 list
     */
    List<SymbolLeverageTierRecord> selectBySymbolAndGroup(@Param("symbol") String symbol,
                                                          @Param("groupCode") String groupCode);

    /**
     * 按主键查询单档（不过滤 enabled，供更新/软删前查存在性与审计 before 快照）。
     *
     * @param id 主键 ID
     * @return 命中记录；不存在返回 {@code null}
     */
    SymbolLeverageTierRecord selectById(@Param("id") long id);

    /**
     * 插入一行档位。id 由调用方（Repository 层雪花生成）填充。
     *
     * @param record 待插入档位（id 非空）
     * @return 影响行数
     */
    int insert(SymbolLeverageTierRecord record);

    /**
     * 按主键更新可变档位字段（notional 区间 / max_leverage / mm_rate / enabled）。
     *
     * @param record 待更新档位（id 非空）
     * @return 影响行数
     */
    int updateById(SymbolLeverageTierRecord record);

    /**
     * 软删（enabled=0）。master §7.4 规定 tier 删除为软删，不物理删除。
     *
     * @param id 主键 ID
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int softDeleteById(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 分页查询档位（symbol/groupCode 可空=不过滤），按 symbol、group_code、tier_no 升序。
     *
     * @param symbol 品种代码，可空（null=不过滤）
     * @param groupCode 客户组代码，可空（null=不过滤）
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页档位
     */
    List<SymbolLeverageTierRecord> selectPage(@Param("symbol") String symbol,
                                              @Param("groupCode") String groupCode,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /**
     * 统计符合过滤条件的档位总数（与 {@link #selectPage} 过滤口径一致）。
     *
     * @param symbol 品种代码，可空（null=不过滤）
     * @param groupCode 客户组代码，可空（null=不过滤）
     * @return 总条数
     */
    long countBy(@Param("symbol") String symbol, @Param("groupCode") String groupCode);
}
