package com.falconx.trading.repository.mapper.test;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * trading-core-service 测试专用 Mapper。
 *
 * <p>该 Mapper 只存在于测试源码中，用来完成 Stage 5 集成测试的环境清理和结果断言。
 * 这样测试代码本身也不再使用字符串 SQL，而是继续遵循 `MyBatis + XML` 规范。
 */
@Mapper
public interface TradingTestSupportMapper {

    /**
     * 依赖关系从弱到强清空 owner 表，避免外键或唯一键残留影响下一条用例。
     */
    default void clearOwnerTables() {
        deleteInbox();
        deleteOutbox();
        deleteHedgeLog();
        deleteLiquidationLog();
        deleteRiskExposure();
        deleteTrade();
        deletePosition();
        deleteOrder();
        deleteDeposit();
        deleteWithdrawOrder();
        deleteLedger();
        deleteAccount();
        deleteRiskControlAction();
        deleteNotification();
        resetRiskConfigDefaults();
        seedIntegrationTestLeverageTiers();
    }

    /**
     * STAGE-14C1 Task 11（Finding 3 修复）：为既有强平 / 开仓 IT 用的测试 symbol 注入 default 组单档 tier。
     *
     * <p>Task 6 后开仓强制 tier 校验：IT 共享库的 tier seed（V30）按 market t_symbol 生成。其中
     * <b>纯测试 symbol</b>（BTCUSDT / ETHUSDT / ZZZAUD 不在 market t_symbol → V30 无对应行）开仓会被
     * {@code TIER_CONFIG_NOT_FOUND}（30072）拒，position 为 null → 既有 IT 红。此处在每个 IT 的
     * {@code @BeforeEach}（经 {@link #clearOwnerTables()}）幂等地仅为这 3 个纯测试 symbol 注入一条覆盖全
     * notional 区间的单档 tier。
     *
     * <p><b>不触碰 V30 已 seed 的真实 symbol</b>（BTCUSD/EURUSD/AUDCAD/EURAUD/GBPAUD/GBPUSD/XAUUSD 等）：
     * 它们的 V30 多档 tier 已能解析（IT 用 0.1 lot 小单落 tier1，maxLev 300/500x ≥ IT 用到的 10/100/200x），
     * 删除会破坏 {@code SymbolLeverageTierRepositoryIntegrationTests} 与本任务 IT-002 的 seed 抽查。
     *
     * <p>口径：maxLeverage=200（覆盖 IT 用到的 10/100/200x），mmRate=<b>0.005000</b>。该 mmRate 与
     * Task 6 前开仓强平价口径（{@code properties.maintenanceMarginRate=0.005}）一致 —— 既有强平 IT
     * （{@code TradingLiquidationIntegrationTests}/{@code TradingAutoCloseIntegrationTests}）的强平价 /
     * 平仓价 / 已实现盈亏期望值都按 0.005 调出来的（如 10x BUY entry=10000 → liqPrice=10000×(1−1/10+0.005)=9050），
     * 统一为 0.005 才能让这些既有 IT 不漂移。{@code 200×0.005=1.0 ≤ 1.0} 满足 CHECK max_lev×mm_rate≤1.0。
     * 不改生产 tier seed（V30，market owner 数据）—— 仅测试侧注入纯测试 symbol。
     *
     * <p>幂等：先删该 symbol/group 全档再插 tier_no=1（notional 0 ~ 无上限）；id 用稳定基址 + 偏移避免冲突。
     * 需要自定义杠杆/mmRate 的单个用例（如 StopOut IT 的 BTCUSDT maxLev=100/mm=0.01）仍可在自身
     * {@code @BeforeEach} 用 {@link #seedSingleLeverageTier} 覆盖（同为 delete+insert，后写生效）。
     */
    default void seedIntegrationTestLeverageTiers() {
        String[] testOnlySymbols = {"BTCUSDT", "ETHUSDT", "ZZZAUD"};
        long idBase = 39901000L;
        for (int i = 0; i < testOnlySymbols.length; i++) {
            deleteLeverageTierBySymbolAndGroup(testOnlySymbols[i], "default");
            insertFullRangeLeverageTier(idBase + i, testOnlySymbols[i], "default", 200, "0.005000");
        }
    }

