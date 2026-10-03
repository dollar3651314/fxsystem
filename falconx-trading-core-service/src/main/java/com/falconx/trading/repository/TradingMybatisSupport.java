package com.falconx.trading.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.trading.entity.TradingDepositStatus;
import com.falconx.trading.entity.TradingHedgeLogStatus;
import com.falconx.trading.entity.TradingHedgeTriggerSource;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingOrderStatus;
import com.falconx.trading.entity.TradingOrderType;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingTradeType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * trading MyBatis 持久化映射支持工具。
 *
 * <p>该工具统一处理交易核心持久化中的公共转换规则：
 *
 * <ul>
 *   <li>领域时间与数据库本地时间的转换</li>
 *   <li>数据库状态码与领域枚举的转换</li>
 *   <li>Outbox / Inbox payload 的 JSON 序列化和反序列化</li>
 * </ul>
 *
 * <p>这样可以避免每个 MyBatis Repository 都各自维护一套重复映射逻辑。
 */
final class TradingMybatisSupport {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private TradingMybatisSupport() {
    }

    /**
     * 把领域时间转换为数据库存储使用的本地时间。
     *
     * @param value 领域时间
     * @return 本地日期时间
     */
    static LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 把数据库时间恢复为统一使用的 UTC 偏移时间。
     *
     * @param value 数据库时间
     * @return UTC 偏移时间
     */
    static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /**
     * 把账本业务类型转换为数据库状态码。
     *
     * @param bizType 账本业务类型
     * @return 账本业务类型码
     */
    static int toLedgerBizTypeCode(TradingLedgerBizType bizType) {
        return switch (bizType) {
            case DEPOSIT_CREDIT -> 1;
            case DEPOSIT_REVERSAL -> 2;
            case ORDER_MARGIN_RESERVED -> 3;
            case ORDER_FEE_CHARGED -> 4;
            case ORDER_MARGIN_CONFIRMED -> 5;
            case ISOLATED_MARGIN_SUPPLEMENT -> 10;
            case SWAP_CHARGE -> 6;
            case SWAP_INCOME -> 7;
            case REALIZED_PNL -> 8;
            case LIQUIDATION_PNL -> 9;
            case ADMIN_BALANCE_ADJUST -> 11;
            case PENDING_ORDER_RELEASED -> 12;
            case WITHDRAW_FREEZE -> 13;
            case WITHDRAW_REFUND_CANCEL -> 14;
            case WITHDRAW_REFUND_REJECT -> 15;
            case WITHDRAW_REFUND_EMERGENCY -> 16;
            case WITHDRAW_SETTLE -> 17;
            case WITHDRAW_REFUND_CHAIN_FAILED -> 18;
        };
    }

    /**
     * 把数据库账本业务类型码恢复为领域枚举。
     *
     * @param code 账本业务类型码
     * @return 账本业务类型
     */
    static TradingLedgerBizType toLedgerBizType(int code) {
        return switch (code) {
            case 1 -> TradingLedgerBizType.DEPOSIT_CREDIT;
            case 2 -> TradingLedgerBizType.DEPOSIT_REVERSAL;
            case 3 -> TradingLedgerBizType.ORDER_MARGIN_RESERVED;
            case 4 -> TradingLedgerBizType.ORDER_FEE_CHARGED;
            case 5 -> TradingLedgerBizType.ORDER_MARGIN_CONFIRMED;
            case 10 -> TradingLedgerBizType.ISOLATED_MARGIN_SUPPLEMENT;
            case 6 -> TradingLedgerBizType.SWAP_CHARGE;
            case 7 -> TradingLedgerBizType.SWAP_INCOME;
            case 8 -> TradingLedgerBizType.REALIZED_PNL;
            case 9 -> TradingLedgerBizType.LIQUIDATION_PNL;
            case 11 -> TradingLedgerBizType.ADMIN_BALANCE_ADJUST;
            case 12 -> TradingLedgerBizType.PENDING_ORDER_RELEASED;
            case 13 -> TradingLedgerBizType.WITHDRAW_FREEZE;
            case 14 -> TradingLedgerBizType.WITHDRAW_REFUND_CANCEL;
            case 15 -> TradingLedgerBizType.WITHDRAW_REFUND_REJECT;
            case 16 -> TradingLedgerBizType.WITHDRAW_REFUND_EMERGENCY;
            case 17 -> TradingLedgerBizType.WITHDRAW_SETTLE;
            case 18 -> TradingLedgerBizType.WITHDRAW_REFUND_CHAIN_FAILED;
            default -> throw new IllegalStateException("Unsupported ledger biz type code: " + code);
        };
    }

