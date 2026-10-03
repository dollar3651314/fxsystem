package com.falconx.trading.application;

import com.falconx.trading.command.ListTradingLedgerEntriesCommand;
import com.falconx.trading.command.ListTradingLiquidationsCommand;
import com.falconx.trading.command.ListTradingOrdersCommand;
import com.falconx.trading.command.ListTradingPositionsCommand;
import com.falconx.trading.command.ListTradingTradesCommand;
import com.falconx.trading.dto.TradingLedgerItemResponse;
import com.falconx.trading.dto.TradingLedgerListResponse;
import com.falconx.trading.dto.TradingLiquidationItemResponse;
import com.falconx.trading.dto.TradingLiquidationListResponse;
import com.falconx.trading.dto.TradingOrderItemResponse;
import com.falconx.trading.dto.TradingOrderListResponse;
import com.falconx.trading.dto.TradingPositionItemResponse;
import com.falconx.trading.dto.TradingPositionListResponse;
import com.falconx.trading.dto.TradingPositionSummaryResponse;
import com.falconx.trading.dto.TradingTradeItemResponse;
import com.falconx.trading.dto.TradingTradeListResponse;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.TradingLeverageTierListResponse;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOrderRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.support.TradingPricingSupport;
import com.falconx.trading.websocket.TradingRealtimeDualPnlSupport;
import com.falconx.trading.websocket.TradingUserRealtimePayloadFactory;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 用户视角查询应用服务。
 */
@Service
public class TradingUserQueryApplicationService {

    private final TradingOrderRepository tradingOrderRepository;
    private final TradingTradeRepository tradingTradeRepository;
    private final TradingPositionRepository tradingPositionRepository;
    private final TradingLedgerRepository tradingLedgerRepository;
    private final TradingLiquidationLogRepository tradingLiquidationLogRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingUserRealtimePayloadFactory payloadFactory;
    private final TradingRealtimeDualPnlSupport dualPnlSupport;
    private final SymbolLeverageTierRepository symbolLeverageTierRepository;
    private final FxRateService fxRateService;
    private final TradingCoreServiceProperties properties;

    public TradingUserQueryApplicationService(TradingOrderRepository tradingOrderRepository,
                                              TradingTradeRepository tradingTradeRepository,
                                              TradingPositionRepository tradingPositionRepository,
                                              TradingLedgerRepository tradingLedgerRepository,
                                              TradingLiquidationLogRepository tradingLiquidationLogRepository,
                                              TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                              TradingUserRealtimePayloadFactory payloadFactory,
                                              TradingRealtimeDualPnlSupport dualPnlSupport,
                                              SymbolLeverageTierRepository symbolLeverageTierRepository,
                                              FxRateService fxRateService,
                                              TradingCoreServiceProperties properties) {
        this.tradingOrderRepository = tradingOrderRepository;
        this.tradingTradeRepository = tradingTradeRepository;
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingLedgerRepository = tradingLedgerRepository;
        this.tradingLiquidationLogRepository = tradingLiquidationLogRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.payloadFactory = payloadFactory;
        this.dualPnlSupport = dualPnlSupport;
        this.symbolLeverageTierRepository = symbolLeverageTierRepository;
        this.fxRateService = fxRateService;
        this.properties = properties;
    }

    public TradingOrderListResponse listOrders(ListTradingOrdersCommand command) {
        int offset = offset(command.page(), command.pageSize());
        List<TradingOrderItemResponse> items = tradingOrderRepository.findByUserIdPaginated(
                        command.userId(),
                        offset,
                        command.pageSize()
                ).stream()
                .map(this::toOrderResponse)
                .toList();
        return new TradingOrderListResponse(
                command.page(),
                command.pageSize(),
                tradingOrderRepository.countByUserId(command.userId()),
                items
        );
    }

    public TradingTradeListResponse listTrades(ListTradingTradesCommand command) {
        int offset = offset(command.page(), command.pageSize());
        List<TradingTradeItemResponse> items = tradingTradeRepository.findByUserIdPaginated(
                        command.userId(),
                        offset,
                        command.pageSize()
                ).stream()
                .map(this::toTradeResponse)
                .toList();
        return new TradingTradeListResponse(
                command.page(),
                command.pageSize(),
                tradingTradeRepository.countByUserId(command.userId()),
                items
        );
    }

    public TradingPositionListResponse listPositions(ListTradingPositionsCommand command) {
        int offset = offset(command.page(), command.pageSize());
        List<TradingPositionItemResponse> items = tradingPositionRepository.findByUserIdPaginated(
                        command.userId(),
                        command.statusFilter(),
                        offset,
                        command.pageSize()
                ).stream()
                .map(this::toPositionResponse)
                .toList();
        return new TradingPositionListResponse(
                command.page(),
                command.pageSize(),
                tradingPositionRepository.countByUserId(command.userId(), command.statusFilter()),
                items
        );
    }

