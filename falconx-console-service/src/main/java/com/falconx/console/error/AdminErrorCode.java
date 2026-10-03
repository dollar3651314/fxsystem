package com.falconx.console.error;

import com.falconx.common.error.ErrorCode;

/**
 * 管理端业务错误码（独占 9xxxx 号段，与 C 端 1xxxx-5xxxx 完全隔离）。
 *
 * <p>子模块号段分配见 {@code docs/api/管理端接口规范.md} §1.5：
 * <ul>
 *   <li>{@code 90001-90099} 鉴权 / token / 会话</li>
 *   <li>{@code 90100-90199} 改密 / 密码策略</li>
 *   <li>{@code 90200-90299} RBAC / 权限点 / 角色 / 菜单</li>
 *   <li>{@code 90300+} 业务管理端（按阶段顺序分配）</li>
 * </ul>
 *
 * <p>本枚举当前仅含阶段 1 已冻结的 {@code 90001-90008}；后续阶段新增错误码必须先在
 * 接口规范登记再加入本枚举。
 */
public enum AdminErrorCode implements ErrorCode {

    /** 用户名或密码错误。*/
    ADMIN_AUTH_FAILED("90001", "Admin Auth Failed"),
    /** Access / Refresh Token 已过期。 */
    ADMIN_TOKEN_EXPIRED("90002", "Admin Token Expired"),
    /** Token 签名 / 类型 / issuer 不合法或已黑名单。 */
    ADMIN_TOKEN_INVALID("90003", "Admin Token Invalid"),
    /** 当前管理员对该接口无权限。 */
    ADMIN_PERMISSION_DENIED("90004", "Admin Permission Denied"),
    /** 请求 IP 不在白名单内。 */
    ADMIN_IP_NOT_WHITELISTED("90005", "Admin IP Not Whitelisted"),
    /** 登录失败次数过多，账号已锁定。 */
    ADMIN_LOGIN_LOCKED("90006", "Admin Login Locked"),
    /** 当前管理员被要求强制修改密码后才能继续操作。 */
    ADMIN_PASSWORD_MUST_CHANGE("90007", "Admin Password Must Change"),
    /** 管理员账号已被禁用。 */
    ADMIN_USER_DISABLED("90008", "Admin User Disabled"),

    // 阶段 1 P1 改密 / 密码策略段（90100-90199）

    /** 新密码不符合密码策略（≥ 12 字符 + 大小写 + 数字 + 特殊字符 + 不含 username）。 */
    ADMIN_PASSWORD_POLICY_VIOLATION("90100", "Admin Password Policy Violation"),

    // 阶段 1 P2-P5 RBAC 自管段（90200-90299，R2 二轮 B 冻结 2026-05-09）

    /** 管理员 username 重复（t_admin_user.username UNIQUE）。 */
    ADMIN_USER_USERNAME_DUPLICATE("90210", "Admin User Username Duplicate"),
    /** 管理员不存在。 */
    ADMIN_USER_NOT_FOUND("90211", "Admin User Not Found"),
    /** 管理员 username 格式不符（非 [a-zA-Z0-9_]+ 或长度 < 3 / > 32）。 */
    ADMIN_USER_USERNAME_INVALID_FORMAT("90212", "Admin User Username Invalid Format"),
    /** 新建管理员时不允许指派 SUPER_ADMIN 角色（id=1）。 */
    ADMIN_USER_SUPER_ADMIN_ROLE_NOT_ASSIGNABLE("90213", "Admin User Super Admin Role Not Assignable"),
    /** 编辑 SUPER_ADMIN 用户时禁止移除 SUPER_ADMIN 角色。 */
    ADMIN_USER_SUPER_ADMIN_ROLE_LOCKED("90214", "Admin User Super Admin Role Locked"),
    /** 禁止禁用自己。 */
    ADMIN_USER_CANNOT_DISABLE_SELF("90215", "Admin User Cannot Disable Self"),
    /** SUPER_ADMIN 角色用户不可删除。 */
    ADMIN_USER_SUPER_ADMIN_NOT_DELETABLE("90216", "Admin User Super Admin Not Deletable"),
    /** 禁止删除自己。 */
    ADMIN_USER_CANNOT_DELETE_SELF("90217", "Admin User Cannot Delete Self"),