    /**
     * 把业务入金状态转换为数据库状态码。
     *
     * @param status 业务入金状态
     * @return 状态码
     */
    static int toDepositStatusCode(TradingDepositStatus status) {
        return switch (status) {
            case CREDITED -> 1;
            case REVERSED -> 2;
            case REJECTED -> 3;
        };
    }

    /**
     * 把数据库入金状态码恢复为领域枚举。
     *
     * @param code 状态码
     * @return 业务入金状态
     */
    static TradingDepositStatus toDepositStatus(int code) {
        return switch (code) {
            case 1 -> TradingDepositStatus.CREDITED;
            case 2 -> TradingDepositStatus.REVERSED;
            case 3 -> TradingDepositStatus.REJECTED;
            default -> throw new IllegalStateException("Unsupported deposit status code: " + code);
        };
    }

    /**
     * 把订单方向转换为数据库状态码。
     *
     * @param side 订单方向
     * @return 方向码
     */
    static int toSideCode(TradingOrderSide side) {
        return switch (side) {
            case BUY -> 1;
            case SELL -> 2;
        };
    }

    /**
     * 把数据库方向码恢复为订单方向。
     *
     * @param code 方向码
     * @return 订单方向
     */
    static TradingOrderSide toSide(int code) {
        return switch (code) {
            case 1 -> TradingOrderSide.BUY;
            case 2 -> TradingOrderSide.SELL;
            default -> throw new IllegalStateException("Unsupported order side code: " + code);
        };
    }

    /**
     * 把对冲观测状态转换为数据库状态码。
     */
    static int toHedgeLogStatusCode(TradingHedgeLogStatus actionStatus) {
        return switch (actionStatus) {
            case ALERT_ONLY -> 1;
            case RECOVERED -> 2;
        };
    }

    /**
     * 把数据库对冲观测状态码恢复为领域枚举。
     */
    static TradingHedgeLogStatus toHedgeLogStatus(int code) {
        return switch (code) {
            case 1 -> TradingHedgeLogStatus.ALERT_ONLY;
            case 2 -> TradingHedgeLogStatus.RECOVERED;
            default -> throw new IllegalStateException("Unsupported hedge log status code: " + code);
        };
    }

    /**
     * 把风控动作类型转换为数据库状态码。
     */
    static int toRiskControlActionTypeCode(TradingRiskControlActionType actionType) {
        return switch (actionType) {
            case REJECT_OPEN     -> 1;
            case REDUCE_ONLY     -> 2;
            case SUSPEND_SYMBOL  -> 3;
            case GLOBAL_PAUSE    -> 4;
        };
    }

    /**
     * 把数据库风控动作类型码恢复为领域枚举。
     */
    static TradingRiskControlActionType toRiskControlActionType(int code) {
        return switch (code) {
            case 1 -> TradingRiskControlActionType.REJECT_OPEN;
            case 2 -> TradingRiskControlActionType.REDUCE_ONLY;
            case 3 -> TradingRiskControlActionType.SUSPEND_SYMBOL;
            case 4 -> TradingRiskControlActionType.GLOBAL_PAUSE;
            default -> throw new IllegalStateException("Unsupported risk control action type code: " + code);
        };
    }

