package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.FxPauseBehaviorRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * FX_PAUSED 类目行为开关 MyBatis Mapper（读 + admin 写）。
 *
 * <p>STAGE-14C2 Task 5 引入只读路径（{@code selectByCategory}/{@code selectAll}）。
 * STAGE-14D3a Task 6 加 admin 写路径 {@link #updateByCategory}（console 配置 UI 用），
 * 写后由 Repository 失效本地快照实现即时生效。
 */
@Mapper
public interface FxPauseBehaviorMapper {

    /**
     * 按类目编码查询单行行为开关。
     *
     * @param category 品种类目编码（1-8）
     * @return 命中记录；不存在返回 {@code null}
     */
    FxPauseBehaviorRecord selectByCategory(@Param("category") int category);

    /**
     * 查询全部类目行为开关（按 category 升序）。
     *
     * @return 全部行为开关（8 行）
     */
    List<FxPauseBehaviorRecord> selectAll();

    /**
     * 按类目编码绝对覆盖更新行为开关（admin 写路径）。
     *
     * <p>{@code allow_close} 始终为 true（master §6.5 手动平仓不限制），由调用方传 {@code true}。
     * boolean ↔ TINYINT(1/0) 走 MyBatis 默认 BooleanTypeHandler。
     *
     * @param category 品种类目编码（1-8）
     * @param allowOpen 是否允许开仓
     * @param allowClose 是否允许平仓（恒 true）
     * @param allowLiquidation 是否允许被动强平
     * @param adminUserId 操作 admin 用户 id（写入 updated_by_admin_id）
     * @return 受影响行数（命中 1，未命中 0）
     */
    int updateByCategory(@Param("category") int category,
                         @Param("allowOpen") boolean allowOpen,
                         @Param("allowClose") boolean allowClose,
                         @Param("allowLiquidation") boolean allowLiquidation,
                         @Param("adminUserId") Long adminUserId);
}
