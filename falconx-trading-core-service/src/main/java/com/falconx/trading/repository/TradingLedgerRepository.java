package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.service.model.TradingSwapSettlementView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交易账本仓储接口。
 *
 * <p>该接口负责保存和查询 `t_ledger` 的最小流水能力，
 * 正式数据库实现统一通过 `MyBatis Mapper + XML` 承载。
 */
public interface TradingLedgerRepository {

    /**
     * 保存账本流水。
     *
     * @param entry 账本流水
     * @return 持久化后的账本对象
     */
    TradingLedgerEntry save(TradingLedgerEntry entry);

    /**
     * 批量持久化账本流水（仅支持新增，不支持更新）。
     *
     * <p>{@link com.falconx.trading.service.TradingAccountService#applyOrderPlacementAccountChange}
     * 一次写 3 条 ledger 用。生成 id（雪花）+ 拼装 record 在 Repository 层完成，
     * 让 Service 层只关心 {@link TradingLedgerEntry} 领域对象。
     *
     * <p>原子性：底层走 MyBatis foreach multi-row INSERT，单 SQL 要么全成功要么全失败。
     *
     * @param entries 待写入的账本对象列表，{@link TradingLedgerEntry#ledgerId()} 可为 null，
     *                由 Repository 调用 idGenerator 填充
     * @return 持久化后的账本对象列表，顺序与入参一致，{@code ledgerId} 已填
     */
    List<TradingLedgerEntry> batchInsert(List<TradingLedgerEntry> entries);

    /**
     * 查询用户全部账本流水。
     *
     * @param userId 用户 ID
     * @return 该用户账本列表
     */
    List<TradingLedgerEntry> findByUserId(Long userId);

    /**
     * 分页查询用户账本流水。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 当前页账本列表
     */
    List<TradingLedgerEntry> findByUserIdPaginated(Long userId, int offset, int limit);

    /**
     * 统计用户账本流水总数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countByUserId(Long userId);

    /**
     * 按业务类型 + 时间范围过滤分页查询用户账本流水。所有过滤参数均可为 {@code null}（不过滤）。
     */
    List<TradingLedgerEntry> findByUserIdFiltered(Long userId,
                                                  com.falconx.trading.entity.TradingLedgerBizType bizType,
                                                  OffsetDateTime from,
                                                  OffsetDateTime to,
                                                  int offset,
                                                  int limit);

    long countByUserIdFiltered(Long userId,
                               com.falconx.trading.entity.TradingLedgerBizType bizType,
                               OffsetDateTime from,
                               OffsetDateTime to);

    /**
     * Swap 聚合查询。持仓维度传 {@code positionId}，账户维度传 null。时间窗均可空。
     * 返回的 DTO 已经把 mapper 的 0/1/2 行结果拍平到 totalCharge / totalIncome；
     * 无任何命中时返回 {@link com.falconx.trading.dto.TradingSwapSummaryResponse#empty()}。
     */
    com.falconx.trading.dto.TradingSwapSummaryResponse aggregateSwap(Long userId,
                                                                     Long positionId,
                                                                     OffsetDateTime from,
                                                                     OffsetDateTime to);

    /**
     * 分页查询用户 `Swap` 明细。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return `Swap` 明细列表
     */
    List<TradingSwapSettlementView> findSwapSettlementsByUserId(Long userId, int offset, int limit);

    /**
     * 统计用户 `Swap` 明细总条数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countSwapSettlementsByUserId(Long userId);

    /**
     * 判断某个账务幂等键是否已经存在。
     *
     * @param userId 用户 ID
     * @param idempotencyKey 账务幂等键
     * @return `true` 表示已存在
     */
    boolean existsByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * 查询指定持仓最近一次 `Swap` 结算时间。
     *
     * @param userId 用户 ID
     * @param positionId 持仓 ID
     * @return 最近一次 `Swap` 账本的发生时间
     */
    Optional<OffsetDateTime> findLatestSwapSettlementAt(Long userId, Long positionId);
}
