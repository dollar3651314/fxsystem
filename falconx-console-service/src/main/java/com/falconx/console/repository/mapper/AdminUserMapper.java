package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminUserListRecord;
import com.falconx.console.repository.mapper.record.AdminUserRecord;
import com.falconx.console.repository.mapper.record.AdminUserRoleRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_admin_user} MyBatis Mapper。
 *
 * <p>R9.5 已有读路径 / 改密；R9.13（[`管理端接口规范`](../../../../../../../../../docs/api/管理端接口规范.md) §5.1）
 * 扩展为完整 CRUD + disable/enable/reset-password + 角色绑定维护。
 */
@Mapper
public interface AdminUserMapper {

    AdminUserRecord selectByUsername(@Param("username") String username);

    AdminUserRecord selectById(@Param("id") long id);

    int updateLastLogin(@Param("id") long id,
                        @Param("lastLoginAt") LocalDateTime lastLoginAt,
                        @Param("lastLoginIp") String lastLoginIp);

    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    /** P2 列表分页查询。 */
    List<AdminUserListRecord> selectUsersForList(@Param("username") String username,
                                                   @Param("realName") String realName,
                                                   @Param("status") Integer status,
                                                   @Param("roleCode") String roleCode,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    long countUsers(@Param("username") String username,
                    @Param("realName") String realName,
                    @Param("status") Integer status,
                    @Param("roleCode") String roleCode,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);

    /** 列表 + 详情共用：拉指定 userIds 的角色集合（避免 N+1）。 */
    List<AdminUserRoleRecord> selectRolesForUserIds(@Param("userIds") List<Long> userIds);

    /** 新建管理员（不写 last_login）。 */
    int insertUser(@Param("id") long id,
                    @Param("username") String username,
                    @Param("passwordHash") String passwordHash,
                    @Param("realName") String realName,
                    @Param("status") int status,
                    @Param("mustChangePassword") int mustChangePassword);

    /** 编辑 realName（不允许改 username）。 */
    int updateRealName(@Param("id") long id, @Param("realName") String realName);

    /** disable/enable: status 1=ACTIVE, 2=DISABLED. */
    int updateStatus(@Param("id") long id, @Param("status") int status);

    /** 删除（物理）。 */
    int deleteUser(@Param("id") long id);

    /** 角色绑定：清空 + 批量插入。 */
    int deleteUserRoles(@Param("userId") long userId);

    int insertUserRoles(@Param("userId") long userId, @Param("roleIds") List<Long> roleIds);

    /** 重置密码（清 must_change_password 由 reset 自行决定，因此不复用 updatePassword）。 */
    int resetPassword(@Param("id") long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("mustChangePassword") int mustChangePassword);
}
