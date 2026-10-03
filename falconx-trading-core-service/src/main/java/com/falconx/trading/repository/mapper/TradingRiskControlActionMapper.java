package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingRiskControlActionRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 风控执行动作 MyBatis Mapper。
 */
@Mapper
public interface TradingRiskControlActionMapper {

    /**
     * 插入风控动作（幂等：如已存在相同品种+动作类型+触发来源的激活记录则跳过）。
     */
    int insertIfAbsent(TradingRiskControlActionRecord record);

    /**
     * 停用指定品种+动作类型+触发来源的所有激活记录。
     */
    int deactivateBySymbolAndTypeAndSource(@Param("symbol") String symbol,
                                           @Param("actionTypeCode") int actionTypeCode,
                                           @Param("triggerSource") String triggerSource,
                                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 查询指定品种下严重程度最高的激活动作。
     */
    TradingRiskControlActionRecord selectMostSevereActiveBySymbol(@Param("symbol") String symbol);

    /**
     * 查询是否存在激活的全局暂停动作。
     */
    int countActiveGlobalPause();

    /**
     * 批量停用指定品种列表+动作类型+触发来源的所有激活记录（跨品种集中度恢复时使用）。
     */
    int deactivateBySymbolsAndTypeAndSource(@Param("symbols") List<String> symbols,
                                             @Param("actionTypeCode") int actionTypeCode,
                                             @Param("triggerSource") String triggerSource,
                                             @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端多条件分页查询。
     */
    List<TradingRiskControlActionRecord> selectAdminPaginated(@Param("symbol") String symbol,
                                                              @Param("actionTypeCode") Integer actionTypeCode,
                                                              @Param("triggerSource") String triggerSource,
                                                              @Param("isActive") Integer isActive,
                                                              @Param("fromCreatedAt") java.time.LocalDateTime fromCreatedAt,
                                                              @Param("toCreatedAt") java.time.LocalDateTime toCreatedAt,
                                                              @Param("offset") int offset,
                                                              @Param("limit") int limit);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端多条件计数。
     */
    long countAdminFiltered(@Param("symbol") String symbol,
                            @Param("actionTypeCode") Integer actionTypeCode,
                            @Param("triggerSource") String triggerSource,
                            @Param("isActive") Integer isActive,
                            @Param("fromCreatedAt") java.time.LocalDateTime fromCreatedAt,
                            @Param("toCreatedAt") java.time.LocalDateTime toCreatedAt);

    /**
     * STAGE-2-RISK-ADMIN R4：按 id 查询。
     */
    TradingRiskControlActionRecord selectById(@Param("id") long id);

    /**
     * STAGE-2-RISK-ADMIN R4：按 id 停用（is_active=0），仅当 trigger_source=MANUAL_ADMIN 且 is_active=1 时生效。
     *
     * @return 受影响行数（0 表示未匹配，调用方应再次 select 区分 NOT_FOUND vs NOT_DEACTIVATABLE）
     */
    int deactivateAdminById(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);
}