    /** 角色 code 重复（t_admin_role.code UNIQUE）。 */
    ADMIN_ROLE_CODE_DUPLICATE("90220", "Admin Role Code Duplicate"),
    /** 角色不存在。 */
    ADMIN_ROLE_NOT_FOUND("90221", "Admin Role Not Found"),
    /** 系统内置角色（is_system=1）拒绝编辑 / 删除。 */
    ADMIN_ROLE_IS_SYSTEM_NOT_EDITABLE("90222", "Admin Role Is System Not Editable"),
    /** 角色仍有成员，不可删除。 */
    ADMIN_ROLE_HAS_MEMBERS_CANNOT_DELETE("90223", "Admin Role Has Members Cannot Delete"),
    /** SUPER_ADMIN 角色权限不可通过 API 修改。 */
    ADMIN_ROLE_SUPER_ADMIN_PERMISSIONS_LOCKED("90224", "Admin Role Super Admin Permissions Locked"),
    /** 传入的 permissionCode 不在权限点字典中。 */
    ADMIN_ROLE_PERMISSION_CODE_NOT_FOUND("90225", "Admin Role Permission Code Not Found"),

    /** 菜单 code 重复（t_admin_menu.code UNIQUE）。 */
    ADMIN_MENU_CODE_DUPLICATE("90230", "Admin Menu Code Duplicate"),
    /** 菜单不存在。 */
    ADMIN_MENU_NOT_FOUND("90231", "Admin Menu Not Found"),
    /** 菜单仍有子菜单，不可删除。 */
    ADMIN_MENU_HAS_CHILDREN_CANNOT_DELETE("90232", "Admin Menu Has Children Cannot Delete"),
    /** 菜单 permissionCode 不存在于权限点字典。 */
    ADMIN_MENU_PERMISSION_CODE_NOT_FOUND("90233", "Admin Menu Permission Code Not Found"),
    /** 菜单已是同级第一/最后，无法上/下移。 */
    ADMIN_MENU_SORT_BOUNDARY("90234", "Admin Menu Sort Boundary"),

    // STAGE-2-CUSTOMER 客户管理段（90300-90399）
    /** 单次调余额超 $5000 上限。 */
    ADMIN_BALANCE_ADJUST_SINGLE_LIMIT_EXCEEDED("90301", "Admin Balance Adjust Single Limit Exceeded"),
    /** 单日累计调余额超 $20000 上限。 */
    ADMIN_BALANCE_ADJUST_DAILY_LIMIT_EXCEEDED("90302", "Admin Balance Adjust Daily Limit Exceeded"),
    /** 客户不存在。 */
    ADMIN_CUSTOMER_NOT_FOUND("90303", "Admin Customer Not Found"),
    /** 操作原因 < 10 字符。 */
    ADMIN_REASON_TOO_SHORT("90304", "Admin Operation Reason Too Short"),
    /** 客户已 FROZEN，不可重复冻结。 */
    ADMIN_CUSTOMER_ALREADY_FROZEN("90305", "Admin Customer Already Frozen"),
    /** 客户处于终态 BANNED，不可冻结/解冻。 */
    ADMIN_CUSTOMER_TERMINAL_STATUS("90306", "Admin Customer In Terminal Status"),
    /** 扣减后余额为负，账户余额不足。 */
    ADMIN_BALANCE_INSUFFICIENT_FOR_NEGATIVE_ADJUST("90307", "Admin Balance Insufficient For Negative Adjust"),

    // STAGE-2-SYMBOL 行情品种段（90600-90649，R2 二轮 D 冻结 2026-05-09）

