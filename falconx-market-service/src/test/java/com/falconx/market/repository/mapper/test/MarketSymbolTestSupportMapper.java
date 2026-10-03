package com.falconx.market.repository.mapper.test;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * `t_symbol` 测试专用支持 Mapper。
 *
 * <p>该 Mapper 只存在于测试源码，用来驱动 owner 数据状态变化并验证启动器的热刷新行为。
 * 这里仍然遵循运行时正式规范：通过 MyBatis + XML 修改数据库，不再通过内存对象模拟产品启停状态。
 */
@Mapper
public interface MarketSymbolTestSupportMapper {

    /**
     * 更新指定 symbol 的启用状态。
     *
     * @param symbol 品种代码
     * @param status 目标状态，1=trading,2=suspended
     * @return 更新行数
     */
    int updateSymbolStatus(@Param("symbol") String symbol, @Param("status") int status);

    /**
     * 更新指定平台 symbol 的 LP 报价订阅开关。
     *
     * @param platformSymbol 平台品种代码
     * @param enabled 目标开关，1=订阅,0=不订阅
     * @return 更新行数
     */
    int updateLpSubscribeEnabled(@Param("platformSymbol") String platformSymbol, @Param("enabled") int enabled);

    /**
     * 统计仍残留在 LP owner 表中的禁用后缀 symbol。
     *
     * @return 残留行数
     */
    long countBlockedSuffixSymbols();

    /**
     * 统计仍残留在用户组可见性表中的禁用后缀 symbol。
     *
     * @return 残留行数
     */
    long countBlockedSuffixGroupVisibility();

    /**
     * 统计仍残留在报价映射表中的禁用后缀 platform/source symbol。
     *
     * @return 残留行数
     */
    long countBlockedSuffixQuoteMappings();
}