    /**
     * 插入一条覆盖全 notional 区间（lower=0、upper=NULL）的单档 tier，id 由调用方指定（多 symbol 批量注入用）。
     */
    int insertFullRangeLeverageTier(@Param("id") long id,
                                    @Param("symbol") String symbol,
                                    @Param("groupCode") String groupCode,
                                    @Param("maxLeverage") int maxLeverage,
                                    @Param("mmRate") String mmRate);

    /**
     * STAGE-14C1 Task 9 IT：为指定 symbol 重置一条单档杠杆 tier（覆盖全 notional 区间），
     * 让 IT DB 缺失 tier seed 时下单不被 TIER_CONFIG_NOT_FOUND 拒（Task 12 才做全量 Flyway tier seed）。
     * 幂等：先删该 symbol/group 全档再插一条 tier_no=1（notional 0 ~ 无上限）。
     */
    default void seedSingleLeverageTier(String symbol, String groupCode, int maxLeverage, String mmRate) {
        deleteLeverageTierBySymbolAndGroup(symbol, groupCode);
        insertSingleLeverageTier(symbol, groupCode, maxLeverage, mmRate);
    }

    int deleteLeverageTierBySymbolAndGroup(@Param("symbol") String symbol,
                                           @Param("groupCode") String groupCode);

    // ===== STAGE-14C1 Task 11 IT 专用查询/构造 =====

    /** IT-001：尝试插入一条违反 CHECK(max_lev×mm_rate≤1.0) 的 tier；DB 拒绝则抛异常（测试断言抛出）。 */
    int insertViolatingCheckLeverageTier(@Param("id") long id,
                                         @Param("symbol") String symbol,
                                         @Param("maxLeverage") int maxLeverage,
                                         @Param("mmRate") String mmRate);

    /** IT-001：校验某表列是否存在（information_schema），用于核对 V31/V32 列已 migrate。 */
    Integer countTableColumnByName(@Param("tableName") String tableName,
                                   @Param("columnName") String columnName);

    /** IT-002/011：某 symbol+default 组的 tier 行数。 */
    Integer countLeverageTiersBySymbol(@Param("symbol") String symbol);

    /** IT-002：tier1 maxLev=给定值的 distinct symbol 数（验证 T4 当前空映射）。 */
    Integer countDistinctSymbolsByTier1MaxLeverage(@Param("maxLeverage") int maxLeverage);

    /** IT-002：某 symbol+default 组某档的 max_leverage。 */
    Integer selectTierMaxLeverage(@Param("symbol") String symbol, @Param("tierNo") int tierNo);

    /** IT-002：某 symbol+default 组某档的 mm_rate。 */
    String selectTierMmRate(@Param("symbol") String symbol, @Param("tierNo") int tierNo);

    /** IT-013：读 t_risk_config 平台行（symbol IS NULL）的 stop_out_level。 */
    String selectPlatformStopOutLevel();

    /** IT-013：读 t_risk_config 平台行（symbol IS NULL）的 margin_call_level。 */
    String selectPlatformMarginCallLevel();

    /** IT-013：改 t_risk_config 平台行阈值（验证改值生效）。 */
    int updatePlatformMarginThresholds(@Param("stopOutLevel") String stopOutLevel,
                                       @Param("marginCallLevel") String marginCallLevel);

    /** IT-013：读 t_fx_pause_behavior 某类目的 allow_open（V31 seed 抽查）。 */
    Integer selectFxPauseAllowOpen(@Param("category") int category);

    /** IT-012：构造一条「老仓位」—— 不写 mm_rate_at_open/tier_no_at_open（DB DEFAULT 0.005000/1 兜底），
     *  验证回填默认值口径（V32 老数据回填 0.005/1）。返回插入行数。 */
    int insertLegacyPositionWithoutTierColumns(@Param("id") long id,
                                               @Param("userId") long userId,
                                               @Param("symbol") String symbol,
                                               @Param("entryPrice") String entryPrice);

