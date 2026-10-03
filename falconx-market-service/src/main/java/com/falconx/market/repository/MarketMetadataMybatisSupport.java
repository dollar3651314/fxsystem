package com.falconx.market.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.market.entity.MarketOutboxStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * market 元数据 MyBatis 映射支持工具。
 *
 * <p>该工具统一处理低频元数据持久化中的公共时间转换，
 * 避免每个 MyBatis Repository 重复维护转换代码。
 */
final class MarketMetadataMybatisSupport {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private MarketMetadataMybatisSupport() {
    }

    /**
     * 把领域时间转换为数据库 UTC 本地时间。
     *
     * @param value 领域时间
     * @return UTC 本地时间
     */
    static LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 把数据库 UTC 本地时间恢复为偏移时间。
     *
     * @param value 数据库时间
     * @return UTC 偏移时间
     */
    static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /**
     * 把 Outbox 状态转换为数据库状态码。
     *
     * @param status Outbox 状态
     * @return 状态码
     */
    static int toOutboxStatusCode(MarketOutboxStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case DISPATCHING -> 1;
            case SENT -> 2;
            case FAILED -> 3;
            case DEAD -> 4;
        };
    }

    /**
     * 把数据库状态码恢复为 Outbox 状态枚举。
     *
     * @param code 状态码
     * @return Outbox 状态
     */
    static MarketOutboxStatus toOutboxStatus(int code) {
        return switch (code) {
            case 0 -> MarketOutboxStatus.PENDING;
            case 1 -> MarketOutboxStatus.DISPATCHING;
            case 2 -> MarketOutboxStatus.SENT;
            case 3 -> MarketOutboxStatus.FAILED;
            case 4 -> MarketOutboxStatus.DEAD;
            default -> throw new IllegalStateException("Unsupported market outbox status code: " + code);
        };
    }

    /**
     * 把对象序列化为 JSON。
     *
     * @param payload 负载对象
     * @return JSON 文本
     */
    static String toJson(Object payload) {
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize market JSON payload", exception);
        }
    }

    /**
     * 把 JSON 文本恢复为通用对象结构。
     *
     * @param payloadJson JSON 文本
     * @return 反序列化结果
     */
    static Object readJsonObject(String payloadJson) {
        try {
            return JSON_MAPPER.readValue(payloadJson, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize market JSON payload", exception);
        }
    }
}
