package com.falconx.trading.application;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.TradingAccountPositionResponse;
import com.falconx.trading.dto.TradingAccountResponse;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.support.TradingPricingSupport;
import com.falconx.trading.websocket.TradingUserRealtimePayloadFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 用户交易账户快照应用服务。
 *
 * <p>该服务统一给 REST 查询和用户侧 WebSocket 初始快照 / account.update 复用，
 * 避免账户、OPEN 持仓和 Redis 最新价拼装逻辑在多个入口漂移。
 *
 * <p>STAGE-14E1 Task 2（master §7.5 / §3.2）：{@link #toResponse} 在 DB 余额视图之上实时补算
 * 账户级 {@code equity / marginLevel / marginLevelStatus}（复用 {@link AccountEquityCalculator}
 * +{@link MarginLevelMonitor}，FX 不可用降级 null 不抛），并把内嵌 openPositions 由单币硬切双币
 * （复用 {@link TradingUserRealtimePayloadFactory#toAccountPositionResponse}，与 position 列表同源）。
 */
@Service
public class TradingAccountSnapshotApplicationService {

    private final TradingAccountService tradingAccountService;
    private final TradingCoreServiceProperties tradingCoreServiceProperties;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final AccountEquityCalculator accountEquityCalculator;
    private final MarginLevelMonitor marginLevelMonitor;
    private final TradingUserRealtimePayloadFactory payloadFactory;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;

    public TradingAccountSnapshotApplicationService(TradingAccountService tradingAccountService,
                                                    TradingCoreServiceProperties tradingCoreServiceProperties,
                                                    OpenPositionSnapshotStore openPositionSnapshotStore,
                                                    TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                                    AccountEquityCalculator accountEquityCalculator,
                                                    // @Lazy 打破构造期循环：MarginLevelMonitor → TradingNotificationApplicationService
                                                    // → tradingUserRealtimePushService → tradingUserWebSocketSessionRegistry → 本服务。
                                                    // 本服务只在 toResponse 运行期调用 monitor.evaluate，注入代理即可。
                                                    @org.springframework.context.annotation.Lazy MarginLevelMonitor marginLevelMonitor,
                                                    TradingUserRealtimePayloadFactory payloadFactory,
                                                    MarketSymbolSpecRepository marketSymbolSpecRepository) {
        this.tradingAccountService = tradingAccountService;
        this.tradingCoreServiceProperties = tradingCoreServiceProperties;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.accountEquityCalculator = accountEquityCalculator;
        this.marginLevelMonitor = marginLevelMonitor;
        this.payloadFactory = payloadFactory;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
    }

    public TradingAccountResponse getCurrentAccountSnapshot(Long userId) {
        TradingAccount account = tradingAccountService.getOrCreateAccount(
                userId,
                tradingCoreServiceProperties.getSettlementToken()
        );
        return toResponse(account);
    }

    /**
     * STAGE-3-PENDING-ORDER：计算下单 / 挂单是否有足够 available。
     *
     * <p>CROSS-MARGIN-EXEC-01 防御抽象：挂单 / 触发评估 / 修改 / 撤单 全部走该方法，
     * 不得在调用方硬编码 {@code balance - frozen - marginUsed} 公式。
     * CROSS 实施时只需在本方法内分支化，不需要回头改挂单代码。
     *
     * <p>当前实现：仅支持 ISOLATED。CROSS 走旧 {@code MARGIN_MODE_NOT_SUPPORTED} 路径。
     *
     * @return null 表示足够；非 null 表示拒单原因字符串（外层 controller 翻译为错误码）
     */
    public String calculateAvailableForOrder(com.falconx.trading.entity.TradingAccount account,
                                              com.falconx.trading.entity.TradingMarginMode marginMode,
                                              BigDecimal requestedMargin,
                                              BigDecimal requestedFee) {
        if (marginMode != com.falconx.trading.entity.TradingMarginMode.ISOLATED) {
            return "MARGIN_MODE_NOT_SUPPORTED";
        }
        BigDecimal totalRequired = requestedMargin.add(requestedFee);
        if (account.available().compareTo(totalRequired) < 0) {
            return "INSUFFICIENT_AVAILABLE_BALANCE";
        }
        return null;
    }

    /**
     * STAGE-14E1 Task 2：REST GET account / WS 账户快照 / account.update 三处共用的账户响应组装。
     *
     * <p>在 DB 余额视图之上实时补：
     * <ul>
     *   <li>account 头 {@code equity / marginLevel}：账户全部 OPEN 持仓 →
     *       {@link AccountEquityCalculator#computeAccountMarginLevel}（口径
     *       {@code Equity=balance+frozen+ΣuPnL(AC)}，FX 不可用降级 null 不抛，无持仓 marginLevel=null）。</li>
     *   <li>{@code marginLevelStatus}：{@link MarginLevelMonitor#evaluate}（读 t_risk_config 阈值，
     *       marginLevel=null → HEALTHY）。</li>
     *   <li>{@code openPositions}：双币，复用 {@link TradingUserRealtimePayloadFactory#toAccountPositionResponse}
     *       （与 position 列表同源，避免双币组装漂移）。</li>
     * </ul>
     */
    public TradingAccountResponse toResponse(TradingAccount account) {
        List<TradingPosition> positions = openPositionSnapshotStore.listOpenByUserId(account.userId());

        List<TradingAccountPositionResponse> openPositions = positions.stream()
                .map(payloadFactory::toAccountPositionResponse)
                .toList();

        AccountMarginState marginState = accountEquityCalculator.computeAccountMarginLevel(
                account, toMarkInputs(positions));
        MarginLevelStatus status = marginLevelMonitor.evaluate(account.userId(), marginState);

        return new TradingAccountResponse(
                account.accountId(),
                account.userId(),
                account.currency(),
                account.balance(),
                account.frozen(),
                account.marginUsed(),
                account.available(),
                account.marginMode() == null ? null : account.marginMode().name(),
                marginState.equity(),
                marginState.marginLevel(),
                status == null ? null : status.name(),
                openPositions
        );
    }

    /**
     * 账户级 MarginLevel 输入：持仓 + 当前有效标记价（基准 bid/ask，calculator 内部叠加冻结 markup）
     * + 计价币 QC（从 SymbolSpec 取，过渡期可能 null → calculator 据此降级）。
     */
    private List<AccountEquityCalculator.PositionMarkInput> toMarkInputs(List<TradingPosition> positions) {
        List<AccountEquityCalculator.PositionMarkInput> inputs = new ArrayList<>(positions.size());
        for (TradingPosition position : positions) {
            TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
            BigDecimal markPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position.side());
            String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                    .map(SymbolSpec::quoteCurrency)
                    .orElse(null);
            inputs.add(new AccountEquityCalculator.PositionMarkInput(position, markPrice, quoteCurrency));
        }
        return inputs;
    }
}