    /** IT-012：读某仓位 tier_no_at_open。 */
    Integer selectPositionTierNoAtOpenById(@Param("positionId") Long positionId);

    /** IT-012：删测试构造的仓位行。 */
    int deletePositionById(@Param("id") long id);

    int insertSingleLeverageTier(@Param("symbol") String symbol,
                                 @Param("groupCode") String groupCode,
                                 @Param("maxLeverage") int maxLeverage,
                                 @Param("mmRate") String mmRate);

    int deleteWithdrawOrder();

    Integer countWithdrawOrderByUserId(@Param("userId") Long userId);

    Integer selectWithdrawOrderStatusCodeById(@Param("id") Long id);

    String selectWithdrawOrderAmountById(@Param("id") Long id);

    String selectWithdrawOrderTargetAddressById(@Param("id") Long id);

    int insertSeedAccount(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("currency") String currency,
                          @Param("balance") BigDecimal balance,
                          @Param("frozen") BigDecimal frozen,
                          @Param("marginUsed") BigDecimal marginUsed);

    int updateWithdrawOrderStatus(@Param("id") Long id,
                                   @Param("status") Integer status);

    int updateWithdrawOrderCoolingUntil(@Param("id") Long id,
                                         @Param("coolingUntil") LocalDateTime coolingUntil);

    Integer countOutboxByEventTypeAndPartitionKey(@Param("eventType") String eventType,
                                                    @Param("partitionKey") String partitionKey);

    String selectLatestOutboxPayloadByEventType(@Param("eventType") String eventType);

    int deleteNotification();

    Integer countNotificationByUserId(@Param("userId") Long userId);

    /** STAGE-14C1 Task 9 IT：按用户 + 模板 type 统计通知数（验证 STOP_OUT_TRIGGERED 是否发出）。 */
    Integer countNotificationByUserIdAndType(@Param("userId") Long userId,
                                             @Param("type") String type);

    Integer countNotificationByRelated(@Param("relatedKey") String relatedKey,
                                        @Param("relatedId") Long relatedId);

    Integer selectNotificationLevelCodeByRelated(@Param("relatedKey") String relatedKey,
                                                  @Param("relatedId") Long relatedId);

    String selectNotificationTitleByRelated(@Param("relatedKey") String relatedKey,
                                             @Param("relatedId") Long relatedId);

    String selectNotificationBodyByRelated(@Param("relatedKey") String relatedKey,
                                            @Param("relatedId") Long relatedId);

    String selectNotificationTypeByRelated(@Param("relatedKey") String relatedKey,
                                            @Param("relatedId") Long relatedId);

    int deleteInbox();

    int deleteOutbox();

    int deleteHedgeLog();

    int deleteLiquidationLog();

    int deleteRiskExposure();

    int deleteRiskControlAction();

    int deleteTrade();

    int deletePosition();

    int deleteOrder();

    int deleteDeposit();

    int deleteLedger();

    int deleteAccount();

    int deleteAccountByUserIdAndCurrency(@Param("userId") Long userId,
                                         @Param("currency") String currency);

    Integer countAccountsByUserId(@Param("userId") Long userId);

    Integer countDepositsByUserId(@Param("userId") Long userId);

    Integer countDepositsWithWalletTxIdByUserId(@Param("userId") Long userId);

    Integer countDepositsByWalletTxId(@Param("walletTxId") Long walletTxId);

    Integer selectDepositStatusCodeByWalletTxId(@Param("walletTxId") Long walletTxId);

    Integer countLedgerByUserId(@Param("userId") Long userId);

    Integer countLedgerByUserIdAndBizType(@Param("userId") Long userId,
                                          @Param("bizTypeCode") Integer bizTypeCode);

    String selectAccountBalanceByUserId(@Param("userId") Long userId);

    String selectAccountFrozenByUserId(@Param("userId") Long userId);

    String selectAccountMarginUsedByUserId(@Param("userId") Long userId);

    Integer countOrdersByUserId(@Param("userId") Long userId);

