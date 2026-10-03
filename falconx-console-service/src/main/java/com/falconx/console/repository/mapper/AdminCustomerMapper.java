package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminCustomerProfileRecord;
import com.falconx.console.repository.mapper.record.AdminCustomerRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-2-CUSTOMER：客户跨 schema 只读 Mapper（identity.t_user + trading.t_account JOIN）。
 *
 * <p>所有方法仅 SELECT；console DB user 对 identity / trading schema 仅有 SELECT 权限
 * （[`管理端架构`](docs/architecture/管理端架构.md) §1.3）。
 */
@Mapper
public interface AdminCustomerMapper {

    /**
     * 分页查询客户列表（按 created_at DESC）。
     *
     * @param emailLike 邮箱模糊（含 % 通配；调用方拼接）；空字符串表示不筛选
     * @param statusCodes 状态枚举值列表（{@code Integer}），空列表表示不筛选
     * @param fromCreatedAt 创建时间起（含），可空
     * @param toCreatedAt 创建时间止（不含），可空
     * @param offset 偏移
     * @param limit 数量
     * @return 客户记录列表（无 LIMIT 后 marginUsed/frozen 仍 SELECT 但不在响应里返回，预留详情复用）
     */
    List<AdminCustomerRecord> selectCustomers(@Param("emailLike") String emailLike,
                                              @Param("statusCodes") List<Integer> statusCodes,
                                              @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                                              @Param("toCreatedAt") LocalDateTime toCreatedAt,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /**
     * 计数（同筛选条件，用于分页 total）。
     */
    long countCustomers(@Param("emailLike") String emailLike,
                        @Param("statusCodes") List<Integer> statusCodes,
                        @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                        @Param("toCreatedAt") LocalDateTime toCreatedAt);

    /**
     * 详情查询。
     *
     * @param userId 客户主键
     * @return 客户记录或 null
     */
    AdminCustomerRecord selectCustomerById(@Param("userId") long userId);

    /**
     * STAGE-1B-USER-PROFILE：跨 schema 单查 t_user_profile 行（不 JOIN 主查询，
     * 因 profile 字段较多，仅在 detail 路径独立读取）。
     *
     * @param userId 客户主键
     * @return profile 行或 null
     */
    AdminCustomerProfileRecord selectCustomerProfileById(@Param("userId") long userId);
}
