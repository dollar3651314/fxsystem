package com.falconx.trading.application;

import com.falconx.trading.dto.TradingPlatformMetricsResponse;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.FlowsBlock;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.OrdersBlock;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.PositionsBlock;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.RevenueBlock;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.UserPnlBlock;
import com.falconx.trading.dto.TradingPlatformMetricsResponse.UserPnlBlock.BiggestPosition;
import com.falconx.trading.repository.mapper.PlatformMetricsMapper;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.BiggestPositionRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.BizTypeAggregateRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.PositionsStatRow;
import com.falconx.trading.repository.mapper.record.PlatformMetricsRecords.UserPnlBucketRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 平台 dashboard 指标聚合 service。
 *
 * <p>5 段聚合数据一次性算出：实时持仓 / 平台收入 / 用户盈亏 / 资金流入流出 / 订单计数。
 * 关键数据基本走单次 SQL：t_position 一条 CASE WHEN 出 6 个数字、t_ledger 一条 GROUP BY 出
 * lifetime 5 类 + 30d 5 类。
 */
@Service
public class TradingPlatformMetricsApplicationService {

    private static final int BIZ_DEPOSIT_CREDIT = 1;
    private static final int BIZ_ORDER_FEE = 4;
    private static final int BIZ_SWAP_CHARGE = 6;
    private static final int BIZ_SWAP_INCOME = 7;
    private static final int BIZ_WITHDRAW_SETTLE = 17;

    private static final List<Integer> ALL_BIZ_TYPES = List.of(
            BIZ_DEPOSIT_CREDIT, BIZ_ORDER_FEE, BIZ_SWAP_CHARGE, BIZ_SWAP_INCOME, BIZ_WITHDRAW_SETTLE
    );

    private final PlatformMetricsMapper mapper;

    public TradingPlatformMetricsApplicationService(PlatformMetricsMapper mapper) {
        this.mapper = mapper;
    }

    public TradingPlatformMetricsResponse buildOverview() {
        PositionsStatRow posRow = mapper.selectPositionsStat();
        if (posRow == null) {
            posRow = new PositionsStatRow(0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO);
        }

        LocalDateTime since30d = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        Map<Integer, BigDecimal> ledgerAllTime = toBizMap(mapper.selectLedgerAggregate(ALL_BIZ_TYPES, null));
        Map<Integer, BigDecimal> ledger30d = toBizMap(mapper.selectLedgerAggregate(ALL_BIZ_TYPES, since30d));

        BigDecimal feeAll = ledgerAllTime.getOrDefault(BIZ_ORDER_FEE, BigDecimal.ZERO);
        BigDecimal fee30 = ledger30d.getOrDefault(BIZ_ORDER_FEE, BigDecimal.ZERO);
        BigDecimal swapChargeAll = ledgerAllTime.getOrDefault(BIZ_SWAP_CHARGE, BigDecimal.ZERO);
        BigDecimal swapIncomeAll = ledgerAllTime.getOrDefault(BIZ_SWAP_INCOME, BigDecimal.ZERO);
        BigDecimal swapCharge30 = ledger30d.getOrDefault(BIZ_SWAP_CHARGE, BigDecimal.ZERO);
        BigDecimal swapIncome30 = ledger30d.getOrDefault(BIZ_SWAP_INCOME, BigDecimal.ZERO);
        BigDecimal swapNetPlatformAll = swapChargeAll.subtract(swapIncomeAll);
        BigDecimal swapNetPlatform30 = swapCharge30.subtract(swapIncome30);
        BigDecimal platformFeeRevAll = feeAll.add(swapNetPlatformAll);
        BigDecimal platformFeeRev30 = fee30.add(swapNetPlatform30);

        BigDecimal depositAll = ledgerAllTime.getOrDefault(BIZ_DEPOSIT_CREDIT, BigDecimal.ZERO);
        BigDecimal deposit30 = ledger30d.getOrDefault(BIZ_DEPOSIT_CREDIT, BigDecimal.ZERO);
        BigDecimal withdrawAll = ledgerAllTime.getOrDefault(BIZ_WITHDRAW_SETTLE, BigDecimal.ZERO);
        BigDecimal withdraw30 = ledger30d.getOrDefault(BIZ_WITHDRAW_SETTLE, BigDecimal.ZERO);

        List<UserPnlBucketRow> bucketRows = mapper.selectUserPnlDistribution();
        long winningUsers = 0, losingUsers = 0, evenUsers = 0;
        for (UserPnlBucketRow r : bucketRows) {
            long count = r.userCount() == null ? 0L : r.userCount();
            switch (r.bucket()) {
                case "WIN" -> winningUsers = count;
                case "LOSE" -> losingUsers = count;
                case "EVEN" -> evenUsers = count;
                default -> { /* ignore */ }
            }
        }

        BiggestPosition biggestWinner = mapBiggest(mapper.selectBiggestPosition("WIN"));
        BiggestPosition biggestLoser = mapBiggest(mapper.selectBiggestPosition("LOSE"));

        long filledTotal = mapper.countFilledOrders();
        long filledToday = mapper.countFilledOrdersToday();

        return new TradingPlatformMetricsResponse(
                new PositionsBlock(
                        posRow.openCount() == null ? 0L : posRow.openCount(),
                        posRow.longCount() == null ? 0L : posRow.longCount(),
                        posRow.shortCount() == null ? 0L : posRow.shortCount(),
                        posRow.closedCount() == null ? 0L : posRow.closedCount(),
                        posRow.liquidatedCount() == null ? 0L : posRow.liquidatedCount(),
                        posRow.totalRealizedPnl() == null ? BigDecimal.ZERO : posRow.totalRealizedPnl()
                ),
                new RevenueBlock(
                        feeAll, fee30,
                        swapChargeAll, swapIncomeAll, swapNetPlatformAll,
                        swapCharge30, swapIncome30, swapNetPlatform30,
                        platformFeeRevAll, platformFeeRev30
                ),
                new UserPnlBlock(winningUsers, losingUsers, evenUsers, biggestWinner, biggestLoser),
                new FlowsBlock(
                        depositAll, deposit30,
                        withdrawAll, withdraw30,
                        depositAll.subtract(withdrawAll),
                        deposit30.subtract(withdraw30)
                ),
                new OrdersBlock(filledTotal, filledToday),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private Map<Integer, BigDecimal> toBizMap(List<BizTypeAggregateRow> rows) {
        Map<Integer, BigDecimal> m = new HashMap<>();
        for (BizTypeAggregateRow r : rows) {
            if (r.bizType() == null) continue;
            m.put(r.bizType(), r.totalAmount() == null ? BigDecimal.ZERO : r.totalAmount());
        }
        return m;
    }

    private BiggestPosition mapBiggest(BiggestPositionRow row) {
        if (row == null) return BiggestPosition.empty();
        return new BiggestPosition(row.userId(), row.symbol(),
                row.realizedPnl() == null ? BigDecimal.ZERO : row.realizedPnl());
    }
}
