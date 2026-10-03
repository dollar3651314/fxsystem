package com.falconx.trading.application;

import com.falconx.trading.api.AdminManualLiquidateResponse;
import com.falconx.trading.api.AdminRiskSwitchUpdateResponse;
import com.falconx.trading.api.AdminTradingExposureListResponse;
import com.falconx.trading.api.AdminTradingOrderListResponse;
import com.falconx.trading.api.AdminTradingPositionListResponse;
import com.falconx.trading.api.AdminTradingPositionSummaryResponse;
import com.falconx.trading.api.AdminTradingRiskSwitchListResponse;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import com.falconx.trading.repository.mapper.TradingOrderMapper;
import com.falconx.trading.repository.mapper.TradingPositionMapper;
import com.falconx.trading.repository.mapper.record.TradingOrderRecord;
import com.falconx.trading.repository.mapper.record.TradingPositionRecord;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-TRADING-MONITOR R4：管理端订单 / 持仓 / 敞口 / 风控开关只读 + 手动强平 + 切换开关编排。
 *
 * <p>本服务承接来自 {@code AdminInternalTradingConsoleController} 的请求；
 * 鉴权由 {@link com.falconx.trading.security.TradingInternalApiTokenFilter} 在 filter 层完成。
 */
@Service
public class TradingMonitorAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingMonitorAdminApplicationService.class);

    private final TradingOrderMapper orderMapper;
    private final TradingPositionMapper positionMapper;
    private final TradingPositionRepository positionRepository;
    private final TradingQuoteSnapshotRepository quoteSnapshotRepository;
    private final TradingRiskExposureRepository exposureRepository;
    private final TradingRiskSwitchApplicationService riskSwitchApplicationService;
    private final TradingPositionCloseApplicationService positionCloseApplicationService;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;
    private final com.falconx.trading.repository.TradingLedgerRepository tradingLedgerRepository;
    private final com.falconx.trading.websocket.TradingRealtimeDualPnlSupport dualPnlSupport;

    public TradingMonitorAdminApplicationService(TradingOrderMapper orderMapper,
                                                 TradingPositionMapper positionMapper,
                                                 TradingPositionRepository positionRepository,
                                                 TradingQuoteSnapshotRepository quoteSnapshotRepository,
                                                 TradingRiskExposureRepository exposureRepository,
                                                 TradingRiskSwitchApplicationService riskSwitchApplicationService,
                                                 TradingPositionCloseApplicationService positionCloseApplicationService,
                                                 com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService,
                                                 com.falconx.trading.repository.TradingLedgerRepository tradingLedgerRepository,
                                                 com.falconx.trading.websocket.TradingRealtimeDualPnlSupport dualPnlSupport) {
        this.orderMapper = orderMapper;
        this.positionMapper = positionMapper;
        this.positionRepository = positionRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.exposureRepository = exposureRepository;
        this.riskSwitchApplicationService = riskSwitchApplicationService;
        this.positionCloseApplicationService = positionCloseApplicationService;
        this.adminRealtimePushService = adminRealtimePushService;
        this.tradingLedgerRepository = tradingLedgerRepository;
        this.dualPnlSupport = dualPnlSupport;
    }

    /**
     * admin 查持仓 Swap 聚合，跨 user — userId 传 null，仅按 reference_no LIKE 'swap:{positionId}:%' 过滤。
     */
    public com.falconx.trading.dto.TradingSwapSummaryResponse getPositionSwapSummary(Long positionId) {
        return tradingLedgerRepository.aggregateSwap(null, positionId, null, null);
    }

    public AdminTradingOrderListResponse listOrders(Long userId, String symbol, Integer status,
                                                    OffsetDateTime fromCreatedAt, OffsetDateTime toCreatedAt,
                                                    int page, int size) {
        int offset = (page - 1) * size;
        LocalDateTime fromLdt = toLocalDateTime(fromCreatedAt);
        LocalDateTime toLdt = toLocalDateTime(toCreatedAt);
        List<TradingOrderRecord> records = orderMapper.selectAdminPaginated(
                userId, blankToNull(symbol), status, fromLdt, toLdt, offset, size);
        long total = orderMapper.countAdminFiltered(userId, blankToNull(symbol), status, fromLdt, toLdt);
        List<AdminTradingOrderListResponse.Item> items = records.stream()
                .map(this::toOrderItem)
                .toList();
        return new AdminTradingOrderListResponse(items, total, page, size);
    }

    public AdminTradingPositionListResponse listPositions(Long userId, String symbol, Integer status,
                                                          OffsetDateTime fromOpenedAt, OffsetDateTime toOpenedAt,
                                                          int page, int size) {
        int offset = (page - 1) * size;
        LocalDateTime fromLdt = toLocalDateTime(fromOpenedAt);
        LocalDateTime toLdt = toLocalDateTime(toOpenedAt);
        List<TradingPositionRecord> records = positionMapper.selectAdminPaginated(
                userId, blankToNull(symbol), status, fromLdt, toLdt, offset, size);
        long total = positionMapper.countAdminFiltered(userId, blankToNull(symbol), status, fromLdt, toLdt);
        Map<String, TradingQuoteSnapshot> quoteBySymbol = loadQuotesForOpenRecords(records);
        List<AdminTradingPositionListResponse.Item> items = records.stream()
                .map(r -> toPositionItem(r, quoteBySymbol))
                .toList();
        return new AdminTradingPositionListResponse(items, total, page, size);
    }

    /**
     * 平台 OPEN 持仓汇总：未实现盈亏总额 + 占用保证金合计 + OPEN 数。
     * 缺 quote 的持仓不计入 PnL（按 0 处理），但单独计数返回给运营定位。
     */
    public AdminTradingPositionSummaryResponse getPositionSummary() {
        List<TradingPosition> openPositions = positionRepository.findAllOpenPositions();
        Set<String> symbols = openPositions.stream().map(TradingPosition::symbol).collect(Collectors.toSet());
        Map<String, TradingQuoteSnapshot> quoteBySymbol = new HashMap<>();
        for (String s : symbols) {
            quoteSnapshotRepository.findBySymbol(s).ifPresent(q -> quoteBySymbol.put(s, q));
        }
        BigDecimal totalMargin = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        long withoutQuote = 0;
        for (TradingPosition p : openPositions) {
            if (p.margin() != null) totalMargin = totalMargin.add(p.margin());
            TradingQuoteSnapshot quote = quoteBySymbol.get(p.symbol());
            BigDecimal markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, p.side());
            if (markPrice == null) {
                withoutQuote++;
                continue;
            }
            BigDecimal pnl = TradingPricingSupport.calculatePositionPnl(p, markPrice);
            if (pnl != null) totalPnl = totalPnl.add(pnl);
        }
        log.info("trading.admin.positions.summary.computed openCount={} totalMargin={} totalPnl={} missingQuote={}",
                openPositions.size(), totalMargin, totalPnl, withoutQuote);
        return new AdminTradingPositionSummaryResponse(
                openPositions.size(),
                totalMargin,
                totalPnl,
                withoutQuote,
                OffsetDateTime.now()
        );
    }

    private Map<String, TradingQuoteSnapshot> loadQuotesForOpenRecords(List<TradingPositionRecord> records) {
        Set<String> openSymbols = records.stream()
                .filter(r -> r.statusCode() != null && r.statusCode() == 1)
                .map(TradingPositionRecord::symbol)
                .collect(Collectors.toSet());
        Map<String, TradingQuoteSnapshot> map = new HashMap<>();
        for (String s : openSymbols) {
            quoteSnapshotRepository.findBySymbol(s).ifPresent(q -> map.put(s, q));
        }
        return map;
    }

    public AdminTradingExposureListResponse listExposures(String symbol) {
        List<TradingRiskExposure> exposures = exposureRepository.selectAdminAll(blankToNull(symbol));
        // STAGE-14E2 Task 2：每 symbol 补 quoteCurrency（复用 Task1 同源取法
        // TradingRealtimeDualPnlSupport#resolveQuoteCurrency → SymbolSpec.quoteCurrency），
        // 过渡期 SymbolSpec 缺失时降级 null（不抛），供前端按报价币聚合 netExposureUsd。
        List<AdminTradingExposureListResponse.Item> items = exposures.stream()
                .map(e -> new AdminTradingExposureListResponse.Item(
                        e.symbol(),
                        dualPnlSupport.resolveQuoteCurrency(e.symbol()),
                        e.totalLongQty(),
                        e.totalShortQty(),
                        e.netExposure(),
                        e.netExposureUsd(),
                        e.updatedAt()
                ))
                .toList();
        return new AdminTradingExposureListResponse(items);
    }

    public AdminTradingRiskSwitchListResponse listRiskSwitches() {
        List<TradingRiskSwitch> switches = riskSwitchApplicationService.listAll();
        List<AdminTradingRiskSwitchListResponse.Item> items = switches.stream()
                .map(s -> new AdminTradingRiskSwitchListResponse.Item(
                        s.switchKey(),
                        s.enabled(),
                        s.reason(),
                        s.updatedBy(),
                        s.updatedAt()
                ))
                .toList();
        return new AdminTradingRiskSwitchListResponse(items);
    }

    public AdminManualLiquidateResponse manualLiquidate(Long positionId, long adminUserId, String reason) {
        PositionCloseResult result = positionCloseApplicationService.forceLiquidatePositionByAdmin(positionId);
        TradingPosition exited = result.position();
        log.warn("trading.admin.manual-liquidate.executed positionId={} adminUserId={} reason={} realizedPnl={} liquidationLogId={}",
                positionId, adminUserId, reason, exited.realizedPnl(),
                result.liquidationLog() != null ? result.liquidationLog().liquidationLogId() : null);
        return new AdminManualLiquidateResponse(
                exited.positionId(),
                result.liquidationLog() != null ? result.liquidationLog().liquidationLogId() : null,
                exited.closedAt(),
                exited.realizedPnl()
        );
    }

    public AdminRiskSwitchUpdateResponse updateRiskSwitch(String switchKey, boolean enabled,
                                                          String reason, long adminUserId) {
        TradingRiskSwitch updated = riskSwitchApplicationService.updateSwitch(
                switchKey, enabled, reason, String.valueOf(adminUserId));
        // STAGE-2-REALTIME-DATA Phase 2：管理端推送
        try {
            adminRealtimePushService.publishRiskSwitchChanged(updated);
        } catch (RuntimeException exception) {
            log.warn("trading.admin.risk-switch.push.failed switchKey={} enabled={} message={}",
                    switchKey, enabled, exception.getMessage());
        }
        return new AdminRiskSwitchUpdateResponse(
                updated.switchKey(),
                updated.enabled(),
                updated.updatedAt(),
                updated.updatedBy()
        );
    }

    private AdminTradingOrderListResponse.Item toOrderItem(TradingOrderRecord record) {
        return new AdminTradingOrderListResponse.Item(
                record.id(),
                record.orderNo(),
                record.userId(),
                record.symbol(),
                record.sideCode(),
                record.orderTypeCode(),
                record.quantity(),
                record.requestedPrice(),
                record.filledPrice(),
                record.leverage(),
                record.margin(),
                record.fee(),
                record.openFeeRate(),
                record.clientOrderId(),
                record.statusCode(),
                record.rejectReason(),
                record.createdAt(),
                record.updatedAt(),
                dualPnlSupport.resolvePricePrecision(record.symbol())
        );
    }

    private AdminTradingPositionListResponse.Item toPositionItem(TradingPositionRecord record,
                                                                  Map<String, TradingQuoteSnapshot> quoteBySymbol) {
        BigDecimal markPrice = null;
        BigDecimal unrealizedPnl = null;
        if (record.statusCode() != null && record.statusCode() == 1) {
            TradingQuoteSnapshot quote = quoteBySymbol.get(record.symbol());
            TradingOrderSide side = record.sideCode() == 1 ? TradingOrderSide.BUY : TradingOrderSide.SELL;
            markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, side);
            if (markPrice != null && record.entryPrice() != null && record.quantity() != null) {
                BigDecimal delta = side == TradingOrderSide.BUY
                        ? markPrice.subtract(record.entryPrice())
                        : record.entryPrice().subtract(markPrice);
                unrealizedPnl = TradingPricingSupport.scaleAmount(delta.multiply(record.quantity()));
            }
        }
        return new AdminTradingPositionListResponse.Item(
                record.id(),
                record.openingOrderId(),
                record.userId(),
                record.symbol(),
                record.sideCode(),
                record.quantity(),
                record.entryPrice(),
                record.leverage(),
                record.margin(),
                record.marginModeCode(),
                record.liquidationPrice(),
                record.takeProfitPrice(),
                record.stopLossPrice(),
                record.closePrice(),
                record.closeReasonCode(),
                record.realizedPnl(),
                record.openFeeRate(),
                record.statusCode(),
                record.openedAt(),
                record.closedAt(),
                record.updatedAt(),
                markPrice,
                unrealizedPnl,
                dualPnlSupport.resolvePricePrecision(record.symbol())
        );
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toLocalDateTime();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