    /**
     * 把对冲观测触发来源转换为数据库状态码。
     */
    static int toHedgeTriggerSourceCode(TradingHedgeTriggerSource triggerSource) {
        return switch (triggerSource) {
            case OPEN_POSITION -> 1;
            case MANUAL_CLOSE -> 2;
            case TAKE_PROFIT -> 3;
            case STOP_LOSS -> 4;
            case LIQUIDATION -> 5;
            case PRICE_TICK -> 6;
        };
    }

    /**
     * 把数据库触发来源码恢复为领域枚举。
     */
    static TradingHedgeTriggerSource toHedgeTriggerSource(int code) {
        return switch (code) {
            case 1 -> TradingHedgeTriggerSource.OPEN_POSITION;
            case 2 -> TradingHedgeTriggerSource.MANUAL_CLOSE;
            case 3 -> TradingHedgeTriggerSource.TAKE_PROFIT;
            case 4 -> TradingHedgeTriggerSource.STOP_LOSS;
            case 5 -> TradingHedgeTriggerSource.LIQUIDATION;
            case 6 -> TradingHedgeTriggerSource.PRICE_TICK;
            default -> throw new IllegalStateException("Unsupported hedge trigger source code: " + code);
        };
    }

    /**
     * 把订单类型转换为数据库状态码。
     *
     * @param type 订单类型
     * @return 类型码
     */
    static int toOrderTypeCode(TradingOrderType type) {
        return switch (type) {
            case MARKET -> 1;
        };
    }

    /**
     * 把数据库订单类型码恢复为领域枚举。
     *
     * @param code 类型码
     * @return 订单类型
     */
    static TradingOrderType toOrderType(int code) {
        return switch (code) {
            case 1 -> TradingOrderType.MARKET;
            default -> throw new IllegalStateException("Unsupported order type code: " + code);
        };
    }