    Integer countOpenPositionsByUserId(@Param("userId") Long userId);

    Long selectLatestPositionIdByUserId(@Param("userId") Long userId);

    int updatePositionOpenedAt(@Param("positionId") Long positionId,
                               @Param("openedAt") LocalDateTime openedAt);

    Integer selectPositionStatusCodeById(@Param("positionId") Long positionId);

    String selectPositionClosePriceById(@Param("positionId") Long positionId);

    String selectPositionTakeProfitPriceById(@Param("positionId") Long positionId);

    String selectPositionStopLossPriceById(@Param("positionId") Long positionId);

    String selectPositionLiquidationPriceById(@Param("positionId") Long positionId);

    String selectPositionMarginById(@Param("positionId") Long positionId);

    /** STAGE-14C1 Task 9 IT：读开仓冻结 mm_rate_at_open，供 MarginLevel StopOut 用例计算阈值价。 */
    String selectPositionMmRateAtOpenById(@Param("positionId") Long positionId);

    /** STAGE-14C1 Task 9 IT：读开仓 entry_price，供 MarginLevel StopOut 用例计算阈值价。 */
    String selectPositionEntryPriceById(@Param("positionId") Long positionId);

    Integer selectPositionMarginModeCodeById(@Param("positionId") Long positionId);

    Integer selectPositionCloseReasonCodeById(@Param("positionId") Long positionId);

    String selectPositionRealizedPnlById(@Param("positionId") Long positionId);

    String selectPositionClosedAtById(@Param("positionId") Long positionId);

    Integer countPositionColumnsByName(@Param("columnName") String columnName);

    Integer countTradesByUserId(@Param("userId") Long userId);

    Integer countTradesByPositionId(@Param("positionId") Long positionId);

    Integer countTradesByPositionIdAndTradeType(@Param("positionId") Long positionId,
                                                @Param("tradeTypeCode") Integer tradeTypeCode);

    String selectTradePriceByPositionIdAndTradeType(@Param("positionId") Long positionId,
                                                    @Param("tradeTypeCode") Integer tradeTypeCode);

    String selectTradeRealizedPnlByPositionIdAndTradeType(@Param("positionId") Long positionId,
                                                          @Param("tradeTypeCode") Integer tradeTypeCode);

    String selectTradeFeeByPositionIdAndTradeType(@Param("positionId") Long positionId,
                                                  @Param("tradeTypeCode") Integer tradeTypeCode);

    Integer countOutbox();

    Integer countOutboxByEventType(@Param("eventType") String eventType);

    Integer countInboxByEventId(@Param("eventId") String eventId);

    Integer countInboxByEventType(@Param("eventType") String eventType);

    Integer countRiskExposureBySymbol(@Param("symbol") String symbol);

    String selectRiskExposureNetBySymbol(@Param("symbol") String symbol);

    String selectRiskExposureNetUsdBySymbol(@Param("symbol") String symbol);

    String selectRiskExposureTotalLongQtyBySymbol(@Param("symbol") String symbol);

    String selectRiskExposureTotalShortQtyBySymbol(@Param("symbol") String symbol);

    String selectLatestLedgerAmountByUserIdAndBizType(@Param("userId") Long userId,
                                                      @Param("bizTypeCode") Integer bizTypeCode);

    String selectLatestLedgerBalanceSnapshotByUserIdAndBizType(@Param("userId") Long userId,
                                                               @Param("bizTypeCode") Integer bizTypeCode);

    String selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(@Param("userId") Long userId,
                                                                  @Param("bizTypeCode") Integer bizTypeCode);

    Integer countLiquidationLogsByPositionId(@Param("positionId") Long positionId);

    String selectLiquidationLogPriceByPositionId(@Param("positionId") Long positionId);

    String selectLiquidationLogMarginModeByPositionId(@Param("positionId") Long positionId);

    String selectLiquidationLogPlatformCoveredLossByPositionId(@Param("positionId") Long positionId);

    String selectLiquidationLogMarginReleasedByPositionId(@Param("positionId") Long positionId);

    Integer countHedgeLogsBySymbol(@Param("symbol") String symbol);

