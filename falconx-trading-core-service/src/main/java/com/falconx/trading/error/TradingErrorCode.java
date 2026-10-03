package com.falconx.trading.error;

import com.falconx.common.error.ErrorCode;

/**
 * trading-core-service 业务错误码。
 *
 * <p>本轮只补齐手动平仓最小链路直接使用到的错误码，
 * 其余交易业务码继续按既有 controller 返回语义保留。
 */
public enum TradingErrorCode implements ErrorCode {
    POSITION_LIMIT_REACHED("30005", "Position Limit Reached"),
    PLATFORM_POSITION_LIMIT_REACHED("30006", "Platform Position Limit Reached"),
    BBOOK_RISK_OPEN_REJECTED("30007", "Open Position Rejected by BBook Risk Control"),
    BBOOK_RISK_REDUCE_ONLY("30008", "Symbol in Reduce-Only Mode"),
    BBOOK_RISK_GLOBAL_PAUSE("30009", "Global Trading Paused by Risk Control"),
    /** BBOOK-RISK-CONTROL-01：用户级敞口超阈拒单。 */
    BBOOK_RISK_USER_EXPOSURE_LIMIT("30010", "User Exposure Limit Exceeded"),
    /** STAGE-3-PENDING-ORDER：挂单价与 markPrice 距离过近。 */
    PENDING_ORDER_TOO_CLOSE("30013", "Pending Order Too Close To Market"),
    /** STAGE-3-PENDING-ORDER：撤改时挂单 ID 不存在。 */
    PENDING_ORDER_NOT_FOUND("30014", "Pending Order Not Found"),
    /** STAGE-3-PENDING-ORDER：撤改时挂单已非 PENDING 状态。 */
    PENDING_ORDER_INVALID_STATE("30015", "Pending Order Not In PENDING State"),
    /** STAGE-3-PENDING-ORDER：创建挂单时 available 不足以冻结保证金。 */
    PENDING_ORDER_INSUFFICIENT_FUNDS("30016", "Insufficient Available For Pending Order"),
    /** PROD 精度统一：数量小数位超过 symbol qtyPrecision（市价/挂单同 reason 字符串 QTY_PRECISION_EXCEEDED）。 */
    QTY_PRECISION_EXCEEDED("30017", "Quantity Precision Exceeded"),
    /** PROD 精度统一：用户给的价格字段小数位超过 symbol pricePrecision。 */
    PRICE_PRECISION_EXCEEDED("30018", "Price Precision Exceeded"),
    /** STAGE-4-PRICE-ALERT：撤销/查询时告警 ID 不存在或非本人。 */
    PRICE_ALERT_NOT_FOUND("30020", "Price Alert Not Found"),
    /** STAGE-4-PRICE-ALERT：用户 ACTIVE 告警数已达上限（10）。 */
    PRICE_ALERT_LIMIT_EXCEEDED("30021", "Price Alert Limit Exceeded"),
    /** STAGE-4-PRICE-ALERT：direction 与 targetPrice/markPrice 关系矛盾或相等。 */
    PRICE_ALERT_INVALID_DIRECTION("30022", "Invalid Price Alert Direction"),
    /** STAGE-7-WITHDRAW：金额 ≤ 0 或精度超过 8 位。 */
    WITHDRAW_AMOUNT_INVALID("30040", "Withdraw Amount Invalid"),
    /** STAGE-7-WITHDRAW：单笔超 $10K。 */
    WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT("30041", "Withdraw Amount Exceeds Single Limit"),
    /** STAGE-7-WITHDRAW：当日累计超 $30K。 */
    WITHDRAW_AMOUNT_EXCEEDS_DAILY_LIMIT("30042", "Withdraw Amount Exceeds Daily Limit"),
    /** STAGE-7-WITHDRAW：identity kyc_level &lt; 1。 */
    WITHDRAW_KYC_REQUIRED("30043", "Withdraw KYC Required"),
    /** STAGE-7-WITHDRAW：network 非 ERC20/TRC20。 */
    WITHDRAW_NETWORK_UNSUPPORTED("30044", "Withdraw Network Unsupported"),
    /** STAGE-7-WITHDRAW：地址格式非法（EIP-55 / TRC20 base58）。 */
    WITHDRAW_ADDRESS_INVALID("30045", "Withdraw Address Invalid"),
    /** STAGE-7-WITHDRAW：地址未匹配白名单。 */
    WITHDRAW_ADDRESS_NOT_WHITELISTED("30046", "Withdraw Address Not Whitelisted"),
    /** STAGE-7-WITHDRAW：出金单不存在或非己。 */
    WITHDRAW_NOT_FOUND("30047", "Withdraw Not Found"),
    /** STAGE-7-WITHDRAW：非 COOLING 状态不可取消。 */
    WITHDRAW_NOT_CANCELABLE("30048", "Withdraw Not Cancelable"),
    /** STAGE-7-WITHDRAW：admin 审核时非 PENDING 状态。 */
    WITHDRAW_NOT_PENDING("30049", "Withdraw Not Pending"),
    /** STAGE-7-WITHDRAW：非 APPROVED_DELAYED 状态不可紧急取消。 */
    WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED("30050", "Withdraw Emergency Cancel Not Allowed"),
    /** STAGE-7-WITHDRAW：白名单超 10 条 ACTIVE。 */
    WITHDRAW_WHITELIST_LIMIT_EXCEEDED("30051", "Withdraw Whitelist Limit Exceeded"),
    /** STAGE-7-WITHDRAW：白名单重复添加。 */
    WITHDRAW_WHITELIST_DUPLICATE("30052", "Withdraw Whitelist Duplicate"),
    /** STAGE-7-WITHDRAW：白名单不存在或非己。 */
    WITHDRAW_WHITELIST_NOT_FOUND("30053", "Withdraw Whitelist Not Found"),
    /** STAGE-7-WITHDRAW：可用余额不足。 */
    WITHDRAW_BALANCE_INSUFFICIENT("30054", "Withdraw Balance Insufficient"),
    /** STAGE-7-WITHDRAW：白名单 24h 冷静期未过。 */
    WITHDRAW_WHITELIST_COOLING_NOT_PASSED("30055", "Withdraw Whitelist Cooling Not Passed"),
    /** STAGE-6-KYC trigger 2：出金地址 ∉ 该用户历史入金 from_address 集合（陌生地址 → 强制 KYC）。 */
    WITHDRAW_UNFAMILIAR_ADDRESS("30056", "Withdraw Address Not In Deposit History"),
    /** 2026-05-20 加固：idempotencyKey 在 24h TTL 之外被重用（客户端 bug），后端拒绝。 */
    WITHDRAW_IDEMPOTENCY_KEY_EXPIRED("30057", "Withdraw Idempotency Key Expired or Reused"),
    /** STAGE-8-NOTIFICATION：模板 code 不存在 / 已 disabled / 已软删除。 */
    NOTIFICATION_TEMPLATE_NOT_FOUND("30060", "Notification Template Not Found"),
    /** STAGE-8-NOTIFICATION：admin 新建模板时 code 重复（DB 唯一约束触发）。 */
    NOTIFICATION_TEMPLATE_CODE_DUPLICATE("30061", "Notification Template Code Duplicate"),
    /** STAGE-8-NOTIFICATION：内置模板（PRICE_ALERT_ / POSITION_ / KYC_ / WITHDRAW_ / DEPOSIT_ / RISK_）不可删除。 */
    NOTIFICATION_TEMPLATE_IN_USE("30062", "Notification Template In Use"),
    /** STAGE-8-NOTIFICATION：手动发送的目标 userId 不存在。 */
    NOTIFICATION_USER_NOT_FOUND("30063", "Notification User Not Found"),
    /** STAGE-8-NOTIFICATION：按 id 查询通知不存在（admin 详情）。 */
    NOTIFICATION_NOT_FOUND("30064", "Notification Not Found"),
    /** STAGE-8-NOTIFICATION：模板 enabled=0 状态下不可作为发送目标（视为不存在）。 */
    NOTIFICATION_TEMPLATE_DISABLED("30065", "Notification Template Disabled"),
    INSUFFICIENT_MARGIN("40001", "Insufficient Margin"),
    QUOTE_NOT_AVAILABLE("30003", "Quote Not Available"),
    PRICE_SOURCE_STALE_OR_DISCONNECTED("30002", "Price Source Stale Or Disconnected"),
    POSITION_NOT_FOUND("40004", "Position Not Found"),
    POSITION_ALREADY_CLOSED("40007", "Position Already Closed"),
    SYMBOL_TRADING_SUSPENDED("40008", "Symbol Trading Suspended"),
    MARGIN_MODE_NOT_SUPPORTED("40010", "Margin Mode Not Supported"),
    /** STAGE-2-CUSTOMER 内部 RPC：客户账户不存在。 */
    ACCOUNT_NOT_FOUND("30030", "Trading Account Not Found"),
    /** STAGE-2-CUSTOMER 内部 RPC：调余额扣减后余额为负。 */
    BALANCE_INSUFFICIENT("30031", "Trading Balance Insufficient"),
    /** STAGE-2-TRADING-MONITOR：管理端手动强平 positionId 不存在。 */
    ADMIN_TRADING_POSITION_NOT_FOUND("90650", "Admin Trading Position Not Found"),
    /** STAGE-2-TRADING-MONITOR：管理端手动强平时 position 已平仓。 */
    ADMIN_TRADING_POSITION_ALREADY_CLOSED("90651", "Admin Trading Position Already Closed"),
    /** STAGE-2-TRADING-MONITOR：管理端手动强平时 FOR UPDATE 锁超时。 */
    ADMIN_TRADING_POSITION_LOCK_TIMEOUT("90652", "Admin Trading Position Lock Timeout"),
    /** STAGE-2-TRADING-MONITOR：管理端手动强平失败（内部错误）。 */
    ADMIN_TRADING_MANUAL_LIQUIDATE_FAILED("90653", "Admin Trading Manual Liquidate Failed"),
    /** STAGE-2-TRADING-MONITOR：风控开关 key 不在白名单。 */
    ADMIN_TRADING_RISK_SWITCH_KEY_INVALID("90654", "Admin Trading Risk Switch Key Invalid"),
    /** STAGE-2-TRADING-MONITOR：风控开关新值与当前值一致。 */
    ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED("90655", "Admin Trading Risk Switch Value Unchanged"),
    /** STAGE-2-RISK-ADMIN：同 symbol+actionType+source 已激活。 */
    ADMIN_RISK_ACTION_ALREADY_ACTIVE("90800", "Admin Risk Action Already Active"),
    /** STAGE-2-RISK-ADMIN：停用时 id 不存在。 */
    ADMIN_RISK_ACTION_NOT_FOUND("90801", "Admin Risk Action Not Found"),
    /** STAGE-2-RISK-ADMIN：试图停用非 MANUAL_ADMIN 触发的激活记录。 */
    ADMIN_RISK_ACTION_NOT_DEACTIVATABLE("90802", "Admin Risk Action Not Deactivatable"),
    /** STAGE-2-RISK-ADMIN：risk_config 按 symbol 查不到。 */
    ADMIN_RISK_CONFIG_NOT_FOUND("90803", "Admin Risk Config Not Found"),
    /** STAGE-2-RISK-ADMIN：POST 新建时 symbol 已存在。 */
    ADMIN_RISK_CONFIG_DUPLICATE_SYMBOL("90804", "Admin Risk Config Duplicate Symbol"),
    /** STAGE-2-RISK-ADMIN：maxLeverage 不在 [1, 500]。 */
    ADMIN_RISK_CONFIG_INVALID_LEVERAGE("90805", "Admin Risk Config Invalid Leverage"),
    /** STAGE-2-RISK-ADMIN：maxPositionPerUser / maxPositionTotal 非法。 */
    ADMIN_RISK_CONFIG_INVALID_POSITION_LIMIT("90806", "Admin Risk Config Invalid Position Limit"),
    /** STAGE-2-RISK-ADMIN：hedgeThresholdUsd 为负。 */
    ADMIN_RISK_CONFIG_INVALID_HEDGE_THRESHOLD("90807", "Admin Risk Config Invalid Hedge Threshold"),
    /** STAGE-2-RISK-ADMIN：risk_market_config marketCode 不存在。 */
    ADMIN_RISK_MARKET_CONFIG_NOT_FOUND("90808", "Admin Risk Market Config Not Found"),
    /** STAGE-2-RISK-ADMIN：concentration_threshold_usd 为负。 */
    ADMIN_RISK_MARKET_CONFIG_INVALID_THRESHOLD("90809", "Admin Risk Market Config Invalid Threshold"),

