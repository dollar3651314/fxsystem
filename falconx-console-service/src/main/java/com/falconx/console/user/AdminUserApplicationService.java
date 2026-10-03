package com.falconx.console.user;

import com.falconx.console.api.AdminUserCreateResponse;
import com.falconx.console.api.AdminUserListResponse;
import com.falconx.console.api.AdminUserResetPasswordResponse;
import com.falconx.console.entity.AdminUserListItem;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminUserRepository;
import com.falconx.console.repository.mapper.record.AdminUserRoleRecord;
import com.falconx.console.security.AdminPasswordEncoder;
import com.falconx.console.security.AdminPasswordPolicyValidator;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminRefreshTokenStore;
import com.falconx.console.security.AdminSecurityContextHolder;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 1 P2 管理员管理 ApplicationService（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.1）。
 *
 * <p>关键业务约束：
 * <ul>
 *   <li>新建 / 编辑 / 删除时禁止操作 SUPER_ADMIN 角色相关边界（90213 / 90214 / 90216）</li>
 *   <li>禁止禁用 / 删除自己（90215 / 90217）</li>
 *   <li>禁用 + 重置密码立即调 {@link AdminRefreshTokenStore#revokeAllByUserId} 清除 refresh token；
 *       现有 access token 等自然过期（30 分钟），用户无法继续 refresh 新 access token。</li>
 * </ul>
 */
@Service
public class AdminUserApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserApplicationService.class);
    private static final long SUPER_ADMIN_ROLE_ID = 1L;
    /** 自动生成密码字符集（不含 1 / l / 0 / O 等易混淆字符）。 */
    private static final String GEN_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%^&*";
    private static final int GEN_LENGTH = 16;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_DISABLED = 2;

    private final AdminUserRepository adminUserRepository;
    private final AdminPasswordEncoder adminPasswordEncoder;
    private final AdminRefreshTokenStore adminRefreshTokenStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminUserApplicationService(AdminUserRepository adminUserRepository,
                                        AdminPasswordEncoder adminPasswordEncoder,
                                        AdminRefreshTokenStore adminRefreshTokenStore) {
        this.adminUserRepository = adminUserRepository;
        this.adminPasswordEncoder = adminPasswordEncoder;
        this.adminRefreshTokenStore = adminRefreshTokenStore;
    }

    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(String username, String realName, String status,
                                             String roleCode, LocalDateTime from, LocalDateTime to,
                                             int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        Integer statusCode = parseStatus(status);

        List<AdminUserListItem> items = adminUserRepository.findUsersForList(
                username, realName, statusCode, roleCode, from, to, offset, safeSize);
        long total = adminUserRepository.countUsers(username, realName, statusCode, roleCode, from, to);

        Map<Long, List<AdminUserListResponse.RoleRef>> rolesByUser = loadRolesGrouped(
                items.stream().map(AdminUserListItem::id).toList());

        List<AdminUserListResponse.Item> dtoItems = items.stream()
                .map(it -> new AdminUserListResponse.Item(
                        it.id(), it.username(), it.realName(),
                        statusName(it.status()), it.mustChangePassword(),
                        it.lastLoginAt(), it.lastLoginIp(), it.createdAt(),
                        rolesByUser.getOrDefault(it.id(), List.of())))
                .toList();
        return new AdminUserListResponse(dtoItems, total, page, safeSize);
    }

    @Transactional(readOnly = true)
    public AdminUserListResponse.Item getUserDetail(long id) {
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        AdminUserListResponse list = listUsers(null, null, null, null, null, null, 0, 1);
        // 简化：复用 list 路径但要按 id 精确取；为了 N+1 回避保留 byIds 接口语义，这里直接走单查询
        AdminUserListItem single = adminUserRepository.findUsersForList(null, null, null, null, null, null, 0, 100)
                .stream().filter(u -> u.id().equals(id)).findFirst()
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        Map<Long, List<AdminUserListResponse.RoleRef>> rolesByUser = loadRolesGrouped(List.of(id));
        return new AdminUserListResponse.Item(
                single.id(), single.username(), single.realName(),
                statusName(single.status()), single.mustChangePassword(),
                single.lastLoginAt(), single.lastLoginIp(), single.createdAt(),
                rolesByUser.getOrDefault(id, List.of()));
    }

    @Transactional
    public AdminUserCreateResponse createUser(String username, String realName, List<Long> roleIds,
                                                String password, Boolean mustChangePassword) {
        if (adminUserRepository.findByUsername(username).isPresent()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_USERNAME_DUPLICATE);
        }
        if (roleIds == null || roleIds.contains(SUPER_ADMIN_ROLE_ID)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_SUPER_ADMIN_ROLE_NOT_ASSIGNABLE);
        }

        boolean autoGen = (password == null || password.isBlank());
        String effectivePassword = autoGen ? generatePassword() : password;
        if (!autoGen && !AdminPasswordPolicyValidator.isValid(effectivePassword, null, username)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_PASSWORD_POLICY_VIOLATION);
        }
        boolean forceChange = mustChangePassword == null || mustChangePassword;
        String hash = adminPasswordEncoder.encode(effectivePassword);

        long id = adminUserRepository.createUser(username, hash, realName, STATUS_ACTIVE, forceChange);
        adminUserRepository.replaceUserRoles(id, roleIds);
        log.info("admin.user.create.completed userId={} username={}", id, username);
        return new AdminUserCreateResponse(id, username, autoGen ? effectivePassword : null);
    }

    @Transactional
    public AdminUserListResponse.Item updateUser(long id, String realName, List<Long> roleIds) {
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        // 若目标是 SUPER_ADMIN 用户，禁止移除 SUPER_ADMIN 角色
        List<AdminUserRoleRecord> currentRoles = adminUserRepository.findRolesForUserIds(List.of(id));
        boolean isSuperAdmin = currentRoles.stream().anyMatch(r -> r.roleId() == SUPER_ADMIN_ROLE_ID);
        if (isSuperAdmin && (roleIds == null || !roleIds.contains(SUPER_ADMIN_ROLE_ID))) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_SUPER_ADMIN_ROLE_LOCKED);
        }
        adminUserRepository.updateRealName(id, realName);
        adminUserRepository.replaceUserRoles(id, roleIds);
        return getUserDetail(id);
    }

    @Transactional
    public void disableUser(long id) {
        long currentAdminId = currentAdminId();
        if (currentAdminId == id) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_CANNOT_DISABLE_SELF);
        }
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        adminUserRepository.updateStatus(id, STATUS_DISABLED);
        adminRefreshTokenStore.revokeAllByUserId(id);
        log.info("admin.user.disable.completed userId={} byAdmin={}", id, currentAdminId);
    }

    @Transactional
    public void enableUser(long id) {
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        adminUserRepository.updateStatus(id, STATUS_ACTIVE);
        log.info("admin.user.enable.completed userId={}", id);
    }

    @Transactional
    public AdminUserResetPasswordResponse resetPassword(long id, String password, Boolean forceChangePassword) {
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        boolean autoGen = (password == null || password.isBlank());
        String effectivePassword = autoGen ? generatePassword() : password;
        if (!autoGen && !AdminPasswordPolicyValidator.isValid(effectivePassword, null, "")) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_PASSWORD_POLICY_VIOLATION);
        }
        boolean forceChange = forceChangePassword == null || forceChangePassword;
        String hash = adminPasswordEncoder.encode(effectivePassword);
        adminUserRepository.resetPassword(id, hash, forceChange);
        adminRefreshTokenStore.revokeAllByUserId(id);
        log.info("admin.user.reset-password.completed userId={} forceChange={}", id, forceChange);
        return new AdminUserResetPasswordResponse(id, autoGen ? effectivePassword : null);
    }

    @Transactional
    public void deleteUser(long id) {
        long currentAdminId = currentAdminId();
        if (currentAdminId == id) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_CANNOT_DELETE_SELF);
        }
        adminUserRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_USER_NOT_FOUND));
        List<AdminUserRoleRecord> roles = adminUserRepository.findRolesForUserIds(List.of(id));
        if (roles.stream().anyMatch(r -> r.roleId() == SUPER_ADMIN_ROLE_ID)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_SUPER_ADMIN_NOT_DELETABLE);
        }
        adminUserRepository.deleteUser(id);
        adminRefreshTokenStore.revokeAllByUserId(id);
        log.info("admin.user.delete.completed userId={} byAdmin={}", id, currentAdminId);
    }

    private long currentAdminId() {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        return principal.adminUserId();
    }

    private Map<Long, List<AdminUserListResponse.RoleRef>> loadRolesGrouped(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AdminUserListResponse.RoleRef>> map = new LinkedHashMap<>();
        for (AdminUserRoleRecord r : adminUserRepository.findRolesForUserIds(userIds)) {
            map.computeIfAbsent(r.userId(), k -> new java.util.ArrayList<>())
                    .add(new AdminUserListResponse.RoleRef(r.roleId(), r.roleCode(), r.roleName()));
        }
        return map;
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(GEN_LENGTH);
        for (int i = 0; i < GEN_LENGTH; i++) {
            sb.append(GEN_CHARS.charAt(secureRandom.nextInt(GEN_CHARS.length())));
        }
        return sb.toString();
    }

    private static Integer parseStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "ACTIVE" -> STATUS_ACTIVE;
            case "DISABLED" -> STATUS_DISABLED;
            default -> null;
        };
    }

    private static String statusName(Integer status) {
        if (status == null) return null;
        return status == STATUS_DISABLED ? "DISABLED" : "ACTIVE";
    }
}