    /** symbol 不存在。 */
    ADMIN_SYMBOL_NOT_FOUND("90600", "Admin Symbol Not Found"),
    /** maxLeverage 不在 [1, 500] 范围。 */
    ADMIN_SYMBOL_INVALID_LEVERAGE("90601", "Admin Symbol Invalid Leverage"),
    /** takerFeeRate 不在 [0, 5%] 范围。 */
    ADMIN_SYMBOL_INVALID_FEE_RATE("90602", "Admin Symbol Invalid Fee Rate"),
    /** spread 为负数。 */
    ADMIN_SYMBOL_INVALID_SPREAD("90603", "Admin Symbol Invalid Spread"),
    /** minQty >= maxQty 数量上下限非法。 */
    ADMIN_SYMBOL_INVALID_QTY_RANGE("90604", "Admin Symbol Invalid Qty Range"),
    /** symbol 已 SUSPENDED，不可重复暂停。 */
    ADMIN_SYMBOL_ALREADY_SUSPENDED("90605", "Admin Symbol Already Suspended"),
    /** symbol 已 TRADING，不可重复恢复。 */
    ADMIN_SYMBOL_ALREADY_TRADING("90606", "Admin Symbol Already Trading"),
    /** (symbol, effective_from) 已存在 swap-rate 记录。 */
    ADMIN_SYMBOL_SWAP_RATE_OVERLAP_DATE("90610", "Admin Symbol Swap Rate Overlap Date"),
    /** swap-rate effective_from 在过去。 */
    ADMIN_SYMBOL_SWAP_RATE_DATE_INVALID("90611", "Admin Symbol Swap Rate Date Invalid"),
    /** longRate / shortRate 绝对值超 1% 上限。 */
    ADMIN_SYMBOL_SWAP_RATE_OUT_OF_RANGE("90612", "Admin Symbol Swap Rate Out Of Range"),
    /** t_symbol_quote_mapping.platform_symbol 不存在。 */
    ADMIN_SYMBOL_MAPPING_NOT_FOUND("90613", "Admin Symbol Mapping Not Found"),
    /** t_symbol_quote_mapping.platform_symbol 已存在。 */
    ADMIN_SYMBOL_MAPPING_DUPLICATE("90614", "Admin Symbol Mapping Duplicate"),
    /** t_symbol_quote_mapping.source_symbol 不存在于 t_symbol。 */
    ADMIN_SYMBOL_MAPPING_SOURCE_NOT_FOUND("90615", "Admin Symbol Mapping Source Not Found"),
    /** 报价映射价格规则非法。 */
    ADMIN_SYMBOL_MAPPING_INVALID_PRICE_RULE("90616", "Admin Symbol Mapping Invalid Price Rule"),
    /** 用户组可见性参数非法。 */
    ADMIN_SYMBOL_GROUP_VISIBILITY_INVALID("90618", "Admin Symbol Group Visibility Invalid"),
    /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：新建 LP 源 symbol 重复。 */
    ADMIN_SYMBOL_SOURCE_DUPLICATE("90619", "Admin Symbol Source Duplicate"),
    /** STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：source 元数据范围非法（category/marketCode/precision/status）。 */
    ADMIN_SYMBOL_SOURCE_INVALID("90620", "Admin Symbol Source Invalid"),
    /** platform symbol 交易时段 / 特殊交易日参数非法或重复。 */
    ADMIN_SYMBOL_TRADING_SCHEDULE_INVALID("90621", "Admin Symbol Trading Schedule Invalid"),
    /** platform symbol 交易时段 / 特殊交易日记录不存在。 */
    ADMIN_SYMBOL_TRADING_SCHEDULE_NOT_FOUND("90622", "Admin Symbol Trading Schedule Not Found"),
    /** market holiday 参数非法或重复。 */
    ADMIN_SYMBOL_HOLIDAY_INVALID("90623", "Admin Symbol Holiday Invalid"),
    /** market holiday 记录不存在。 */
    ADMIN_SYMBOL_HOLIDAY_NOT_FOUND("90624", "Admin Symbol Holiday Not Found"),
    /** STAGE-12-GROUP-MARKUP：(groupCode, platformSymbol) 加点配置不存在。 */
    ADMIN_SYMBOL_GROUP_MARKUP_NOT_FOUND("90640", "Admin Symbol Group Markup Not Found"),
    /** STAGE-12-GROUP-MARKUP：bidExtra/askExtra 越界或参数非法（空 groupCode / bulk 空 / 越界等）。 */
    ADMIN_SYMBOL_GROUP_MARKUP_INVALID_RANGE("90641", "Admin Symbol Group Markup Invalid Range"),
    /** STAGE-12-GROUP-MARKUP：(groupCode, platformSymbol) 重复（POST 时 PK 冲突）。 */
    ADMIN_SYMBOL_GROUP_MARKUP_DUPLICATE("90642", "Admin Symbol Group Markup Duplicate"),

