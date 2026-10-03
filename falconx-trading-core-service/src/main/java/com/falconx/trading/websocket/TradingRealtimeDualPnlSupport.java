package com.falconx.trading.websocket;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.support.PnlResult;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * STAGE-14E2 Task 1：实时推送双币浮盈亏共享计算器。
 *
 * <p>把 STAGE-14E1 客户端侧 {@code TradingUserRealtimePayloadFactory#computeDualPnl} 的双币算法
 * 提取为可被客户端 / 管理端 payload 组装共享的单一实现，避免 admin 侧（{@link TradingAdminRealtimePushService}）
 * 另写一份并行双币逻辑造成口径漂移。口径完全复用 STAGE-14B/D2
 * {@link TradingPricingSupport#calculatePositionPnlInAccount}：QC 取自 SymbolSpec（过渡期可能 null），
 * AC=settlementCurrency，fx 用实时 {@link FxRateService}；FX 不可用降级开仓冻结 {@code entryFxRate}（不抛）。
 */
@Component
public class TradingRealtimeDualPnlSupport {

    private static final Logger log = LoggerFactory.getLogger(TradingRealtimeDualPnlSupport.class);
    private static final int AMOUNT_SCALE = 8;

    private final FxRateService fxRateService;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final String settlementCurrency;

    @Autowired
    public TradingRealtimeDualPnlSupport(FxRateService fxRateService,
                                         MarketSymbolSpecRepository marketSymbolSpecRepository,
                                         TradingCoreServiceProperties properties) {
        this(fxRateService, marketSymbolSpecRepository, properties.getSettlementToken());
    }

    /** 测试 / 跨包复用构造：直接注入结算币（账户币 AC）。 */
    public TradingRealtimeDualPnlSupport(FxRateService fxRateService,
                                         MarketSymbolSpecRepository marketSymbolSpecRepository,
                                         String settlementCurrency) {
        this.fxRateService = fxRateService;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.settlementCurrency = settlementCurrency;
    }

    /**
     * 双币浮盈亏 + 元数据，口径复用 STAGE-14B/D2 {@link TradingPricingSupport#calculatePositionPnlInAccount}：
     * QC 取自 SymbolSpec（过渡期可能 null）；AC=settlementCurrency；fx 用实时 {@link FxRateService}。
     *
     * <p><b>FX 降级</b>（沿 D2 §3.5.5）：实时 FX 不可用（inAccount==null 且 inQuote!=null）时，
     * 用开仓冻结 {@code position.entryFxRate()} 换算 inAccount 并以其作为 fxRate，不抛；
     * entryFxRate 亦缺失则 inAccount/fxRate 保持 null。
     */
    public DualPnl computeDualPnl(TradingPosition position, BigDecimal effectiveMarkPrice) {
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, effectiveMarkPrice, quoteCurrency, settlementCurrency, fxRateService);
        BigDecimal inAccount = result.inAccount();
        BigDecimal fxRate = result.fxRate();
        // FX 不可用降级：inQuote 有值但 inAccount/fxRate 为 null（实时 FX 缺失 / QC 缺失）→ 用 entryFxRate。
        if (inAccount == null && result.inQuote() != null && position.entryFxRate() != null) {
            fxRate = position.entryFxRate();
            inAccount = result.inQuote().multiply(fxRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            log.warn("trading.ws.pnl.fx.degraded positionId={} symbol={} QC={} AC={} entryFxRate={}",
                    position.positionId(), position.symbol(), quoteCurrency, settlementCurrency, fxRate);
        }
        return new DualPnl(result.quoteCurrency(), fxRate, result.inQuote(), inAccount);
    }

    /** 计价币代码（QC，来源 SymbolSpec）；过渡期 SymbolSpec 未回填时为 null。供 exposure 等无 PnL 场景复用同一查询。 */
    public String resolveQuoteCurrency(String symbol) {
        if (symbol == null) {
            return null;
        }
        return marketSymbolSpecRepository.findByPlatformSymbol(symbol)
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
    }

    /**
     * 价格显示精度（来源 SymbolSpec.pricePrecision）；过渡期 SymbolSpec 缺失时为 null。
     * 供管理端监控列表按 symbol 格式化价格（口径同 exposure 的 {@link #resolveQuoteCurrency}，复用 10s 本地缓存）。
     */
    public Integer resolvePricePrecision(String symbol) {
        if (symbol == null) {
            return null;
        }
        return marketSymbolSpecRepository.findByPlatformSymbol(symbol)
                .map(SymbolSpec::pricePrecision)
                .orElse(null);
    }

    /** 双币浮盈亏内部载体（quoteCurrency / fxRate / QC 原币 / AC 账户币）。 */
    public record DualPnl(String quoteCurrency, BigDecimal fxRate, BigDecimal inQuote, BigDecimal inAccount) {
    }
}
