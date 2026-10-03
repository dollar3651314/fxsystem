package com.falconx.identity.repository.mapper;

import com.falconx.identity.repository.mapper.record.IdentityUserRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * identity 用户 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `falconx_identity.t_user` 的 SQL 声明，
 * Repository 会在其上层完成领域对象与记录对象的转换。
 */
@Mapper
public interface IdentityUserMapper {

    /**
     * 按邮箱查询用户记录。
     *
     * @param email 归一化邮箱
     * @return 用户记录
     */
    IdentityUserRecord selectByEmail(@Param("email") String email);

    /**
     * 按主键查询用户记录。
     *
     * @param id 用户主键
     * @return 用户记录
     */
    IdentityUserRecord selectById(@Param("id") Long id);

    /**
     * 插入新用户。
     *
     * @param record 用户持久化记录
     * @return 影响行数
     */
    int insertIdentityUser(IdentityUserRecord record);

    /**
     * 更新已有用户。
     *
     * @param record 用户持久化记录
     * @return 影响行数
     */
    int updateIdentityUser(IdentityUserRecord record);

    /**
     * STAGE-7-WITHDRAW：查询用户 KYC 等级（供 trading-core 出金前置校验）。
     *
     * @param id 用户主键
     * @return kyc_level（0/1）；用户不存在返回 null
     */
    Integer selectKycLevelById(@Param("id") Long id);

    /**
     * 管理端 patch 用户元数据：任一参数为 null 即保留原值（COALESCE）。
     *
     * @return 受影响行数
     */
    int updateAdminFields(@Param("userId") long userId,
                          @Param("email") String email,
                          @Param("emailVerified") Integer emailVerified,
                          @Param("groupCode") String groupCode,
                          @Param("kycLevel") Integer kycLevel,
                          @Param("statusCode") Integer statusCode);
}