    // STAGE-2-TRADING-MONITOR 订单 / 持仓监控段（90650-90699，R2 一轮冻结 2026-05-12）
    /** 手动强平 positionId 不存在。 */
    ADMIN_TRADING_POSITION_NOT_FOUND("90650", "Admin Trading Position Not Found"),
    /** 手动强平时 position 已平仓。 */
    ADMIN_TRADING_POSITION_ALREADY_CLOSED("90651", "Admin Trading Position Already Closed"),
    /** 手动强平时 FOR UPDATE 锁超时。 */
    ADMIN_TRADING_POSITION_LOCK_TIMEOUT("90652", "Admin Trading Position Lock Timeout"),
    /** 手动强平失败（内部错误）。 */
    ADMIN_TRADING_MANUAL_LIQUIDATE_FAILED("90653", "Admin Trading Manual Liquidate Failed"),
    /** 风控开关 key 不在白名单。 */
    ADMIN_TRADING_RISK_SWITCH_KEY_INVALID("90654", "Admin Trading Risk Switch Key Invalid"),
    /** 风控开关新值与当前值一致。 */
    ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED("90655", "Admin Trading Risk Switch Value Unchanged"),
    /** 高危操作 reason 字段为空。 */
    ADMIN_TRADING_REASON_REQUIRED("90656", "Admin Trading Reason Required"),

    // STAGE-2-RISK-ADMIN 风控管理段（90800-90849，R2 一轮冻结 2026-05-12）
    /** 同 symbol+actionType+source 已激活。 */
    ADMIN_RISK_ACTION_ALREADY_ACTIVE("90800", "Admin Risk Action Already Active"),
    /** 风控动作 id 不存在。 */
    ADMIN_RISK_ACTION_NOT_FOUND("90801", "Admin Risk Action Not Found"),
    /** 试图停用非 MANUAL_ADMIN 触发的激活记录。 */
    ADMIN_RISK_ACTION_NOT_DEACTIVATABLE("90802", "Admin Risk Action Not Deactivatable"),
    /** risk_config 按 symbol 查不到。 */
    ADMIN_RISK_CONFIG_NOT_FOUND("90803", "Admin Risk Config Not Found"),
    /** POST 新建时 symbol 已存在。 */
    ADMIN_RISK_CONFIG_DUPLICATE_SYMBOL("90804", "Admin Risk Config Duplicate Symbol"),
    /** maxLeverage 越界。 */
    ADMIN_RISK_CONFIG_INVALID_LEVERAGE("90805", "Admin Risk Config Invalid Leverage"),
    /** position limit 异常。 */
    ADMIN_RISK_CONFIG_INVALID_POSITION_LIMIT("90806", "Admin Risk Config Invalid Position Limit"),
    /** hedgeThresholdUsd 为负。 */
    ADMIN_RISK_CONFIG_INVALID_HEDGE_THRESHOLD("90807", "Admin Risk Config Invalid Hedge Threshold"),
    /** risk_market_config marketCode 不存在。 */
    ADMIN_RISK_MARKET_CONFIG_NOT_FOUND("90808", "Admin Risk Market Config Not Found"),
    /** concentration_threshold_usd 为负。 */
    ADMIN_RISK_MARKET_CONFIG_INVALID_THRESHOLD("90809", "Admin Risk Market Config Invalid Threshold"),
    /** 高危操作 reason 字段为空。 */
    ADMIN_RISK_REASON_REQUIRED("90810", "Admin Risk Reason Required"),

