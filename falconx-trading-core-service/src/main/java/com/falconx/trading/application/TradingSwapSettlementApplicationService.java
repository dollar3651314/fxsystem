package com.falconx.trading.application;

import com.falconx.trading.contract.event.TradingSwapSettledEventPayload;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingSwapRateSnapshotRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.model.TradingSwapRateRule;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隔夜利息单笔结算应用服务。
 *
 * <p>该服务负责一笔 `position + rolloverAt` 的正式 owner 写路径：
 *
 * <ol>
 *   <li>锁定持仓并重做终态校验</li>
 *   <li>读取 owner 费率快照并按结算日解析有效规则</li>
 *   <li>校验最新报价 fresh，可执行 bid/ask 不为空</li>
 *   <li>锁定结算账户并写 `t_ledger.biz_type=6/7`</li>
 * </ol>
 *
 * <p>幂等点固定使用 `t_ledger(user_id, idempotency_key)`，
 * 同一持仓同一结算点重复触发时只允许落账一次。
 */
@Service
public class TradingSwapSettlementApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingSwapSettlementApplicationService.class);

    private final TradingPositionRepository tradingPositionRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository;
    private final TradingLedgerRepository tradingLedgerRepository;
    private final TradingOutboxRepository tradingOutboxRepository;
    private final TradingAccountService tradingAccountService;
    private final TradingCoreServiceProperties properties;
    // STAGE-14B Task 8：Swap 货币换算所需 —— 取 position symbol 的计价币 + FX rate。
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final FxRateService fxRateService;

    public TradingSwapSettlementApplicationService(TradingPositionRepository tradingPositionRepository,
                                                   TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                                   TradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository,
                                                   TradingLedgerRepository tradingLedgerRepository,
                                                   TradingOutboxRepository tradingOutboxRepository,
                                                   TradingAccountService tradingAccountService,
                                                   TradingCoreServiceProperties properties,
                                                   MarketSymbolSpecRepository marketSymbolSpecRepository,
                                                   FxRateService fxRateService) {
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.tradingSwapRateSnapshotRepository = tradingSwapRateSnapshotRepository;
        this.tradingLedgerRepository = tradingLedgerRepository;
        this.tradingOutboxRepository = tradingOutboxRepository;
        this.tradingAccountService = tradingAccountService;
        this.properties = properties;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.fxRateService = fxRateService;
    }

    /**
     * 在单个事务内完成一笔隔夜利息结算。
     *
     * @param positionId 持仓 ID
     * @param rolloverAt 结算时点（UTC）
     * @return `true` 表示实际写入了账本
     */
    @Transactional
    public boolean settlePositionAtRollover(Long positionId, OffsetDateTime rolloverAt) {
        TradingPosition position = tradingPositionRepository.findByIdForUpdate(positionId)
                .orElseThrow(() -> new IllegalStateException("Swap settlement position not found, positionId=" + positionId));
        if (position.isTerminal()) {
            log.info("trading.swap.settlement.skip positionId={} reason=POSITION_TERMINAL status={}",
                    positionId,
                    position.status());
            return false;
        }
        if (!position.openedAt().isBefore(rolloverAt)) {
            log.info("trading.swap.settlement.skip positionId={} reason=OPENED_AFTER_ROLLOVER openedAt={} rolloverAt={}",
                    positionId,
                    position.openedAt(),
                    rolloverAt);
            return false;
        }

        TradingSwapRateSnapshot snapshot = tradingSwapRateSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
        if (snapshot == null) {
            log.warn("trading.swap.settlement.skip positionId={} symbol={} reason=SWAP_RATE_SNAPSHOT_MISSING",
                    positionId,
                    position.symbol());
            return false;
        }
        LocalDate settlementDate = rolloverAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        TradingSwapRateRule rule = snapshot.resolveEffectiveRule(settlementDate).orElse(null);
        if (rule == null) {
            log.warn("trading.swap.settlement.skip positionId={} symbol={} settlementDate={} reason=SWAP_RATE_RULE_MISSING",
                    positionId,
                    position.symbol(),
                    settlementDate);
            return false;
        }

        String idempotencyKey = buildIdempotencyKey(position.positionId(), rolloverAt);
        if (tradingLedgerRepository.existsByUserIdAndIdempotencyKey(position.userId(), idempotencyKey)) {
            log.info("trading.swap.settlement.duplicate positionId={} userId={} rolloverAt={}",
                    position.positionId(),
                    position.userId(),
                    rolloverAt);
            return false;
        }

        TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
        if (!isQuoteEligibleForRollover(quote, rolloverAt)) {
            log.warn("trading.swap.settlement.skip positionId={} symbol={} rolloverAt={} reason=QUOTE_UNAVAILABLE quoteMissing={} quoteStale={} quoteTs={}",
                    position.positionId(),
                    position.symbol(),
                    rolloverAt,
                    quote == null,
                    quote != null && quote.stale(),
                    quote == null ? null : quote.ts());
            return false;
        }
        BigDecimal effectivePrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position.side());
        if (effectivePrice == null) {
            log.warn("trading.swap.settlement.skip positionId={} symbol={} rolloverAt={} reason=EFFECTIVE_PRICE_MISSING side={}",
                    position.positionId(),
                    position.symbol(),
                    rolloverAt,
                    position.side());
            return false;
        }

        BigDecimal configuredRate = resolveConfiguredRate(rule, position.side());
        if (configuredRate == null || configuredRate.signum() == 0) {
            log.info("trading.swap.settlement.skip positionId={} symbol={} rolloverAt={} reason=ZERO_RATE side={}",
                    position.positionId(),
                    position.symbol(),
                    rolloverAt,
                    position.side());
            return false;
        }

        BigDecimal settlementAmount = TradingPricingSupport.scaleAmount(
                position.quantity().multiply(effectivePrice).multiply(configuredRate.abs())
        );
        if (settlementAmount.signum() == 0) {
            log.info("trading.swap.settlement.skip positionId={} symbol={} rolloverAt={} reason=ZERO_AMOUNT quantity={} effectivePrice={} rate={}",
                    position.positionId(),
                    position.symbol(),
                    rolloverAt,
                    position.quantity(),
                    effectivePrice,
                    configuredRate);
            return false;
        }

        TradingLedgerBizType ledgerBizType = configuredRate.signum() < 0
                ? TradingLedgerBizType.SWAP_CHARGE
                : TradingLedgerBizType.SWAP_INCOME;
        TradingAccount settlementAccount = tradingAccountService.getExistingAccountForUpdate(
                position.userId(),
                properties.getSettlementToken()
        );
        // STAGE-14B Task 8：原币 settlementAmount(=Swap(QC)) → 账户币 Swap(AC) 换算 + 落账三列留痕。
        //   master §3.2：Swap(AC) = Swap(QC) × fx(QC→AC) 当时快照。
        //   account balance 按账户币计 → balance 变更用 inAccount；三列留痕记原币 (inQuote, QC, fx)。
        // Swap 为定时被动结算（master §3.5.5 FX 降级：被动结算应继续，不阻断），故降级为原值留痕（fx=1）+ warn。
        //   STAGE-14B Task 9b 收口 Issue 2：SymbolSpec 缺失 / quoteCurrency 未回填 → original_currency 记账户币
        //   （确实不知道 QC）；FX 不可用（QC 已知仅 rate 查不到）→ original_currency 如实记真实 quoteCurrency。
        String accountCurrency = settlementAccount.currency();
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        BigDecimal settlementInAccount;
        BigDecimal settlementFxRate;
        String settlementOriginalCurrency;
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            // 降级：SymbolSpec 缺失或过渡期 quoteCurrency 未回填 → 退化为账户币原值（与 Task 5 占位等价）。
            log.warn("trading.swap.settlement.fx.degraded positionId={} symbol={} reason=QUOTE_CURRENCY_MISSING amount={} accountCurrency={}",
                    position.positionId(), position.symbol(), settlementAmount, accountCurrency);
            settlementInAccount = settlementAmount;
            settlementFxRate = BigDecimal.ONE;
            settlementOriginalCurrency = accountCurrency;
        } else if (quoteCurrency.equals(accountCurrency)) {
            // 同币种短路：fx=1、inAccount==inQuote，不查询。
            settlementInAccount = settlementAmount;
            settlementFxRate = BigDecimal.ONE;
            settlementOriginalCurrency = quoteCurrency;
        } else {
            BigDecimal fxRate = fxRateService.queryRate(quoteCurrency, accountCurrency).orElse(null);
            if (fxRate == null) {
                // FX 不可用：被动结算不阻断 → inAccount 退化为原币原值、fx=1（未真实换算）+ warn。
                // STAGE-14B Task 9b 收口 Issue 2：QC 已知仅 rate 查不到 → original_currency 如实记原币
                //   quoteCurrency（avoid 原币值标账户币的审计误导）；仅 SymbolSpec 缺失 / QC 未回填分支才记账户币。
                log.warn("trading.swap.settlement.fx.degraded positionId={} symbol={} reason=FX_RATE_UNAVAILABLE quoteCurrency={} accountCurrency={} amount={}",
                        position.positionId(), position.symbol(), quoteCurrency, accountCurrency, settlementAmount);
                settlementInAccount = settlementAmount;
                settlementFxRate = BigDecimal.ONE;
                settlementOriginalCurrency = quoteCurrency;
            } else {
                settlementInAccount = settlementAmount.multiply(fxRate).setScale(8, java.math.RoundingMode.HALF_UP);
                settlementFxRate = fxRate;
                settlementOriginalCurrency = quoteCurrency;
            }
        }
        // STAGE-14B Task 8 收口：FX 换算后账户币零值守卫 —— 原币非零但极小 fxRate 下舍入到 0，
        //   写零额账本无意义且会触发 settleSwap signum 守卫；被动结算遵循不阻断原则 → skip 本次。
        if (settlementInAccount.signum() == 0) {
            log.info("trading.swap.settlement.skip positionId={} symbol={} reason=ZERO_AMOUNT_AFTER_FX settlementAmount={} fxRate={}",
                    position.positionId(),
                    position.symbol(),
                    settlementAmount,
                    settlementFxRate);
            return false;
        }
        TradingLedgerEntry ledgerEntry = tradingAccountService.settleSwap(
                settlementAccount,
                settlementInAccount,
                settlementAmount,
                settlementOriginalCurrency,
                settlementFxRate,
                ledgerBizType,
                idempotencyKey,
                buildReferenceNo(position.positionId(), rolloverAt),
                rolloverAt
        );
        tradingOutboxRepository.save(new TradingOutboxMessage(
                null,
                "swap-settled:" + ledgerEntry.ledgerId(),
                "trading.swap.settled",
                String.valueOf(position.userId()),
                new TradingSwapSettledEventPayload(
                        ledgerEntry.ledgerId(),
                        position.userId(),
                        position.positionId(),
                        position.symbol(),
                        position.side().name(),
                        ledgerBizType.name(),
                        // STAGE-14B Task 8：event.amount 与 t_ledger.amount 口径一致 = 账户币 Swap(AC)。
                        settlementInAccount,
                        configuredRate,
                        effectivePrice,
                        rolloverAt,
                        quote.ts(),
                        ledgerEntry.createdAt()
                ),
                TradingOutboxStatus.PENDING,
                ledgerEntry.createdAt(),
                null,
                0,
                ledgerEntry.createdAt(),
                null
        ));
        log.info("trading.swap.settlement.completed userId={} positionId={} symbol={} side={} rolloverAt={} ledgerBizType={} amount={} originalAmount={} originalCurrency={} fxRate={} rate={} effectivePrice={} quoteTs={} quoteSource={}",
                position.userId(),
                position.positionId(),
                position.symbol(),
                position.side(),
                rolloverAt,
                ledgerBizType,
                settlementInAccount,
                settlementAmount,
                settlementOriginalCurrency,
                settlementFxRate,
                configuredRate,
                effectivePrice,
                quote.ts(),
                quote.source());
        return true;
    }

    private BigDecimal resolveConfiguredRate(TradingSwapRateRule rule, TradingOrderSide side) {
        if (rule == null || side == null) {
            return null;
        }
        return side == TradingOrderSide.BUY ? rule.longRate() : rule.shortRate();
    }

    private boolean isQuoteEligibleForRollover(TradingQuoteSnapshot quote, OffsetDateTime rolloverAt) {
        if (quote == null || quote.stale() || quote.ts() == null || rolloverAt == null) {
            return false;
        }
        Duration maxQuoteAge = properties.getStale().getMaxAge();
        OffsetDateTime quoteTs = quote.ts().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime earliestAccepted = rolloverAt.minus(maxQuoteAge);
        OffsetDateTime latestAccepted = rolloverAt.plus(maxQuoteAge);
        return !quoteTs.isBefore(earliestAccepted) && !quoteTs.isAfter(latestAccepted);
    }

    private String buildIdempotencyKey(Long positionId, OffsetDateTime rolloverAt) {
        return "swap:" + positionId + ":" + rolloverAt.toInstant();
    }

    private String buildReferenceNo(Long positionId, OffsetDateTime rolloverAt) {
        return "swap:" + positionId + ":" + rolloverAt.toInstant();
    }
}
