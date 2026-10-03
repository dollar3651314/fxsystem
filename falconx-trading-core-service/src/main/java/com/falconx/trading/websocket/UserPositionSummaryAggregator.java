package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 单用户「总未实现盈亏」聚合器。
 *
 * <p>结构：{@code symbol → (userId → ΣpositionsPnl for that user on that symbol)}。
 *
 * <p>QuoteDrivenEngine 每条 tick 处理完 symbol X 的所有 OPEN 持仓后，
 * 按 userId 分组累计本 symbol PnL → 调 {@link #updateSymbol(String, Map)} 整体覆盖该 symbol 的快照。
 * 用户的 totalUnrealizedPnl = 跨 symbol 求和（{@link #totalForUser(long)}）。
 *
 * <p>用户完全平掉某个 symbol 上的全部仓位后，下一个 tick 该 symbol 进来时 totalsByUser 不再包含
 * 该用户 → updateSymbol put 时该用户 entry 被覆盖移除，避免读总和时算到已平仓 PnL。
 *
 * <p>读取复杂度 O(symbols)：1571 symbols 单次累加 μs 级，可接受。
 *
 * <p><b>币种口径（多币种与显示一致性收尾，2026-06-03）</b>：调用方
 * （{@link TradingUserRealtimePushService#publishPositionPnlUpdates}）已统一传入各 symbol 的
 * AC 账户币 unrealizedPnl（{@code unrealizedPnlInAccount}，经 FX 换算），跨 symbol 求和币种一致、口径正确，
 * 修复早前 STAGE-14B 过渡期 QC 原币混币相加偏差。AC 不可用（FX + 开仓冻结 entryFxRate 均缺失）的持仓
 * 由调用方剔除、不写入本聚合器，避免陈旧/异币值串入总和（与 admin 侧一致）。
 */
@Component
public class UserPositionSummaryAggregator {

    /** symbol → (userId → ΣpositionsPnl) 双层 ConcurrentHashMap，跨线程安全。 */
    private final Map<String, Map<Long, BigDecimal>> pnlBySymbolUser = new ConcurrentHashMap<>();

    /**
     * 整体覆盖 symbol 的用户 → 总 PnL 快照。
     *
     * @param symbol       平台 symbol
     * @param totalsByUser 当前 OPEN 持仓在该 symbol 上的 userId → ΣPnL 映射；
     *                     用户不在 map 内即代表该 symbol 已无 OPEN 持仓
     */
    public void updateSymbol(String symbol, Map<Long, BigDecimal> totalsByUser) {
        if (symbol == null || symbol.isBlank()) return;
        if (totalsByUser == null || totalsByUser.isEmpty()) {
            pnlBySymbolUser.remove(symbol);
            return;
        }
        // 拷贝一份，避免调用方后续修改影响内部状态
        Map<Long, BigDecimal> snapshot = new ConcurrentHashMap<>(totalsByUser);
        pnlBySymbolUser.put(symbol, snapshot);
    }

    /**
     * 跨 symbol 累加用户的总未实现盈亏（各 symbol 值已为 AC 账户币，币种一致可直接求和）。
     *
     * @return 用户 PnL 之和；用户当前无 OPEN 持仓返回 BigDecimal.ZERO
     */
    public BigDecimal totalForUser(long userId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<Long, BigDecimal> usersInSymbol : pnlBySymbolUser.values()) {
            BigDecimal v = usersInSymbol.get(userId);
            if (v != null) sum = sum.add(v);
        }
        return sum;
    }

    /** 测试 / 监控用：跟踪的 symbol 数。 */
    public int trackedSymbolCount() {
        return pnlBySymbolUser.size();
    }
}