    // STAGE-2-DEPOSIT 入金记录段（90850-90899，R2 一轮冻结 2026-05-12）
    /** 按 id 查 deposit 不存在。 */
    ADMIN_DEPOSIT_NOT_FOUND("90850", "Admin Deposit Not Found"),

    // STAGE-5-WALLET-PROVISION Phase 2 DLQ 段（90860-90869）
    ADMIN_WALLET_PROVISION_DLQ_NOT_FOUND("90860", "Admin Wallet Provision DLQ Not Found"),
    ADMIN_WALLET_PROVISION_DLQ_ALREADY_RESOLVED("90861", "Admin Wallet Provision DLQ Already Resolved"),
    /** 重试 reason 字段为空（与 STAGE-7-WITHDRAW 90503 / STAGE-6-KYC 90872 同口径，业务域独立）。 */
    ADMIN_WALLET_PROVISION_REASON_REQUIRED("90862", "Admin Wallet Provision Reason Required"),

    // STAGE-6-KYC 段（90870-90879）
    ADMIN_KYC_NOT_FOUND("90870", "Admin KYC Submission Not Found"),
    ADMIN_KYC_NOT_PENDING("90871", "Admin KYC Submission Not Pending"),
    ADMIN_KYC_REJECT_REASON_REQUIRED("90872", "Admin KYC Reject Reason Required"),

    // STAGE-8-NOTIFICATION 段（90880-90899）
    /** 模板 code 不存在 / 已 disabled / 已软删除（trading 30060/30065 → 90880 翻译）。 */
    ADMIN_NOTIFICATION_TEMPLATE_NOT_FOUND("90880", "Admin Notification Template Not Found"),
    /** 新建模板时 code 重复（trading 30061 → 90881）。 */
    ADMIN_NOTIFICATION_TEMPLATE_CODE_DUPLICATE("90881", "Admin Notification Template Code Duplicate"),
    /** 内置模板不可删除（trading 30062 → 90882）。 */
    ADMIN_NOTIFICATION_TEMPLATE_IN_USE("90882", "Admin Notification Template In Use"),
    /** 手动发送的目标 userId 不存在（trading 30063 → 90883）。 */
    ADMIN_NOTIFICATION_USER_NOT_FOUND("90883", "Admin Notification User Not Found"),
    /** code 不符合 ^[A-Z][A-Z0-9_]{2,63}$ 格式（console 前置校验 + trading 兜底）。 */
    ADMIN_NOTIFICATION_TEMPLATE_CODE_INVALID_FORMAT("90884", "Admin Notification Template Code Invalid Format"),
    /** channels 含非法枚举值（console 前置校验）。 */
    ADMIN_NOTIFICATION_TEMPLATE_CHANNEL_INVALID("90885", "Admin Notification Template Channel Invalid"),
    /** 按 id 查询通知不存在（trading 30064 → 90886）。 */
    ADMIN_NOTIFICATION_NOT_FOUND("90886", "Admin Notification Not Found"),
    /** 手动发送 reason 字段为空（console 前置校验）。 */
    ADMIN_NOTIFICATION_REASON_REQUIRED("90887", "Admin Notification Reason Required"),

    // STAGE-9-RISK-OPS-COMPLETE 段（90900-90919）
    /** 按 id 查询审计日志不存在。 */
    ADMIN_AUDIT_LOG_NOT_FOUND("90900", "Admin Audit Log Not Found"),