    Integer selectLatestHedgeLogActionStatusCodeBySymbol(@Param("symbol") String symbol);

    Integer selectLatestHedgeLogTriggerSourceCodeBySymbol(@Param("symbol") String symbol);

    String selectLatestHedgeLogNetExposureUsdBySymbol(@Param("symbol") String symbol);

    String selectLatestHedgeLogThresholdUsdBySymbol(@Param("symbol") String symbol);

    String selectLatestHedgeLogMarkPriceBySymbol(@Param("symbol") String symbol);

    int deleteRiskExposureBySymbol(@Param("symbol") String symbol);

    int updateRiskExposureQuantities(@Param("symbol") String symbol,
                                     @Param("totalLongQty") BigDecimal totalLongQty,
                                     @Param("totalShortQty") BigDecimal totalShortQty);

    int updateRiskConfigHedgeThresholdUsd(@Param("symbol") String symbol,
                                          @Param("hedgeThresholdUsd") BigDecimal hedgeThresholdUsd);

    int updateRiskConfigPositionLimits(@Param("symbol") String symbol,
                                       @Param("maxPositionPerUser") BigDecimal maxPositionPerUser,
                                       @Param("maxPositionTotal") BigDecimal maxPositionTotal);

    int resetRiskConfigDefaults();

    Integer countActiveRiskControlActionsBySymbol(@Param("symbol") String symbol);

    Integer countRiskControlActionsBySymbol(@Param("symbol") String symbol);

    // ===== STAGE-14B Task 10：多币种落账三列 + entry_fx_rate 断言 / 老数据回填构造（测试专用） =====

    /** 校验 t_ledger 列是否存在（V28 迁移后 original_amount/original_currency/fx_rate_at_settlement）。 */
    Integer countLedgerColumnsByName(@Param("columnName") String columnName);

    /** 校验 t_position 列是否存在（V29 迁移后 entry_fx_rate）。 */
    Integer countPositionColumnsByName2(@Param("columnName") String columnName);

    /** 读 t_position.entry_fx_rate（开仓 fx 快照留痕）。 */
    String selectPositionEntryFxRateById(@Param("positionId") Long positionId);

    /** 读某用户某 biz_type 最新一条 ledger 的 original_amount（原币金额）。 */
    String selectLatestLedgerOriginalAmountByUserIdAndBizType(@Param("userId") Long userId,
                                                              @Param("bizTypeCode") Integer bizTypeCode);

    /** 读某用户某 biz_type 最新一条 ledger 的 original_currency（原币种）。 */
    String selectLatestLedgerOriginalCurrencyByUserIdAndBizType(@Param("userId") Long userId,
                                                                @Param("bizTypeCode") Integer bizTypeCode);

    /** 读某用户某 biz_type 最新一条 ledger 的 fx_rate_at_settlement（结算 FX）。 */
    String selectLatestLedgerFxRateByUserIdAndBizType(@Param("userId") Long userId,
                                                      @Param("bizTypeCode") Integer bizTypeCode);

    /** 老数据回填验证：直接构造一条不含三列真值的历史 ledger 行（模拟 V27 风格），三列置 NULL 后由迁移回填。 */
    int insertLegacyLedgerRowWithNullCurrencyColumns(@Param("id") Long id,
                                                     @Param("accountId") Long accountId,
                                                     @Param("userId") Long userId,
                                                     @Param("bizTypeCode") Integer bizTypeCode,
                                                     @Param("amount") BigDecimal amount);

    /** 老数据回填验证：读指定 ledger 行的 original_amount。 */
    String selectLedgerOriginalAmountById(@Param("id") Long id);

    /** 老数据回填验证：读指定 ledger 行的 original_currency。 */
    String selectLedgerOriginalCurrencyById(@Param("id") Long id);

    /** 老数据回填验证：读指定 ledger 行的 fx_rate_at_settlement。 */
    String selectLedgerFxRateById(@Param("id") Long id);

    /** 删除测试构造的指定 ledger 行（按 id）。 */
    int deleteLedgerById(@Param("id") Long id);
}
