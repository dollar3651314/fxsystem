package com.falconx.infrastructure.id;

import java.util.Locale;

/**
 * 对外可读业务编号格式化工具。
 *
 * <p>数据库主键统一使用雪花 ID，但北向接口和日志里仍需要更短、更容易定位的业务号。
 * 该工具负责把 64 位主键转换为带前缀的稳定短编码，避免各服务自行发明不同格式。
 */
public final class PublicIdentifierFormatter {

    private PublicIdentifierFormatter() {
    }

    /**
     * 格式化 identity 对外 UID。
     *
     * @param id 用户主键
     * @return 带 `U` 前缀的 base36 编码 UID
     */
    public static String userUid(long id) {
        return "U" + toUpperBase36(id);
    }

    /**
     * 格式化交易订单号。
     *
     * @param id 订单主键
     * @return 带 `O` 前缀的 base36 编码订单号
     */
    public static String orderNo(long id) {
        return "O" + toUpperBase36(id);
    }

    private static String toUpperBase36(long id) {
        return Long.toUnsignedString(id, 36).toUpperCase(Locale.ROOT);
    }
}