    /**
     * 用户当前 OPEN 持仓汇总：用现成的 quote snapshot 即时计算 ΣunrealizedPnl。
     *
     * <p>遍历该用户全部 OPEN（取上限 1000，远大于零售场景 N ≤ 100），
     * 调 {@link com.falconx.trading.support.TradingPricingSupport#resolvePositionMarkPrice} 取标记价，
     * 经 {@link TradingRealtimeDualPnlSupport#computeDualPnl} 换算到 AC 账户币后求和——跨品种币种一致，
     * 修复早前 QC 原币混币相加（与 WS user.position.summary 同口径）。{@code totalMargin} 本就为 AC 账户币（落账口径），原样累加。
     *
     * <p>FX + 开仓冻结 entryFxRate 均不可用致 AC 无法换算的持仓，剔除出 {@code totalUnrealizedPnl}（不混入异币值），
     * 与 admin/WS 侧一致。{@code withoutQuote} 仍只统计无标记价（无 quote）的持仓。
     *
     * <p>WS push（user.position.summary）会持续刷新；本接口主要用于页面首次渲染拿初值。
     */
    @Transactional(readOnly = true)
    public TradingPositionSummaryResponse getPositionSummary(long userId) {
        List<TradingPosition> openPositions = tradingPositionRepository.findByUserIdPaginated(
                userId, List.of(TradingPositionStatus.OPEN), 0, 1000);
        BigDecimal totalMargin = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        long withoutQuote = 0;
        for (TradingPosition p : openPositions) {
            if (p.margin() != null) totalMargin = totalMargin.add(p.margin());
            TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(p.symbol()).orElse(null);
            BigDecimal markPrice = com.falconx.trading.support.TradingPricingSupport
                    .resolvePositionMarkPrice(quote, p.side());
            if (markPrice == null) {
                withoutQuote++;
                continue;
            }
            // AC 账户币口径求和（跨品种可加）；AC 不可用（FX+entryFxRate 均缺失）的仓不计入总和。
            BigDecimal pnlInAccount = dualPnlSupport.computeDualPnl(p, markPrice).inAccount();
            if (pnlInAccount != null) totalPnl = totalPnl.add(pnlInAccount);
        }
        return new TradingPositionSummaryResponse(
                openPositions.size(),
                totalMargin,
                com.falconx.trading.support.TradingPricingSupport.scaleAmount(totalPnl),
                withoutQuote,
                java.time.OffsetDateTime.now()
        );
    }