    // STAGE-14C2 全局/类目暂停（master §7.3 trading-core 3xxxx 段）
    /**
     * STAGE-14C2-FX-PAUSE：GLOBAL_PAUSE/FX_PAUSED 活跃且对应类目 allow_open=0 时拒绝开仓
     * （Task 6 接线）。master §7.3 明确归 trading-core 3xxxx 段（标 B/C）。
     */
    GLOBAL_PAUSE_ACTIVE("30087", "Global Pause Active"),

    // STAGE-14C2 杠杆档位（tier）CRUD 校验（master §7.3 把 90930-90932 列在 console 9xxxx 段，
    // 但 tier CRUD 的实际写校验逻辑落在 trading-core（console 经 internal RPC 透传），
    // 故校验错误在 trading-core 抛出。这里复用 master 指定的 90930-90932 号值（不另起 3xxxx），
    // 使 trading 抛出码与 console 翻译码一致，console 侧仅做可读消息翻译展示。逻辑 Task 4 接线。
    /** STAGE-14C2-TIER：编辑/删除时 tier id 不存在。 */
    ADMIN_TIER_NOT_FOUND("90930", "Admin Tier Not Found"),
    /** STAGE-14C2-TIER：新建/编辑 tier 参数校验失败（max_lev×mm_rate>1.0 / tier_no 重复等）。 */
    ADMIN_TIER_VALIDATION_FAILED("90931", "Admin Tier Validation Failed"),
    /** STAGE-14C2-TIER：新建/编辑 tier 的 notional 区间与同 symbol+group 现有档位重叠。 */
    ADMIN_TIER_OVERLAP("90932", "Admin Tier Overlap"),

