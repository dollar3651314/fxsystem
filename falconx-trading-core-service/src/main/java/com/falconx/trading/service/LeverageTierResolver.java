package com.falconx.trading.service;

import com.falconx.trading.service.model.LeverageTier;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 杠杆/MM 档位解析器。
 *
 * <p>STAGE-14C1 引入。按账户币 notional 在指定 (symbol, groupCode) 的档位列表中落档，
 * 命中区间 {@code [notionalLower, notionalUpper)}（下界含、上界不含，最高档 upper=null 无上限），
 * 返回该档的最大杠杆与维持保证金率。开仓风控（Task 6）据此校验杠杆并冻结 mmRate。
 *
 * <p>档位列表来自 {@code SymbolLeverageTierRepository.findTiers}（已含 default 组回退），
 * Resolver 自身只做本地短 TTL 缓存与区间落档，不重复处理 group 回退。
 */
public interface LeverageTierResolver {

    /**
     * 解析指定品种在指定客户组下、给定 notional 落入的杠杆档位。
     *
     * @param symbol 品种代码
     * @param notionalInAccount 账户币口径名义价值（非负）
     * @param groupCode 客户组代码（原样透传给 Repository，由其负责 default 回退）
     * @return 命中档位；无配置或落不进任何档时返回 {@link Optional#empty()}
     *         （caller 判 empty 即 30072 TIER_CONFIG_NOT_FOUND）
     */
    Optional<LeverageTier> resolve(String symbol, BigDecimal notionalInAccount, String groupCode);

    /**
     * 失效指定 (symbol, groupCode) 的本机缓存档位（STAGE-14C2 Task 4）。
     *
     * <p>管理端经 console 透传 CRUD 改动 tier 后，由 {@code TradingTierAdminApplicationService}
     * 在写事务成功后调用，让本实例下次 {@link #resolve} 立即回源 DB 拿到新档位。
     *
     * <p>多实例部署下仅失效当前实例缓存；其余实例靠 30s 惰性过期兜底（master §8.3
     * 「admin 改 tier 30s 生效」容许该延迟）。symbol 或 groupCode 为 null 时为空操作。
     *
     * @param symbol 品种代码
     * @param groupCode 客户组代码
     */
    void invalidate(String symbol, String groupCode);
}