    // STAGE-11-OBS-RECON 段（90920-90939），详见管理端接口规范 §14
    /** 对账查询时 wallet-service 不可达（InternalRpcClient 传输级失败兜底）。 */
    ADMIN_RECONCILIATION_WALLET_UNREACHABLE("90920", "Admin Reconciliation Wallet Unreachable"),
    /** 对账查询时 trading-core-service 不可达（InternalRpcClient 传输级失败兜底）。 */
    ADMIN_RECONCILIATION_TRADING_UNREACHABLE("90921", "Admin Reconciliation Trading Unreachable"),
    /** 按 walletTxId 标记 resolved，但 unmatched 列表中找不到该项。 */
    ADMIN_RECONCILIATION_NOT_FOUND("90922", "Admin Reconciliation Item Not Found"),
    /** 手动标记 resolved 缺 reason（console 前置校验，≥10 字符）。 */
    ADMIN_RECONCILIATION_REASON_REQUIRED("90923", "Admin Reconciliation Reason Required"),
    /** 同一 walletTxId 已经被另一 admin 标记 resolved（写入 t_admin_operation_log 后唯一索引兜底）。 */
    ADMIN_RECONCILIATION_ALREADY_RESOLVED("90924", "Admin Reconciliation Already Resolved"),

    // STAGE-7-WITHDRAW Phase 4 出金审核段（90500-90599，详见管理端接口规范 §10.6）
    /** 出金单不存在（trading 30047 → 90500 翻译）。 */
    ADMIN_WITHDRAW_NOT_FOUND("90500", "Admin Withdraw Not Found"),
    /** 非 PENDING 状态不可审核（trading 30049 → 90501 翻译）。 */
    ADMIN_WITHDRAW_NOT_PENDING("90501", "Admin Withdraw Not Pending"),
    /** 非 APPROVED_DELAYED 状态不可紧急取消（trading 30050 → 90502 翻译）。 */
    ADMIN_WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED("90502", "Admin Withdraw Emergency Cancel Not Allowed"),
    /** reject/emergency-cancel 缺 reason（console 前置校验，不到 trading）。 */
    ADMIN_WITHDRAW_REJECT_REASON_REQUIRED("90503", "Admin Withdraw Reject Reason Required"),
    /** 审核竞态：同一记录被另一 admin 抢先处理（预留 future-use，当前阶段兜底到 90501）。 */
    ADMIN_WITHDRAW_REVIEW_RACE("90504", "Admin Withdraw Review Race"),
    /** trading-core / wallet-service 不可达，无法转发（InternalRpcClient 传输级失败兜底）。 */
    ADMIN_WITHDRAW_WALLET_UNREACHABLE("90505", "Admin Withdraw Wallet Unreachable"),

    // STAGE-14C2 杠杆档位（tier）配置段（90930-90932，master §7.3 console-service 9xxxx 段）
    // tier CRUD 写校验逻辑在 trading-core 抛出（同号值 90930-90932），console 透传后翻译为可读消息。
    /** tier id 不存在（trading 90930 → 90930 翻译，编辑/删除时）。 */
    ADMIN_TIER_NOT_FOUND("90930", "Admin Tier Not Found"),
    /** tier 参数校验失败（trading 90931 → 90931，max_lev×mm_rate>1.0 / tier_no 重复等）。 */
    ADMIN_TIER_VALIDATION_FAILED("90931", "Admin Tier Validation Failed"),
    /** tier notional 区间与现有档位重叠（trading 90932 → 90932）。 */
    ADMIN_TIER_OVERLAP("90932", "Admin Tier Overlap"),

    // STAGE-14D3b 平台配置段（90950-90952）
    /** 保证金模式配置参数非法（console 平台配置透传校验）。 */
    ADMIN_MARGIN_MODE_CONFIG_INVALID("90950", "Admin Margin Mode Config Invalid"),
    /** FX 暂停行为配置参数非法（console 平台配置透传校验）。 */
    ADMIN_FX_PAUSE_BEHAVIOR_INVALID("90951", "Admin FX Pause Behavior Invalid"),
    /** 风控阈值配置参数非法（console 平台配置透传校验）。 */
    ADMIN_RISK_THRESHOLD_INVALID("90952", "Admin Risk Threshold Invalid"),

    // STAGE-14E2 FX 实时汇率监控段（90940）
    /** FX 实时汇率快照透传时 market 下游 not-found / 错误（GET /internal/v1/market/fx/rates）。 */
    ADMIN_FX_RATE_NOT_FOUND("90940", "Admin FX Rate Not Found");

    private final String code;
    private final String message;

    AdminErrorCode(String code, String message) {
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