    // STAGE-14D1 用户级 margin mode 切换闸门 + 冷静期 + supplement 收口（master §7.3 trading-core 3xxxx 段）。
    // 30084 master 标通知类（挂单自动撤销）不在本切片，30087 已存在（GLOBAL_PAUSE_ACTIVE）不动。
    /** STAGE-14D1：存在 OPEN 持仓，不可切换 margin mode。 */
    MODE_HAS_OPEN_POSITIONS("30080", "Margin Mode Has Open Positions"),
    /** STAGE-14D1：存在 ACTIVE 开仓挂单，不可切换 margin mode。 */
    MODE_HAS_ACTIVE_PENDING("30081", "Margin Mode Has Active Pending Order"),
    /** STAGE-14D1：mode 切换冷静期未结束，不可再次切换。 */
    MODE_COOLING_PERIOD_ACTIVE("30082", "Margin Mode Cooling Period Active"),
    /** STAGE-14D1：目标 margin mode 与当前相同，无需切换。 */
    MODE_NO_CHANGE("30083", "Margin Mode No Change"),
    /** STAGE-14D1：持仓非 ISOLATED，不可追加保证金。 */
    POSITION_NOT_ISOLATED("30085", "Position Not Isolated"),
    /** STAGE-14D1：追加保证金金额非法（≤0 或精度超限）。 */
    SUPPLEMENT_AMOUNT_INVALID("30086", "Supplement Amount Invalid"),
    /** STAGE-14D1：CROSS 模式未启用（D2 账户级强平就位前 gate），不可切换至 CROSS。 */
    CROSS_MODE_NOT_ENABLED("30088", "Cross Mode Not Enabled");

    private final String code;
    private final String message;

    TradingErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
