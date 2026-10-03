package com.falconx.console.repository;

import com.falconx.console.entity.AdminUser;
import com.falconx.console.entity.AdminUserListItem;
import com.falconx.console.repository.mapper.record.AdminUserRoleRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 管理员用户 Repository。
 *
 * <p>R9.5 仅有读路径 + 改密；R9.13（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.1）
 * 扩展为完整 CRUD + disable/enable/reset-password + 角色绑定维护。
 */
public interface AdminUserRepository {

    Optional<AdminUser> findByUsername(String username);

    Optional<AdminUser> findById(long id);

    void updateLastLogin(long id, OffsetDateTime lastLoginAt, String lastLoginIp);

    void updatePassword(long id, String passwordHash);

    /** P2 列表（不含 roles 派生）。 */
    List<AdminUserListItem> findUsersForList(String username, String realName, Integer status,
                                              String roleCode, LocalDateTime from, LocalDateTime to,
                                              int offset, int limit);

    long countUsers(String username, String realName, Integer status, String roleCode,
                    LocalDateTime from, LocalDateTime to);

    /** 列表 / 详情共用：按 userIds 拉角色 JOIN 集合（避免 N+1）。 */
    List<AdminUserRoleRecord> findRolesForUserIds(List<Long> userIds);

    /** 新建管理员，返回生成的 id。 */
    long createUser(String username, String passwordHash, String realName,
                    int status, boolean mustChangePassword);

    void updateRealName(long id, String realName);

    void updateStatus(long id, int status);

    void deleteUser(long id);

    /** 角色绑定：清空 + 批量插入。 */
    void replaceUserRoles(long userId, List<Long> roleIds);

    /** 重置密码（含 must_change_password 控制）。 */
    void resetPassword(long id, String passwordHash, boolean mustChangePassword);
}
