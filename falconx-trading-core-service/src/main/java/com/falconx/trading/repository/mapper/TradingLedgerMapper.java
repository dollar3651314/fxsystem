package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingLedgerRecord;
import com.falconx.trading.repository.mapper.record.TradingSwapAggregateRow;
import com.falconx.trading.repository.mapper.record.TradingSwapSettlementRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易账本 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_ledger` 的 SQL 声明。
 */
@Mapper
public interface TradingLedgerMapper {

    /**
     * 插入账本流水。
     *
     * @param record 账本记录
     * @return 影响行数
     */
    int insertTradingLedger(TradingLedgerRecord record);

    /**
     * 批量插入账本流水（单 SQL multi-row INSERT）。
     *
     * <p>专为 Sprint 3 C1 复合方法 {@code applyOrderPlacementAccountChange} 准备 —
     * 下单链路一次写 3 条 ledger（biz_type ORDER_MARGIN_RESERVED / TRADE_FEE /
     * ORDER_MARGIN_USED_CONFIRM），相比 3 次 insertTradingLedger 减少 2 个 SQL roundtrip。
     *
     * <p>原子性：单 SQL，要么全成功要么全失败；任一行 UNIQUE 约束冲突（idempotency_key
     * 重复）则整个 INSERT 失败抛 DataIntegrityViolationException。
     *
     * @param records 待写入的账本记录列表，要求非空、非空集合
     * @return 实际插入行数（正常等于 records.size()）
     */
    int batchInsert(@Param("list") List<TradingLedgerRecord> records);

    /**
     * 查询用户全部账本流水。
     *
     * @param userId 用户 ID
     * @return 账本记录列表
     */
    List<TradingLedgerRecord> selectByUserId(@Param("userId") Long userId);

    /**
     * 分页查询用户账本流水。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 账本记录列表
     */
    List<TradingLedgerRecord> selectByUserIdPaginated(@Param("userId") Long userId,
                                                      @Param("offset") int offset,
                                                      @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);

    /**
     * 按业务类型 + 时间范围过滤分页查询。所有过滤参数均可为 {@code null}（视为「不过滤」）。
     *
     * @param userId 用户 ID
     * @param bizTypeCode 账本业务类型码（{@code TradingMybatisSupport.toLedgerBizTypeCode}），可为空
     * @param fromTs 起始时间（含），可为空
     * @param toTs 结束时间（含），可为空
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 账本记录列表
     */
    List<TradingLedgerRecord> selectByUserIdFiltered(@Param("userId") Long userId,
                                                     @Param("bizTypeCode") Integer bizTypeCode,
                                                     @Param("fromTs") LocalDateTime fromTs,
                                                     @Param("toTs") LocalDateTime toTs,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    long countByUserIdFiltered(@Param("userId") Long userId,
                               @Param("bizTypeCode") Integer bizTypeCode,
                               @Param("fromTs") LocalDateTime fromTs,
                               @Param("toTs") LocalDateTime toTs);

    /**
     * Swap 聚合查询：按 biz_type 分组返回 SUM(amount) / COUNT / MIN/MAX(created_at)。
     * 仅看 biz_type IN (6=SWAP_CHARGE, 7=SWAP_INCOME)。
     *
     * @param userId 用户 ID（必传）
     * @param referenceLike reference_no 的 LIKE pattern（按持仓维度时传 "swap:{positionId}:%"，账户维度传 null）
     * @param fromTs 起始时间含，可空
     * @param toTs 结束时间含，可空
     * @return 0/1/2 行，每行对应一个 biz_type 的聚合
     */
    List<TradingSwapAggregateRow> selectSwapAggregate(@Param("userId") Long userId,
                                                      @Param("referenceLike") String referenceLike,
                                                      @Param("fromTs") LocalDateTime fromTs,
                                                      @Param("toTs") LocalDateTime toTs);

    /**
     * 分页查询用户 `Swap` 明细。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return `Swap` 明细记录
     */
    List<TradingSwapSettlementRecord> selectSwapSettlementsByUserId(@Param("userId") Long userId,
                                                                    @Param("offset") int offset,
                                                                    @Param("limit") int limit);

    /**
     * 统计用户 `Swap` 明细总数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countSwapSettlementsByUserId(@Param("userId") Long userId);

    /**
     * 统计指定幂等键的账本记录数量。
     *
     * @param userId 用户 ID
     * @param idempotencyKey 账务幂等键
     * @return 命中数量
     */
    int countByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                       @Param("idempotencyKey") String idempotencyKey);

    /**
     * 查询某个持仓最近一次 `Swap` 结算时间。
     *
     * @param userId 用户 ID
     * @param positionId 持仓 ID
     * @return 最近一次账本时间
     */
    LocalDateTime selectLatestSwapSettlementAt(@Param("userId") Long userId,
                                               @Param("positionId") Long positionId);
}
