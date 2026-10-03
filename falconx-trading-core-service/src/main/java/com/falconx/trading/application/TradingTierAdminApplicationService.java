package com.falconx.trading.application;

import com.falconx.trading.api.AdminTierListResponse;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.service.LeverageTierResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-14C2 Task 4：杠杆/MM 档位（tier）管理端编排服务。
 *
 * <p>承接 {@code AdminInternalTradingTierController}（console 透传链路）：tier CRUD（list / create /
 * update / softDelete）+ 业务校验 + 写成功后失效本机 {@link LeverageTierResolver} 缓存。
 *
 * <h3>业务校验</h3>
 * <ul>
 *   <li><b>CHECK</b>：{@code maxLeverage × mmRate ≤ 1.0}（应用层先校验抛
 *       {@link TradingErrorCode#ADMIN_TIER_VALIDATION_FAILED 90931}；DB {@code chk_tier_leverage_mm} 兜底）。</li>
 *   <li><b>区间合法</b>：{@code notionalLower ≥ 0}；{@code notionalUpper} 非空时 {@code lower < upper}
 *       （违反抛 90931）。</li>
 *   <li><b>区间不重叠</b>：同 (symbol, groupCode) 下与现有 enabled 档的 {@code [lower, upper)} 不相交
 *       （编辑时排除自身；重叠抛 {@link TradingErrorCode#ADMIN_TIER_OVERLAP 90932}）。</li>
 *   <li><b>tierNo 唯一</b>：同 (symbol, groupCode) 下序号不重复（重复抛 90932；DB
 *       {@code uk_symbol_group_tier} 兜底）。</li>
 *   <li><b>not found</b>：编辑/软删 findById 不存在 → {@link TradingErrorCode#ADMIN_TIER_NOT_FOUND 90930}。</li>
 * </ul>
 *
 * <h3>缓存失效</h3>
 * 写事务成功后调 {@link LeverageTierResolver#invalidate(String, String)} 清本机缓存对应 key；
 * 多实例其余实例靠 30s 惰性过期兜底（master §8.3「admin 改 tier 30s 生效」容许）。
 */
@Service
public class TradingTierAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingTierAdminApplicationService.class);

    /** CHECK：max_leverage × mm_rate ≤ 1.0（与 DB chk_tier_leverage_mm 同口径）。 */
    private static final BigDecimal LEVERAGE_MM_PRODUCT_MAX = BigDecimal.ONE;

    private final SymbolLeverageTierRepository tierRepository;
    private final LeverageTierResolver leverageTierResolver;

    public TradingTierAdminApplicationService(SymbolLeverageTierRepository tierRepository,
                                              LeverageTierResolver leverageTierResolver) {
        this.tierRepository = tierRepository;
        this.leverageTierResolver = leverageTierResolver;
    }

    /**
     * 分页查询档位（管理端，返回含软删行）。
     *
     * @param symbol 品种代码，可空=不过滤
     * @param groupCode 客户组代码，可空=不过滤
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 分页响应
     */
    public AdminTierListResponse listTiers(String symbol, String groupCode, int page, int size) {
        int offset = (page - 1) * size;
        List<SymbolLeverageTier> rows = tierRepository.page(symbol, groupCode, offset, size);
        long total = tierRepository.count(symbol, groupCode);
        List<AdminTierListResponse.Item> items = rows.stream().map(this::toItem).toList();
        return new AdminTierListResponse(items, total, page, size);
    }

    /**
     * 新建档位（校验通过后持久化，并失效本机缓存）。
     *
     * @return 已持久化档位视图
     */
    @Transactional
    public AdminTierListResponse.Item createTier(String symbol, String groupCode, Integer tierNo,
                                                 BigDecimal notionalLower, BigDecimal notionalUpper,
                                                 Integer maxLeverage, BigDecimal mmRate) {
        validateRange(notionalLower, notionalUpper);
        validateLeverageMmProduct(maxLeverage, mmRate);
        // 同 (symbol, group) 现有 enabled 档（findTiers 仅 enabled=1，含 default 回退；
        // 此处用 symbol+group 精确组，default 回退不影响同组重叠判定）。
        List<SymbolLeverageTier> existing = currentEnabledTiers(symbol, groupCode);
        validateTierNoUnique(existing, tierNo, null);
        validateNoOverlap(existing, notionalLower, notionalUpper, null);

        SymbolLeverageTier saved = tierRepository.save(new SymbolLeverageTier(
                null, symbol, groupCode, tierNo, notionalLower, notionalUpper, maxLeverage, mmRate, true));
        log.warn("trading.admin.tier.created id={} symbol={} groupCode={} tierNo={} maxLeverage={} mmRate={}",
                saved.id(), symbol, groupCode, tierNo, maxLeverage, mmRate);
        leverageTierResolver.invalidate(symbol, groupCode);
        return toItem(saved);
    }

    /**
     * 编辑档位（按 id；symbol/groupCode 沿用原行不可改），校验通过后更新并失效缓存。
     *
     * @return 更新后档位视图
     */
    @Transactional
    public AdminTierListResponse.Item updateTier(long id, Integer tierNo,
                                                 BigDecimal notionalLower, BigDecimal notionalUpper,
                                                 Integer maxLeverage, BigDecimal mmRate) {
        SymbolLeverageTier current = tierRepository.findById(id)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.ADMIN_TIER_NOT_FOUND, Map.of("id", id)));
        validateRange(notionalLower, notionalUpper);
        validateLeverageMmProduct(maxLeverage, mmRate);
        // 重叠/唯一校验排除自身（编辑可保持自身区间或在自身周围调整）。
        List<SymbolLeverageTier> existing = currentEnabledTiers(current.symbol(), current.groupCode());
        validateTierNoUnique(existing, tierNo, id);
        validateNoOverlap(existing, notionalLower, notionalUpper, id);

        SymbolLeverageTier updated = new SymbolLeverageTier(
                id, current.symbol(), current.groupCode(), tierNo,
                notionalLower, notionalUpper, maxLeverage, mmRate, true);
        tierRepository.update(updated);
        log.warn("trading.admin.tier.updated id={} symbol={} groupCode={} tierNo={}",
                id, current.symbol(), current.groupCode(), tierNo);
        leverageTierResolver.invalidate(current.symbol(), current.groupCode());
        return toItem(updated);
    }

    /**
     * 软删档位（enabled=0），并失效缓存。
     *
     * @param id 主键 ID
     */
    @Transactional
    public void deleteTier(long id) {
        SymbolLeverageTier current = tierRepository.findById(id)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.ADMIN_TIER_NOT_FOUND, Map.of("id", id)));
        tierRepository.softDelete(id);
        log.warn("trading.admin.tier.soft-deleted id={} symbol={} groupCode={}",
                id, current.symbol(), current.groupCode());
        leverageTierResolver.invalidate(current.symbol(), current.groupCode());
    }

    // ---- 校验 ----

    /** 区间合法：lower≥0；upper 非空时 lower<upper。 */
    private void validateRange(BigDecimal notionalLower, BigDecimal notionalUpper) {
        if (notionalLower == null || notionalLower.signum() < 0) {
            throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED,
                    Map.of("reason", "notionalLower must be >= 0", "notionalLower", String.valueOf(notionalLower)));
        }
        if (notionalUpper != null && notionalLower.compareTo(notionalUpper) >= 0) {
            throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED,
                    Map.of("reason", "notionalLower must be < notionalUpper",
                            "notionalLower", notionalLower.toPlainString(),
                            "notionalUpper", notionalUpper.toPlainString()));
        }
    }

    /** CHECK：maxLeverage × mmRate ≤ 1.0。 */
    private void validateLeverageMmProduct(Integer maxLeverage, BigDecimal mmRate) {
        if (maxLeverage == null || maxLeverage < 1 || mmRate == null || mmRate.signum() <= 0) {
            throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED,
                    Map.of("reason", "maxLeverage>=1 and mmRate>0 required",
                            "maxLeverage", String.valueOf(maxLeverage), "mmRate", String.valueOf(mmRate)));
        }
        BigDecimal product = mmRate.multiply(BigDecimal.valueOf(maxLeverage));
        if (product.compareTo(LEVERAGE_MM_PRODUCT_MAX) > 0) {
            throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_VALIDATION_FAILED,
                    Map.of("reason", "maxLeverage * mmRate must be <= 1.0",
                            "product", product.toPlainString()));
        }
    }

    /** tierNo 在同 (symbol, group) enabled 档内唯一（编辑时 excludeId 排除自身）。 */
    private void validateTierNoUnique(List<SymbolLeverageTier> existing, Integer tierNo, Long excludeId) {
        boolean dup = existing.stream()
                .filter(t -> excludeId == null || !excludeId.equals(t.id()))
                .anyMatch(t -> tierNo.equals(t.tierNo()));
        if (dup) {
            throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_OVERLAP,
                    Map.of("reason", "duplicate tierNo in same (symbol, groupCode)", "tierNo", tierNo));
        }
    }

    /**
     * 区间 {@code [lower, upper)} 不与现有 enabled 档相交（编辑时 excludeId 排除自身）。
     *
     * <p>两个半开区间 [aL,aU) 与 [bL,bU) 不相交当且仅当 aU ≤ bL 或 bU ≤ aL（upper=null 视为 +∞）。
     */
    private void validateNoOverlap(List<SymbolLeverageTier> existing, BigDecimal lower, BigDecimal upper,
                                   Long excludeId) {
        for (SymbolLeverageTier t : existing) {
            if (excludeId != null && excludeId.equals(t.id())) {
                continue;
            }
            if (intervalsOverlap(lower, upper, t.notionalLower(), t.notionalUpper())) {
                throw new TradingBusinessException(TradingErrorCode.ADMIN_TIER_OVERLAP,
                        Map.of("reason", "notional interval overlaps existing tier",
                                "tierNo", t.tierNo(),
                                "existingLower", t.notionalLower().toPlainString(),
                                "existingUpper", t.notionalUpper() == null ? "INF" : t.notionalUpper().toPlainString()));
            }
        }
    }

    /** 半开区间 [aL,aU) 与 [bL,bU) 是否相交；upper=null 表示 +∞。 */
    private boolean intervalsOverlap(BigDecimal aL, BigDecimal aU, BigDecimal bL, BigDecimal bU) {
        // 不相交：aU<=bL（a 在 b 左侧，含边界相接）或 bU<=aL（b 在 a 左侧）。
        boolean aLeftOfB = aU != null && aU.compareTo(bL) <= 0;
        boolean bLeftOfA = bU != null && bU.compareTo(aL) <= 0;
        return !(aLeftOfB || bLeftOfA);
    }

    /** 取同 (symbol, groupCode) 当前 enabled 档（精确组，不走 default 回退口径）。 */
    private List<SymbolLeverageTier> currentEnabledTiers(String symbol, String groupCode) {
        // findTiers 在精确组有配置时只返回该组 enabled 行；空时才回退 default，
        // 故仅当本组已有 enabled 档时这里取到的就是同组档；重叠/唯一判定针对同组成立。
        return tierRepository.findTiers(symbol, groupCode).stream()
                .filter(t -> groupCode.equals(t.groupCode()))
                .toList();
    }

    private AdminTierListResponse.Item toItem(SymbolLeverageTier t) {
        return new AdminTierListResponse.Item(
                t.id(), t.symbol(), t.groupCode(), t.tierNo(),
                t.notionalLower(), t.notionalUpper(), t.maxLeverage(), t.mmRate(), t.enabled());
    }
}
