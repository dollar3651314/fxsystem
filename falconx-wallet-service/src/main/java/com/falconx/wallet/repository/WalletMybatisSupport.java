package com.falconx.wallet.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.entity.WalletAddressStatus;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletOutboxStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * wallet MyBatis 持久化映射支持工具。
 *
 * <p>该工具统一处理 wallet owner 数据的公共转换规则：
 *
 * <ul>
 *   <li>链类型枚举与数据库字符串值转换</li>
 *   <li>地址状态、入金状态与数据库状态码转换</li>
 *   <li>UTC 偏移时间与数据库本地时间转换</li>
 * </ul>
 *
 * <p>这样可以避免每个 Repository 都重复维护一套相同的映射逻辑。
 */
final class WalletMybatisSupport {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private WalletMybatisSupport() {
    }

    /**
     * 把领域时间转换为数据库存储使用的 UTC 本地时间。
     *
     * @param value 领域时间
     * @return UTC 本地时间
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
     * 把链类型转换为数据库存储值。
     *
     * @param chainType 链类型
     * @return 数据库存储值
     */
    static String toChainValue(ChainType chainType) {
        return chainType.name();
    }

    /**
     * 把数据库值恢复为链类型枚举。
     *
     * @param value 数据库存储值
     * @return 链类型
     */
    static ChainType toChainType(String value) {
        return ChainType.valueOf(value);
    }

    /**
     * 把地址状态转换为数据库状态码。
     *
     * @param status 地址状态
     * @return 数据库状态码
     */
    static int toAddressStatusCode(WalletAddressStatus status) {
        return switch (status) {
            case ASSIGNED -> 1;
            case DISABLED -> 2;
        };
    }

    /**
     * 把数据库地址状态码恢复为领域枚举。
     *
     * @param code 数据库状态码
     * @return 地址状态
     */
    static WalletAddressStatus toAddressStatus(int code) {
        return switch (code) {
            case 1 -> WalletAddressStatus.ASSIGNED;
            case 2 -> WalletAddressStatus.DISABLED;
            default -> throw new IllegalStateException("Unsupported wallet address status code: " + code);
        };
    }

    /**
     * 把原始入金状态转换为数据库状态码。
     *
     * @param status 原始入金状态
     * @return 数据库状态码
     */
    static int toDepositStatusCode(WalletDepositStatus status) {
        return switch (status) {
            case DETECTED -> 0;
            case CONFIRMING -> 1;
            case CONFIRMED -> 2;
            case REVERSED -> 3;
            case IGNORED -> 4;
        };
    }

    /**
     * 把数据库原始入金状态码恢复为领域枚举。
     *
     * @param code 数据库状态码
     * @return 原始入金状态
     */
    static WalletDepositStatus toDepositStatus(int code) {
        return switch (code) {
            case 0 -> WalletDepositStatus.DETECTED;
            case 1 -> WalletDepositStatus.CONFIRMING;
            case 2 -> WalletDepositStatus.CONFIRMED;
            case 3 -> WalletDepositStatus.REVERSED;
            case 4 -> WalletDepositStatus.IGNORED;
            default -> throw new IllegalStateException("Unsupported wallet deposit status code: " + code);
        };
    }

    /**
     * 把 Outbox 状态转换为数据库状态码。
     *
     * @param status Outbox 状态
     * @return 数据库状态码
     */
    static int toOutboxStatusCode(WalletOutboxStatus status) {
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
    static WalletOutboxStatus toOutboxStatus(int code) {
        return switch (code) {
            case 0 -> WalletOutboxStatus.PENDING;
            case 1 -> WalletOutboxStatus.DISPATCHING;
            case 2 -> WalletOutboxStatus.SENT;
            case 3 -> WalletOutboxStatus.FAILED;
            case 4 -> WalletOutboxStatus.DEAD;
            default -> throw new IllegalStateException("Unsupported wallet outbox status code: " + code);
        };
    }

    /**
     * 把对象序列化为 JSON 字符串。
     *
     * @param payload 负载对象
     * @return JSON 文本
     */
    static String toJson(Object payload) {
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize wallet JSON payload", exception);
        }
    }

    /**
     * 把 JSON 文本恢复为通用对象。
     *
     * @param payloadJson JSON 文本
     * @return 通用对象结构
     */
    static Object readJsonObject(String payloadJson) {
        try {
            return JSON_MAPPER.readValue(payloadJson, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize wallet JSON payload", exception);
        }
    }
}
