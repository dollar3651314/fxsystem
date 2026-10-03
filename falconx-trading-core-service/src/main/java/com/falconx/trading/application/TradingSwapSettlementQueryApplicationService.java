package com.falconx.trading.application;

import com.falconx.trading.command.ListTradingSwapSettlementsCommand;
import com.falconx.trading.dto.TradingSwapSettlementItemResponse;
import com.falconx.trading.dto.TradingSwapSettlementListResponse;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.service.model.TradingSwapSettlementView;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * `Swap` 明细查询应用服务。
 */
@Service
public class TradingSwapSettlementQueryApplicationService {

    private final TradingLedgerRepository tradingLedgerRepository;

    public TradingSwapSettlementQueryApplicationService(TradingLedgerRepository tradingLedgerRepository) {
        this.tradingLedgerRepository = tradingLedgerRepository;
    }

    /**
     * 分页查询当前用户 `Swap` 明细。
     *
     * @param command 查询命令
     * @return 分页结果
     */
    public TradingSwapSettlementListResponse listSwapSettlements(ListTradingSwapSettlementsCommand command) {
        int offset = (command.page() - 1) * command.pageSize();
        List<TradingSwapSettlementItemResponse> items = tradingLedgerRepository.findSwapSettlementsByUserId(
                        command.userId(),
                        offset,
                        command.pageSize()
                ).stream()
                .map(this::toResponse)
                .toList();
        return new TradingSwapSettlementListResponse(
                command.page(),
                command.pageSize(),
                tradingLedgerRepository.countSwapSettlementsByUserId(command.userId()),
                items
        );
    }

    private TradingSwapSettlementItemResponse toResponse(TradingSwapSettlementView view) {
        return new TradingSwapSettlementItemResponse(
                view.ledgerId(),
                view.positionId(),
                view.symbol(),
                view.side() == null ? null : view.side().name(),
                view.settlementType() == null ? null : view.settlementType().name(),
                view.amount(),
                view.balanceAfter(),
                view.rolloverAt(),
                view.settledAt(),
                view.referenceNo()
        );
    }
}