    /**
     * 杠杆/MM 档位查询（B 切片，2026-06-03）：供客户端下单面板按名义价值动态降档。
     *
     * <p>档位口径与开仓风控严格同源——同 {@link SymbolLeverageTierRepository#findTiers}
     * （请求组无配置回退 default 组）；附 QC→账户币当前汇率（同币种短路 1，FX 不可用 null
     * 由客户端降级只按 tier1 上限），客户端用 {@code qty × price × fxRate} 估算 notional(AC)
     * 落档（与风控 {@code margin.inAccount() × leverage} 的口径在档位粒度上一致——
     * 档位区间为万级宽度，汇率瞬时差不足以错档）。
     */
    public TradingLeverageTierListResponse getLeverageTiers(String symbol, String groupCode) {
        List<SymbolLeverageTier> tiers = symbolLeverageTierRepository.findTiers(symbol, groupCode);
        String effectiveGroup = tiers.isEmpty() ? groupCode : tiers.get(0).groupCode();
        String quoteCurrency = dualPnlSupport.resolveQuoteCurrency(symbol);
        String settlement = properties.getSettlementToken();
        BigDecimal fxRate;
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            fxRate = null;
        } else if (quoteCurrency.equals(settlement)) {
            fxRate = BigDecimal.ONE;
        } else {
            fxRate = fxRateService.queryRate(quoteCurrency, settlement).orElse(null);
        }
        return new TradingLeverageTierListResponse(
                symbol,
                effectiveGroup,
                quoteCurrency,
                fxRate,
                tiers.stream()
                        .filter(t -> Boolean.TRUE.equals(t.enabled()))
                        .map(t -> new TradingLeverageTierListResponse.Item(
                                t.tierNo(), t.notionalLower(), t.notionalUpper(), t.maxLeverage(), t.mmRate()))
                        .toList()
        );
    }

    /**
     * 持仓维度 Swap 聚合（按 reference_no LIKE "swap:{positionId}:%"）。
     * userId 用于二次过滤，防止越权读他人持仓数据。
     */
    public com.falconx.trading.dto.TradingSwapSummaryResponse getPositionSwapSummary(Long userId, Long positionId) {
        return tradingLedgerRepository.aggregateSwap(userId, positionId, null, null);
    }

    /**
     * 账户维度 Swap 聚合（按时间窗，最少 1 天）。
     */
    public com.falconx.trading.dto.TradingSwapSummaryResponse getAccountSwapSummary(Long userId, int days) {
        java.time.OffsetDateTime to = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        java.time.OffsetDateTime from = to.minusDays(Math.max(1, days));
        return tradingLedgerRepository.aggregateSwap(userId, null, from, to);
    }

    public TradingLedgerListResponse listLedgerEntries(ListTradingLedgerEntriesCommand command) {
        int offset = offset(command.page(), command.pageSize());
        boolean hasFilter = command.bizType() != null || command.from() != null || command.to() != null;
        List<TradingLedgerItemResponse> items;
        long total;
        if (hasFilter) {
            items = tradingLedgerRepository.findByUserIdFiltered(
                            command.userId(),
                            command.bizType(),
                            command.from(),
                            command.to(),
                            offset,
                            command.pageSize()
                    ).stream()
                    .map(this::toLedgerResponse)
                    .toList();
            total = tradingLedgerRepository.countByUserIdFiltered(
                    command.userId(),
                    command.bizType(),
                    command.from(),
                    command.to()
            );
        } else {
            items = tradingLedgerRepository.findByUserIdPaginated(
                            command.userId(),
                            offset,
                            command.pageSize()
                    ).stream()
                    .map(this::toLedgerResponse)
                    .toList();
            total = tradingLedgerRepository.countByUserId(command.userId());
        }
        return new TradingLedgerListResponse(
                command.page(),
                command.pageSize(),
                total,
                items
        );
    }

    public TradingLiquidationListResponse listLiquidations(ListTradingLiquidationsCommand command) {
        int offset = offset(command.page(), command.pageSize());
        List<TradingLiquidationItemResponse> items = tradingLiquidationLogRepository.findByUserIdPaginated(
                        command.userId(),
                        offset,
                        command.pageSize()
                ).stream()
                .map(this::toLiquidationResponse)
                .toList();
        return new TradingLiquidationListResponse(
                command.page(),
                command.pageSize(),
                tradingLiquidationLogRepository.countByUserId(command.userId()),
                items
        );
    }

    private int offset(int page, int pageSize) {
        return (page - 1) * pageSize;
    }

    private TradingOrderItemResponse toOrderResponse(TradingOrder order) {
        return new TradingOrderItemResponse(
                order.orderId(),
                order.orderNo(),
                order.symbol(),
                order.side().name(),
                order.orderType().name(),
                order.quantity(),
                order.requestedPrice(),
                order.filledPrice(),
                order.leverage(),
                order.margin(),
                order.fee(),
                order.clientOrderId(),
                order.status().name(),
                order.rejectReason(),
                order.createdAt(),
                order.updatedAt()
        );
    }

    private TradingTradeItemResponse toTradeResponse(TradingTrade trade) {
        return new TradingTradeItemResponse(
                trade.tradeId(),
                trade.orderId(),
                trade.positionId(),
                trade.symbol(),
                trade.side().name(),
                trade.tradeType().name(),
                trade.quantity(),
                trade.price(),
                trade.fee(),
                trade.realizedPnl(),
                trade.tradedAt()
        );
    }

    /**
     * STAGE-14E1 Task 1（master §7.5）：REST 持仓查询与 WS position.update 共用
     * {@link TradingPositionItemResponse}，故同步硬切双币（quoteCurrency / fxRate /
     * unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin）。直接委托
     * {@link TradingUserRealtimePayloadFactory#toPositionPayload}，避免双份组装漂移（DRY）。
     */
    private TradingPositionItemResponse toPositionResponse(TradingPosition position) {
        return payloadFactory.toPositionPayload(position);
    }

    private TradingLedgerItemResponse toLedgerResponse(TradingLedgerEntry entry) {
        return new TradingLedgerItemResponse(
                entry.ledgerId(),
                entry.bizType().name(),
                entry.amount(),
                entry.idempotencyKey(),
                entry.referenceNo(),
                entry.balanceBefore(),
                entry.balanceAfter(),
                entry.frozenBefore(),
                entry.frozenAfter(),
                entry.marginUsedBefore(),
                entry.marginUsedAfter(),
                entry.createdAt()
        );
    }

    private TradingLiquidationItemResponse toLiquidationResponse(TradingLiquidationLog liquidationLog) {
        return new TradingLiquidationItemResponse(
                liquidationLog.liquidationLogId(),
                liquidationLog.positionId(),
                liquidationLog.symbol(),
                liquidationLog.side().name(),
                liquidationLog.marginMode().name(),
                liquidationLog.quantity(),
                liquidationLog.entryPrice(),
                liquidationLog.liquidationPrice(),
                liquidationLog.markPrice(),
                liquidationLog.priceTs(),
                liquidationLog.priceSource(),
                liquidationLog.loss(),
                liquidationLog.fee(),
                liquidationLog.marginReleased(),
                liquidationLog.platformCoveredLoss(),
                liquidationLog.createdAt()
        );
    }
}
