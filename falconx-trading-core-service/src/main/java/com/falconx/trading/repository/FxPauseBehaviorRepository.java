package com.falconx.trading.repository;

import com.falconx.trading.entity.FxPauseBehavior;
import java.util.List;
import java.util.Optional;

/**
 * FX_PAUSED 类目行为开关 Repository（只读 + 本地缓存）。
 *
 * <p>STAGE-14C2 Task 5 引入。读 {@code t_fx_pause_behavior}（V31 seed 8 行），供 Task 6 在
 * GLOBAL_PAUSE / FX_PAUSED 活跃时按品种 category 判定是否允许开仓 / 被动强平。
 */
public interface FxPauseBehaviorRepository {

    /**
     * 按类目编码查询行为开关。
     *
     * <p>走全量本地缓存：缓存命中后在快照内查 category；快照内无该 category 返回
     * {@link Optional#empty()}（caller Task 6 据此降级 allow_all）。
     *
     * @param category 品种类目编码（1-8）
     * @return 命中行为开关；不存在返回 {@link Optional#empty()}
     */
    Optional<FxPauseBehavior> findByCategory(int category);

    /**
     * 查询全部类目行为开关（按 category 升序）。
     *
     * @return 全部行为开关（V31 seed 8 行）
     */
    List<FxPauseBehavior> findAll();

    /**
     * 按类目编码绝对覆盖更新行为开关（admin 写路径，console 配置 UI 用）。
     *
     * <p>写后立即失效本地快照，下次读重建 → console 改后即时生效（不等 TTL）。
     * {@code allowClose} 恒 true（master §6.5 手动平仓不限制），由调用方传 {@code true}。
     *
     * @param category 品种类目编码（1-8）
     * @param allowOpen 是否允许开仓
     * @param allowClose 是否允许平仓（恒 true）
     * @param allowLiquidation 是否允许被动强平
     * @param adminUserId 操作 admin 用户 id
     */
    void updateByCategory(int category, boolean allowOpen, boolean allowClose,
                          boolean allowLiquidation, Long adminUserId);
}
