package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.GroupVisibilityGroupedRecord;
import com.falconx.market.repository.mapper.record.MarketSwapRateRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolGroupVisibilityAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionExceptionRecord;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-2-SYMBOL: market admin 路径独立 mapper（不与 C 端 Symbol/SwapRate 路径冲突）。
 *
 * <p>所有 admin 方法通过 internal RPC 路径 {@code /internal/v1/market/symbols/**} 触发，
 * filter 校验 X-Internal-Token 后才到达。
 */
@Mapper
public interface MarketSymbolAdminMapper {

    /** 列表分页（含 SUSPENDED）。 */
    List<MarketSymbolAdminRecord> selectSymbolsForList(@Param("category") Integer category,
                                                        @Param("marketCode") String marketCode,
                                                        @Param("status") Integer status,
                                                        @Param("lpCode") String lpCode,
                                                        @Param("symbolLike") String symbolLike,
                                                        @Param("offset") int offset,
                                                        @Param("limit") int limit);

    long countSymbols(@Param("category") Integer category,
                      @Param("marketCode") String marketCode,
                      @Param("status") Integer status,
                      @Param("lpCode") String lpCode,
                      @Param("symbolLike") String symbolLike);

    /** 单查（按 ID）。 */
    MarketSymbolAdminRecord selectSymbolById(@Param("id") long id);

    /** 单查（按 symbol）。 */
    MarketSymbolAdminRecord selectSymbolBySymbol(@Param("symbol") String symbol);

    /** 单查（按 LP code + symbol）。 */
    MarketSymbolAdminRecord selectSymbolByLpCodeAndSymbol(@Param("lpCode") String lpCode,
                                                          @Param("symbol") String symbol);

    /**
     * 编辑：source 元数据（category / market_code / precision）。
     *
     * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后字段集裁剪：原 max_leverage / taker_fee_rate /
     * spread / min_qty / max_qty / min_notional 已下沉到 t_symbol_quote_mapping，
     * 编辑这些参数请走 mapping 接口（{@code updateQuoteMapping}）。
     */
    int updateSymbolConfig(@Param("id") long id,
                            @Param("category") Integer category,
                            @Param("marketCode") String marketCode,
                            @Param("pricePrecision") Integer pricePrecision,
                            @Param("qtyPrecision") Integer qtyPrecision);

    /** 暂停 / 恢复（status 1=TRADING / 2=SUSPENDED）。 */
    int updateSymbolStatus(@Param("id") long id, @Param("status") int status);

    /**
     * 新建 LP 源 symbol（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B 新增）。
     *
     * <p>仅允许 status=1 创建；(lp_code, symbol) 唯一约束在 DB 层。
     */
    int insertSymbol(@Param("id") long id,
                     @Param("lpCode") String lpCode,
                     @Param("symbol") String symbol,
                     @Param("category") Integer category,
                     @Param("marketCode") String marketCode,
                     @Param("baseCurrency") String baseCurrency,
                     @Param("quoteCurrency") String quoteCurrency,
                     @Param("pricePrecision") Integer pricePrecision,
                     @Param("qtyPrecision") Integer qtyPrecision);

    /** 当前生效隔夜费率（按 effective_from 倒序，effective_from ≤ today，取第 1 行）。 */
    MarketSwapRateRecord selectCurrentSwapRate(@Param("symbol") String symbol,
                                                 @Param("today") LocalDate today);

    /** 历史隔夜费率（最近 N 条，effective_from DESC）。 */
    List<MarketSwapRateRecord> selectRecentSwapRates(@Param("symbol") String symbol, @Param("limit") int limit);

    /** 检查 (symbol, effective_from) 是否已存在。 */
    long countSwapRateBySymbolAndDate(@Param("symbol") String symbol,
                                       @Param("effectiveFrom") LocalDate effectiveFrom);

    /** 写入新一行 swap rate。 */
    int insertSwapRate(@Param("id") long id,
                        @Param("symbol") String symbol,
                        @Param("longRate") BigDecimal longRate,
                        @Param("shortRate") BigDecimal shortRate,
                        @Param("rolloverTime") LocalTime rolloverTime,
                        @Param("effectiveFrom") LocalDate effectiveFrom);

    /** 交易时段（按 day_of_week ASC, session_no ASC）。 */
    List<MarketTradingSessionRecord> selectTradingSessions(@Param("symbol") String symbol);

    MarketTradingSessionRecord selectTradingSessionByIdForSymbol(@Param("symbol") String symbol,
                                                                  @Param("id") long id);

    long countTradingSessionConflict(@Param("symbol") String symbol,
                                     @Param("dayOfWeek") Integer dayOfWeek,
                                     @Param("sessionNo") Integer sessionNo,
                                     @Param("effectiveFrom") LocalDate effectiveFrom,
                                     @Param("excludeId") Long excludeId);

    int insertTradingSession(@Param("id") long id,
                             @Param("symbol") String symbol,
                             @Param("dayOfWeek") Integer dayOfWeek,
                             @Param("sessionNo") Integer sessionNo,
                             @Param("openTime") LocalTime openTime,
                             @Param("closeTime") LocalTime closeTime,
                             @Param("timezone") String timezone,
                             @Param("enabled") Integer enabled,
                             @Param("effectiveFrom") LocalDate effectiveFrom,
                             @Param("effectiveTo") LocalDate effectiveTo);

    int updateTradingSession(@Param("id") long id,
                             @Param("symbol") String symbol,
                             @Param("dayOfWeek") Integer dayOfWeek,
                             @Param("sessionNo") Integer sessionNo,
                             @Param("openTime") LocalTime openTime,
                             @Param("closeTime") LocalTime closeTime,
                             @Param("timezone") String timezone,
                             @Param("enabled") Integer enabled,
                             @Param("effectiveFrom") LocalDate effectiveFrom,
                             @Param("effectiveTo") LocalDate effectiveTo);

    int deleteTradingSession(@Param("symbol") String symbol, @Param("id") long id);

    /** 交易时段例外（特定日期）。 */
    List<MarketTradingSessionExceptionRecord> selectTradingExceptions(@Param("symbol") String symbol);

    MarketTradingSessionExceptionRecord selectTradingExceptionByIdForSymbol(@Param("symbol") String symbol,
                                                                             @Param("id") long id);

    long countTradingExceptionConflict(@Param("symbol") String symbol,
                                       @Param("tradeDate") LocalDate tradeDate,
                                       @Param("sessionNo") Integer sessionNo,
                                       @Param("excludeId") Long excludeId);

    int insertTradingException(@Param("id") long id,
                               @Param("symbol") String symbol,
                               @Param("tradeDate") LocalDate tradeDate,
                               @Param("exceptionType") Integer exceptionType,
                               @Param("sessionNo") Integer sessionNo,
                               @Param("openTime") LocalTime openTime,
                               @Param("closeTime") LocalTime closeTime,
                               @Param("timezone") String timezone,
                               @Param("reason") String reason);

    int updateTradingException(@Param("id") long id,
                               @Param("symbol") String symbol,
                               @Param("tradeDate") LocalDate tradeDate,
                               @Param("exceptionType") Integer exceptionType,
                               @Param("sessionNo") Integer sessionNo,
                               @Param("openTime") LocalTime openTime,
                               @Param("closeTime") LocalTime closeTime,
                               @Param("timezone") String timezone,
                               @Param("reason") String reason);

    int deleteTradingException(@Param("symbol") String symbol, @Param("id") long id);

    /** market 节假日（按 market_code）。 */
    List<MarketTradingHolidayRecord> selectMarketHolidays(@Param("marketCode") String marketCode);

    List<MarketTradingHolidayRecord> selectMarketHolidaysForList(@Param("marketCode") String marketCode,
                                                                 @Param("offset") int offset,
                                                                 @Param("limit") int limit);

    long countMarketHolidays(@Param("marketCode") String marketCode);

    MarketTradingHolidayRecord selectMarketHolidayById(@Param("id") long id);

    long countMarketHolidayConflict(@Param("marketCode") String marketCode,
                                    @Param("holidayDate") LocalDate holidayDate,
                                    @Param("excludeId") Long excludeId);

    int insertMarketHoliday(@Param("id") long id,
                            @Param("marketCode") String marketCode,
                            @Param("holidayDate") LocalDate holidayDate,
                            @Param("holidayType") Integer holidayType,
                            @Param("openTime") LocalTime openTime,
                            @Param("closeTime") LocalTime closeTime,
                            @Param("timezone") String timezone,
                            @Param("holidayName") String holidayName,
                            @Param("countryCode") String countryCode);

    int updateMarketHoliday(@Param("id") long id,
                            @Param("marketCode") String marketCode,
                            @Param("holidayDate") LocalDate holidayDate,
                            @Param("holidayType") Integer holidayType,
                            @Param("openTime") LocalTime openTime,
                            @Param("closeTime") LocalTime closeTime,
                            @Param("timezone") String timezone,
                            @Param("holidayName") String holidayName,
                            @Param("countryCode") String countryCode);

    int deleteMarketHoliday(@Param("id") long id);

    /** 报价映射分页。 */
    List<MarketSymbolQuoteMappingAdminRecord> selectQuoteMappingsForList(
            @Param("platformSymbolLike") String platformSymbolLike,
            @Param("sourceLpCode") String sourceLpCode,
            @Param("sourceSymbolLike") String sourceSymbolLike,
            @Param("enabled") Integer enabled,
            @Param("lpSubscribeEnabled") Integer lpSubscribeEnabled,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countQuoteMappings(@Param("platformSymbolLike") String platformSymbolLike,
                            @Param("sourceLpCode") String sourceLpCode,
                            @Param("sourceSymbolLike") String sourceSymbolLike,
                            @Param("enabled") Integer enabled,
                            @Param("lpSubscribeEnabled") Integer lpSubscribeEnabled);

    MarketSymbolQuoteMappingAdminRecord selectQuoteMappingByPlatformSymbol(
            @Param("platformSymbol") String platformSymbol);

    int insertQuoteMapping(@Param("platformSymbol") String platformSymbol,
                           @Param("sourceProvider") String sourceProvider,
                           @Param("sourceLpCode") String sourceLpCode,
                           @Param("sourceSymbol") String sourceSymbol,
                           @Param("category") Integer category,
                           @Param("marketCode") String marketCode,
                           @Param("priceMultiplier") BigDecimal priceMultiplier,
                           @Param("bidAdjustment") BigDecimal bidAdjustment,
                           @Param("askAdjustment") BigDecimal askAdjustment,
                           @Param("enabled") Integer enabled,
                           @Param("lpSubscribeEnabled") Integer lpSubscribeEnabled,
                           @Param("maxLeverage") Integer maxLeverage,
                           @Param("takerFeeRate") BigDecimal takerFeeRate,
                           @Param("spread") BigDecimal spread,
                           @Param("minQty") BigDecimal minQty,
                           @Param("maxQty") BigDecimal maxQty,
                           @Param("minNotional") BigDecimal minNotional,
                           @Param("pricePrecision") Integer pricePrecision,
                           @Param("qtyPrecision") Integer qtyPrecision);

    int updateQuoteMapping(@Param("platformSymbol") String platformSymbol,
                           @Param("sourceProvider") String sourceProvider,
                           @Param("sourceLpCode") String sourceLpCode,
                           @Param("sourceSymbol") String sourceSymbol,
                           @Param("category") Integer category,
                           @Param("marketCode") String marketCode,
                           @Param("priceMultiplier") BigDecimal priceMultiplier,
                           @Param("bidAdjustment") BigDecimal bidAdjustment,
                           @Param("askAdjustment") BigDecimal askAdjustment,
                           @Param("enabled") Integer enabled,
                           @Param("lpSubscribeEnabled") Integer lpSubscribeEnabled,
                           @Param("maxLeverage") Integer maxLeverage,
                           @Param("takerFeeRate") BigDecimal takerFeeRate,
                           @Param("spread") BigDecimal spread,
                           @Param("minQty") BigDecimal minQty,
                           @Param("maxQty") BigDecimal maxQty,
                           @Param("minNotional") BigDecimal minNotional,
                           @Param("pricePrecision") Integer pricePrecision,
                           @Param("qtyPrecision") Integer qtyPrecision);

    /** 用户组可见性分页。 */
    List<MarketSymbolGroupVisibilityAdminRecord> selectGroupVisibilityForList(
            @Param("groupCode") String groupCode,
            @Param("symbolLike") String symbolLike,
            @Param("visible") Integer visible,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countGroupVisibility(@Param("groupCode") String groupCode,
                              @Param("symbolLike") String symbolLike,
                              @Param("visible") Integer visible);

    MarketSymbolGroupVisibilityAdminRecord selectGroupVisibility(@Param("groupCode") String groupCode,
                                                                 @Param("symbol") String symbol);

    int upsertGroupVisibility(@Param("groupCode") String groupCode,
                              @Param("symbol") String symbol,
                              @Param("visible") Integer visible);

    /**
     * 调大当前 SESSION 的 group_concat_max_len 到 1MB，
     * 避免单组 5000+ symbol 被 GROUP_CONCAT 默认 1024 字节截断。
     * 必须在 {@link #selectGroupVisibilityGrouped} 之前同事务内调用。
     */
    void enlargeGroupConcatMaxLen();

    /**
     * 用户组可见性聚合视图（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B 新增）。
     *
     * <p>service 层先调 {@link #enlargeGroupConcatMaxLen()} 调大限制再查询；
     * 然后把 visibleSymbols 逗号串拆为 List 装配到 GroupVisibilityListResult。
     */
    List<GroupVisibilityGroupedRecord> selectGroupVisibilityGrouped(
            @Param("groupCodeLike") String groupCodeLike,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countGroupVisibilityGrouped(@Param("groupCodeLike") String groupCodeLike);
}
