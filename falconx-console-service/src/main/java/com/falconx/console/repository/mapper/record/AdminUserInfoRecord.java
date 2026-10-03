package com.falconx.console.repository.mapper.record;

/**
 * 管理端通用「用户基本信息」enrichment 跨 schema 查询结果。
 *
 * <p>来源：单 SQL JOIN
 * {@code falconx_identity.t_user u LEFT JOIN falconx_identity.t_user_profile p ON p.user_id = u.id
 *        WHERE u.id IN (userIds)}。
 *
 * <p>用于给各 admin 列表/详情 DTO（订单/持仓/入金/出金/KYC/通知/风控/对账/价格告警等）按 userId
 * 批量补充展示用的姓名 + 邮箱 + uid。沿用 {@link AdminWithdrawEnrichmentRecord} 的跨 schema enrichment 范式。
 *
 * @param userId    用户 ID（雪花号）
 * @param uid       对外展示短号（t_user.uid）
 * @param email     邮箱（t_user.email，明文）
 * @param firstName 名（t_user_profile.first_name，可空——profile 未填）
 * @param lastName  姓（t_user_profile.last_name，可空）
 */
public record AdminUserInfoRecord(
        Long userId,
        String uid,
        String email,
        String firstName,
        String lastName
) {

    /**
     * 拼接展示用全名，口径与 {@code MybatisAdminCustomerRepository.joinFullName} 一致：
     * 两者皆空 → null（前端显示「—」）；仅一者有值 → 该值；皆有 → {@code firstName + " " + lastName}。
     */
    public String fullName() {
        boolean hasFirst = firstName != null && !firstName.isBlank();
        boolean hasLast = lastName != null && !lastName.isBlank();
        if (!hasFirst && !hasLast) {
            return null;
        }
        if (!hasFirst) {
            return lastName;
        }
        if (!hasLast) {
            return firstName;
        }
        return firstName + " " + lastName;
    }
}