    /**
     * 把订单状态转换为数据库状态码。
     *
     * @param status 订单状态
     * @return 状态码
     */
    static int toOrderStatusCode(TradingOrderStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case TRIGGERED -> 1;
            case FILLED -> 2;
            case CANCELLED -> 3;
            case REJECTED -> 4;
        };
    }

    /**
     * 把数据库订单状态码恢复为领域状态。
     *
     * @param code 状态码
     * @return 订单状态
     */
    static TradingOrderStatus toOrderStatus(int code) {
        return switch (code) {
            case 0 -> TradingOrderStatus.PENDING;
            case 1 -> TradingOrderStatus.TRIGGERED;
            case 2 -> TradingOrderStatus.FILLED;
            case 3 -> TradingOrderStatus.CANCELLED;
            case 4 -> TradingOrderStatus.REJECTED;
            default -> throw new IllegalStateException("Unsupported order status code: " + code);
        };
    }

    /**
     * 把持仓状态转换为数据库状态码。
     *
     * @param status 持仓状态
     * @return 状态码
     */
    static int toPositionStatusCode(TradingPositionStatus status) {
        return switch (status) {
            case OPEN -> 1;
            case CLOSED -> 2;
            case LIQUIDATED -> 3;
        };
    }

    /**
     * 把数据库持仓状态码恢复为领域枚举。
     *
     * @param code 状态码
     * @return 持仓状态
     */
    static TradingPositionStatus toPositionStatus(int code) {
        return switch (code) {
            case 1 -> TradingPositionStatus.OPEN;
            case 2 -> TradingPositionStatus.CLOSED;
            case 3 -> TradingPositionStatus.LIQUIDATED;
            default -> throw new IllegalStateException("Unsupported position status code: " + code);
        };
    }

    /**
     * 把保证金模式转换为数据库状态码。
     */
    static int toMarginModeCode(TradingMarginMode marginMode) {
        return switch (marginMode) {
            case CROSS -> 1;
            case ISOLATED -> 2;
        };
    }

    /**
     * 把数据库保证金模式码恢复为领域枚举。
     */
    static TradingMarginMode toMarginMode(int code) {
        return switch (code) {
            case 1 -> TradingMarginMode.CROSS;
            case 2 -> TradingMarginMode.ISOLATED;
            default -> throw new IllegalStateException("Unsupported margin mode code: " + code);
        };
    }

    /**
     * 把平仓原因转换为数据库状态码。
     */
    static Integer toCloseReasonCode(TradingPositionCloseReason closeReason) {
        if (closeReason == null) {
            return null;
        }
        return switch (closeReason) {
            case MANUAL -> 1;
            case TAKE_PROFIT -> 2;
            case STOP_LOSS -> 3;
            case LIQUIDATION -> 4;
            // STAGE-14D2 Task 1：CROSS 账户级强平。code 5 此前空闲（4=LIQUIDATION），与 LIQUIDATION 区分留痕。
            case CROSS_STOP_OUT -> 5;
        };
    }

    /**
     * 把数据库平仓原因码恢复为领域枚举。
     */
    static TradingPositionCloseReason toCloseReason(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 1 -> TradingPositionCloseReason.MANUAL;
            case 2 -> TradingPositionCloseReason.TAKE_PROFIT;
            case 3 -> TradingPositionCloseReason.STOP_LOSS;
            case 4 -> TradingPositionCloseReason.LIQUIDATION;
            // STAGE-14D2 Task 1：CROSS 账户级强平 close_reason 回读。
            case 5 -> TradingPositionCloseReason.CROSS_STOP_OUT;
            default -> throw new IllegalStateException("Unsupported close reason code: " + code);
        };
    }

    /**
     * 把成交类型转换为数据库状态码。
     */
    static int toTradeTypeCode(TradingTradeType tradeType) {
        return switch (tradeType) {
            case OPEN -> 1;
            case CLOSE -> 2;
            case LIQUIDATION -> 3;
        };
    }

    /**
     * 把数据库成交类型码恢复为领域枚举。
     */
    static TradingTradeType toTradeType(int code) {
        return switch (code) {
            case 1 -> TradingTradeType.OPEN;
            case 2 -> TradingTradeType.CLOSE;
            case 3 -> TradingTradeType.LIQUIDATION;
            default -> throw new IllegalStateException("Unsupported trade type code: " + code);
        };
    }

    /**
     * 把 Outbox 状态转换为数据库状态码。
     *
     * @param status Outbox 状态
     * @return 状态码
     */
    static int toOutboxStatusCode(TradingOutboxStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case DISPATCHING -> 1;
            case SENT -> 2;
            case FAILED -> 3;
            case DEAD -> 4;
        };
    }

    /**
     * 把数据库 Outbox 状态码恢复为领域枚举。
     *
     * @param code 状态码
     * @return Outbox 状态
     */
    static TradingOutboxStatus toOutboxStatus(int code) {
        return switch (code) {
            case 0 -> TradingOutboxStatus.PENDING;
            case 1 -> TradingOutboxStatus.DISPATCHING;
            case 2 -> TradingOutboxStatus.SENT;
            case 3 -> TradingOutboxStatus.FAILED;
            case 4 -> TradingOutboxStatus.DEAD;
            default -> throw new IllegalStateException("Unsupported outbox status code: " + code);
        };
    }

    /**
     * 把链枚举转换为数据库值。
     *
     * @param chainType 链类型
     * @return 数据库存储值
     */
    static String toChainValue(ChainType chainType) {
        return chainType.name();
    }

    /**
     * 把数据库链值恢复为枚举。
     *
     * @param value 链值
     * @return 链类型
     */
    static ChainType toChainType(String value) {
        return ChainType.valueOf(value);
    }

    /**
     * 序列化交易域 JSON payload。
     *
     * @param payload 原始对象
     * @return JSON 字符串
     */
    static String toJson(Object payload) {
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize trading payload", exception);
        }
    }

    /**
     * 反序列化交易域 JSON payload。
     *
     * @param payload JSON 字符串
     * @return 反序列化后的 JSON 树对象
     */
    static Object readJsonObject(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return JSON_MAPPER.readTree(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize trading payload", exception);
        }
    }
}
