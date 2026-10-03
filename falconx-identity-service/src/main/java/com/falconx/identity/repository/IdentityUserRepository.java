package com.falconx.identity.repository;

import com.falconx.identity.entity.IdentityUser;
import java.util.Optional;

/**
 * 用户仓储抽象。
 *
 * <p>该仓储负责 identity owner 用户事实的读写。
 * 当前正式实现必须通过 `MyBatis Mapper + XML + Repository` 链路访问数据库。
 */
public interface IdentityUserRepository {

    /**
     * 按邮箱查询用户。
     *
     * @param email 归一化邮箱
     * @return 用户记录
     */
    Optional<IdentityUser> findByEmail(String email);

    /**
     * 按主键查询用户。
     *
     * @param userId 用户主键 ID
     * @return 用户记录
     */
    Optional<IdentityUser> findById(long userId);

    /**
     * 保存或覆盖用户记录。
     *
     * @param user 用户对象
     * @return 持久化后的用户对象
     */
    IdentityUser save(IdentityUser user);

    /**
     * STAGE-7-WITHDRAW：查询用户 KYC 等级（供 trading-core 出金前置校验）。
     *
     * @param userId 用户主键
     * @return kyc_level（{@code Optional.empty()} 表示用户不存在）
     */
    Optional<Integer> findKycLevelByUserId(long userId);

    /**
     * 管理端 patch t_user 元数据字段。任一参数为 null 即保留原值（COALESCE）。
     *
     * @param userId         用户主键
     * @param email          新邮箱（null = 不改）
     * @param emailVerified  null = 不改；0/1 写入
     * @param groupCode      null = 不改
     * @param kycLevel       null = 不改
     * @param statusCode     null = 不改；UserStatus.ordinal()
     * @return 受影响行数（0 = 用户不存在）
     */
    int updateAdminFields(long userId,
                          String email,
                          Integer emailVerified,
                          String groupCode,
                          Integer kycLevel,
                          Integer statusCode);
}
