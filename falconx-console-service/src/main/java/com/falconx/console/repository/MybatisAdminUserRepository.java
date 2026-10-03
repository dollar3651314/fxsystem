package com.falconx.console.repository;

import com.falconx.console.entity.AdminUser;
import com.falconx.console.entity.AdminUserListItem;
import com.falconx.console.entity.AdminUserStatus;
import com.falconx.console.repository.mapper.AdminUserMapper;
import com.falconx.console.repository.mapper.record.AdminUserRecord;
import com.falconx.console.repository.mapper.record.AdminUserRoleRecord;
import com.falconx.infrastructure.id.IdGenerator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminUserRepository implements AdminUserRepository {

    private final AdminUserMapper adminUserMapper;
    private final IdGenerator idGenerator;

    public MybatisAdminUserRepository(AdminUserMapper adminUserMapper, IdGenerator idGenerator) {
        this.adminUserMapper = adminUserMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<AdminUser> findByUsername(String username) {
        return Optional.ofNullable(toDomain(adminUserMapper.selectByUsername(username)));
    }

    @Override
    public Optional<AdminUser> findById(long id) {
        return Optional.ofNullable(toDomain(adminUserMapper.selectById(id)));
    }

    @Override
    public void updateLastLogin(long id, OffsetDateTime lastLoginAt, String lastLoginIp) {
        adminUserMapper.updateLastLogin(id, lastLoginAt.toLocalDateTime(), lastLoginIp);
    }

    @Override
    public void updatePassword(long id, String passwordHash) {
        adminUserMapper.updatePassword(id, passwordHash);
    }

    @Override
    public List<AdminUserListItem> findUsersForList(String username, String realName, Integer status,
                                                     String roleCode, LocalDateTime from, LocalDateTime to,
                                                     int offset, int limit) {
        return adminUserMapper.selectUsersForList(username, realName, status, roleCode, from, to, offset, limit)
                .stream()
                .map(r -> new AdminUserListItem(
                        r.id(), r.username(), r.realName(), r.status(),
                        r.mustChangePassword() != null && r.mustChangePassword() == 1,
                        r.lastLoginAt(), r.lastLoginIp(), r.createdAt(),
                        List.of()))  // roles 由 service 后聚合
                .toList();
    }

    @Override
    public long countUsers(String username, String realName, Integer status, String roleCode,
                           LocalDateTime from, LocalDateTime to) {
        return adminUserMapper.countUsers(username, realName, status, roleCode, from, to);
    }

    @Override
    public List<AdminUserRoleRecord> findRolesForUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return adminUserMapper.selectRolesForUserIds(userIds);
    }

    @Override
    public long createUser(String username, String passwordHash, String realName,
                            int status, boolean mustChangePassword) {
        long id = idGenerator.nextId();
        adminUserMapper.insertUser(id, username, passwordHash, realName, status, mustChangePassword ? 1 : 0);
        return id;
    }

    @Override
    public void updateRealName(long id, String realName) {
        adminUserMapper.updateRealName(id, realName);
    }

    @Override
    public void updateStatus(long id, int status) {
        adminUserMapper.updateStatus(id, status);
    }

    @Override
    public void deleteUser(long id) {
        adminUserMapper.deleteUserRoles(id);
        adminUserMapper.deleteUser(id);
    }

    @Override
    public void replaceUserRoles(long userId, List<Long> roleIds) {
        adminUserMapper.deleteUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            adminUserMapper.insertUserRoles(userId, roleIds);
        }
    }

    @Override
    public void resetPassword(long id, String passwordHash, boolean mustChangePassword) {
        adminUserMapper.resetPassword(id, passwordHash, mustChangePassword ? 1 : 0);
    }

    private AdminUser toDomain(AdminUserRecord record) {
        if (record == null) {
            return null;
        }
        return new AdminUser(
                record.id(), record.username(), record.passwordHash(), record.realName(),
                AdminUserStatus.fromCode(record.status()),
                record.mustChangePassword() != null && record.mustChangePassword() == 1,
                toOffsetDateTime(record.lastLoginAt()), record.lastLoginIp(),
                toOffsetDateTime(record.createdAt()), toOffsetDateTime(record.updatedAt())
        );
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atOffset(ZoneOffset.UTC);
    }
}
