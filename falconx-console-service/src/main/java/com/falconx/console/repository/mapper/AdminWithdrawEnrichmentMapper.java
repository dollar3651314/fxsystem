package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminWithdrawEnrichmentRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-7-WITHDRAW Phase 4 §4 commit C：管理端出金审核扩展字段查询。
 *
 * <p>用户当日累计 SQL 与 trading-core
 * {@link com.falconx.trading.repository.TradingWithdrawOrderRepository#sumActiveAmountForUserOnDay}
 * 口径一致：COOLING(0)/PENDING(1)/APPROVED(2)/APPROVED_DELAYED(3)/PROCESSING(4)/COMPLETED(5) 计入；
 * FAILED(6)/CANCELED(7)/REJECTED(8) 已退冻不计入。
 *
 * <p>跨 schema：依赖 console DB user 拥有 {@code falconx_identity} + {@code falconx_trading} SELECT
 * 权限（沿用 AdminCustomerMapper 既有跨 schema 模式）。
 */
@Mapper
public interface AdminWithdrawEnrichmentMapper {

    /**
     * 按 userId 集合批查 enrichment（一次 SQL 拿全部，list() 阶段避免 N+1）。
     *
     * @param userIds      去重后的用户 ID 集合；为空时调用方应直接跳过该方法
     * @param dayStartUtc  当日 UTC 起点（含）
     * @param dayEndUtc    次日 UTC 起点（不含）
     * @return 每个用户一行；用户存在但当日无累计出金时 dailyAccumulatedUsd=0
     */
    List<AdminWithdrawEnrichmentRecord> selectEnrichmentByUserIds(
            @Param("userIds") List<Long> userIds,
            @Param("dayStartUtc") LocalDateTime dayStartUtc,
            @Param("dayEndUtc") LocalDateTime dayEndUtc);
}
