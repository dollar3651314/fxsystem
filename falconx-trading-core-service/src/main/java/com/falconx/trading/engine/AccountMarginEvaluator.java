package com.falconx.trading.engine;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.config.TradingCoreServiceProperties;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单仓 MarginLevel 实时重算 + StopOut 判定（STAGE-14C1 Task 9）。
 *
 * <p>{@code QuoteDrivenEngine.processTick} 每条 tick 对该 symbol 上每个 OPEN 持仓调用本组件，
 * 与既有 {@link PositionTriggerRuleEvaluator}（liqPrice 价格触发）<b>并列</b>构成「双触发」
 * （master §6.3 ISOLATED：liqPrice 命中 <b>或</b> 单仓 MarginLevel ≤ stopOut，任一即强平）。
 *
 * <p>职责（不含强平本身，强平统一交回 {@code closePositionByTrigger}）：
 * <ol>
 *   <li>从 {@link AccountMarginStateCache} 取该用户账户（短 TTL 缓存，避免每 tick 打 MySQL）；
 *       账户缺失 → 跳过判定返回 false（liqPrice 仍独立生效）。</li>
 *   <li>取 quoteCurrency（{@link MarketSymbolSpecRepository}）；缺失则传 null（计算器据此降级）。</li>
 *   <li>{@link AccountEquityCalculator#computePositionMarginLevel}（单仓口径，MM 用开仓冻结 fx，
 *       <b>实时 MM 精化留 STAGE-14D</b>，见下方顾虑注释）。</li>
 *   <li>{@link MarginLevelMonitor#evaluate}（MARGIN_CALL 在 Monitor 内发告警 + 同用户 5min 节流；
 *       <b>本组件不在 MarginCall 路径强平</b>）。</li>
 *   <li>仅当判定为 {@link MarginLevelStatus#STOP_OUT} 返回 true（caller 强平）。</li>
 * </ol>
 *
 * <p><b>降级</b>：FX 不可用 → {@code marginLevel=null} → Monitor 判 HEALTHY → 不因 MarginLevel 强平
 * （liqPrice 路径不受影响）。
 *
 * <p><b>⚠ 顾虑（MM 冻结口径，控制者决策 C1 不改 Task 7 算法）</b>：单仓 MM 用 {@code mmRateAtOpen} +
 * 开仓冻结 {@code entryFxRate} 口径（{@link AccountEquityCalculator} 现状），不随实时 FX 抖动。
 * master §3.2 D2「全实时 MM」精化（MM 用当前 FX 重算）留 STAGE-14D，本阶段不动。
 *
 * <p><b>线程模型（AGENTS §3.8.2）</b>：本组件复用 {@code QuoteDrivenEngine} 既有执行线程，
 * 不新增回调线程业务；账户读经 {@link AccountMarginStateCache} 至多一次轻量 owner 读。
 */
@Component
public class AccountMarginEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AccountMarginEvaluator.class);

    private final AccountMarginStateCache accountMarginStateCache;
    private final AccountEquityCalculator accountEquityCalculator;
    private final MarginLevelMonitor marginLevelMonitor;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    /** 结算币（账户币 AC）：所有持仓统一结算到该币种，与 close 服务 settlementToken 一致。 */
    private final String settlementCurrency;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountMarginEvaluator(AccountMarginStateCache accountMarginStateCache,
                                  AccountEquityCalculator accountEquityCalculator,
                                  MarginLevelMonitor marginLevelMonitor,
                                  MarketSymbolSpecRepository marketSymbolSpecRepository,
                                  TradingCoreServiceProperties properties) {
        this(accountMarginStateCache, accountEquityCalculator, marginLevelMonitor,
                marketSymbolSpecRepository, properties.getSettlementToken());
    }

    /** 测试用构造：直接注入结算币。 */
    AccountMarginEvaluator(AccountMarginStateCache accountMarginStateCache,
                           AccountEquityCalculator accountEquityCalculator,
                           MarginLevelMonitor marginLevelMonitor,
                           MarketSymbolSpecRepository marketSymbolSpecRepository,
                           String settlementCurrency) {
        this.accountMarginStateCache = accountMarginStateCache;
        this.accountEquityCalculator = accountEquityCalculator;
        this.marginLevelMonitor = marginLevelMonitor;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.settlementCurrency = settlementCurrency;
    }

    /**
     * 对单仓实时重算 MarginLevel 并判定是否触发 StopOut。
     *
     * <p>STAGE-14C1 Task 9 收口：返回值由 boolean 改为「触发时的 marginLevel 数值」，
     * 让 caller（{@code QuoteDrivenEngine}）在 STOP_OUT_TRIGGERED 通知里展示真实保证金率，
     * 避免占位字符串。判定逻辑不变：非 null ⇔ 原 true（触发强平），null ⇔ 原 false（不触发）。
     *
     * @param position           OPEN 持仓
     * @param effectiveMarkPrice 有效标记价（已含 position 冻结 markup，由 caller 传入）
     * @return 触发 StopOut 时该仓位的实时 marginLevel（百分比数值，2 位 HALF_UP，来自 Task 7）；
     *         未触发（HEALTHY / MARGIN_CALL / FX 降级 / 账户缺失）返回 {@code null}
     */
    public BigDecimal stopOutMarginLevel(TradingPosition position, BigDecimal effectiveMarkPrice) {
        if (position == null || effectiveMarkPrice == null) {
            return null;
        }
        TradingAccount account = accountMarginStateCache.getAccount(position.userId(), settlementCurrency);
        if (account == null) {
            // 账户缺失（异常态）→ 跳过 MarginLevel 判定，liqPrice 仍独立生效
            return null;
        }
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        AccountMarginState state = accountEquityCalculator.computePositionMarginLevel(
                position, account, effectiveMarkPrice, quoteCurrency);
        // Monitor 内部：MARGIN_CALL 发告警 + 5min 节流；STOP_OUT 仅返回状态（强平由 caller 执行）
        MarginLevelStatus status = marginLevelMonitor.evaluate(position.userId(), state);
        if (status == MarginLevelStatus.STOP_OUT) {
            BigDecimal marginLevel = state == null ? null : state.marginLevel();
            log.warn("trading.margin.stop-out.triggered userId={} positionId={} symbol={} marginLevel={} markPrice={}",
                    position.userId(), position.positionId(), position.symbol(),
                    marginLevel, effectiveMarkPrice);
            return marginLevel;
        }
        return null;
    }
}
