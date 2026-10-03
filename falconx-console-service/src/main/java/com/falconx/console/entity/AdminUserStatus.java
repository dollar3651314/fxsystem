package com.falconx.console.entity;

/**
 * 管理员账号状态。
 *
 * <p>对应 {@code t_admin_user.status} 字段：1=ACTIVE，2=DISABLED。
 */
public enum AdminUserStatus {

    /** 账号正常可用。 */
    ACTIVE(1),
    /** 账号已禁用，登录返回 90008。 */
    DISABLED(2);

    private final int code;

    AdminUserStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * 从数据库存储值还原状态枚举。
     *
     * @param code 数据库 TINYINT 值
     * @return 对应的状态
     * @throws IllegalArgumentException 未知 code
     */
    public static AdminUserStatus fromCode(int code) {
        for (AdminUserStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown AdminUserStatus code: " + code);
    }
}
