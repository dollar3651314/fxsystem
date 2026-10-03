package com.falconx.console.application;

import com.falconx.console.api.AdminMeMenusResponse;
import com.falconx.console.api.AdminMeMenusResponse.MenuNode;
import com.falconx.console.api.AdminMePermissionsResponse;
import com.falconx.console.api.AdminMeResponse;
import com.falconx.console.api.AdminMeResponse.RoleSummary;
import com.falconx.console.entity.AdminPermission;
import com.falconx.console.entity.AdminRole;
import com.falconx.console.entity.AdminUser;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminMenuRepository;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.repository.AdminRoleRepository;
import com.falconx.console.repository.AdminUserRepository;
import com.falconx.console.repository.mapper.record.AdminMenuRecord;
import com.falconx.console.security.AdminPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端「当前管理员」相关查询编排（GET /admin/me + /me/permissions + /me/menus）。
 */
@Service
public class AdminMeApplicationService {

    private static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    private final AdminUserRepository adminUserRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final AdminPermissionRepository adminPermissionRepository;
    private final AdminMenuRepository adminMenuRepository;

    public AdminMeApplicationService(AdminUserRepository adminUserRepository,
                                     AdminRoleRepository adminRoleRepository,
                                     AdminPermissionRepository adminPermissionRepository,
                                     AdminMenuRepository adminMenuRepository) {
        this.adminUserRepository = adminUserRepository;
        this.adminRoleRepository = adminRoleRepository;
        this.adminPermissionRepository = adminPermissionRepository;
        this.adminMenuRepository = adminMenuRepository;
    }

    /**
     * 加载当前管理员基本信息。
     *
     * @param principal 当前已认证管理员
     * @return 响应
     */
    @Transactional(readOnly = true)
    public AdminMeResponse loadCurrentAdmin(AdminPrincipal principal) {
        AdminUser user = adminUserRepository.findById(principal.adminUserId())
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID));
        List<AdminRole> roles = adminRoleRepository.findRolesByUserId(user.id());
        List<RoleSummary> roleSummaries = roles.stream()
                .map(role -> new RoleSummary(role.code(), role.name()))
                .toList();
        return new AdminMeResponse(
                user.id(),
                user.username(),
                user.realName(),
                roleSummaries,
                user.mustChangePassword(),
                user.lastLoginAt(),
                user.lastLoginIp()
        );
    }

    /**
     * 加载当前管理员的权限点集合。
     *
     * <p>SUPER_ADMIN 返回字典全集；普通角色返回角色权限并集。
     *
     * @param principal 当前已认证管理员
     * @return 响应
     */
    @Transactional(readOnly = true)
    public AdminMePermissionsResponse loadCurrentPermissions(AdminPrincipal principal) {
        if (principal.isSuperAdmin()) {
            List<String> all = adminPermissionRepository.findAllPermissions().stream()
                    .map(AdminPermission::code)
                    .toList();
            return new AdminMePermissionsResponse(all, true);
        }
        List<String> codes = adminPermissionRepository.findPermissionCodesByUserId(principal.adminUserId());
        return new AdminMePermissionsResponse(codes, false);
    }

    /**
     * 加载当前管理员可见的菜单树。
     *
     * <p>过滤规则（按 [`管理端 5 页面方案`](../../../../../../../../docs/design/falconx-console-pages-V1.md) §1 + R6 TC-CONSOLE-033~037）：
     *
     * <ol>
     *   <li>{@code is_visible=0} 节点不返回</li>
     *   <li>{@code permission_code} 不为空且当前管理员无该权限的节点不返回</li>
     *   <li>父节点权限符合但所有子节点无权限 → 父节点也不返回（避免空目录）</li>
     *   <li>同级按 {@code sort_order} 升序</li>
     * </ol>
     *
     * @param principal 当前已认证管理员
     * @return 菜单树
     */
    @Transactional(readOnly = true)
    public AdminMeMenusResponse loadCurrentMenus(AdminPrincipal principal) {
        List<AdminMenuRecord> all = adminMenuRepository.findAllOrdered();
        if (all.isEmpty()) {
            return new AdminMeMenusResponse(List.of());
        }

        boolean superAdmin = principal.isSuperAdmin();
        List<String> permissionCodes = superAdmin
                ? adminPermissionRepository.findAllPermissions().stream().map(AdminPermission::code).toList()
                : adminPermissionRepository.findPermissionCodesByUserId(principal.adminUserId());
        java.util.Set<String> permissionCodeSet = new java.util.HashSet<>(permissionCodes);

        // 按 parentId 分组
        Map<Long, List<AdminMenuRecord>> grouped = new HashMap<>();
        for (AdminMenuRecord record : all) {
            grouped.computeIfAbsent(record.parentId(), k -> new ArrayList<>()).add(record);
        }

        List<MenuNode> roots = buildChildren(grouped, null, permissionCodeSet);
        return new AdminMeMenusResponse(roots);
    }

    private List<MenuNode> buildChildren(Map<Long, List<AdminMenuRecord>> grouped,
                                         Long parentId,
                                         java.util.Set<String> permissionCodes) {
        List<AdminMenuRecord> records = grouped.getOrDefault(parentId, List.of());
        List<MenuNode> result = new ArrayList<>();
        for (AdminMenuRecord record : records) {
            if (record.isVisible() == null || record.isVisible() != 1) {
                continue;
            }
            String permissionCode = record.permissionCode();
            boolean leafPermissionMatch = permissionCode == null
                    || permissionCode.isBlank()
                    || permissionCodes.contains(permissionCode);
            List<MenuNode> children = buildChildren(grouped, record.id(), permissionCodes);
            if (!leafPermissionMatch && children.isEmpty()) {
                continue;
            }
            // 父节点权限符合但子节点全部无权限 → 隐藏（避免空目录）。
            // 父节点本身路径为空且无 permission_code（典型分组目录）时，必须有子节点才保留。
            boolean isPureGroupParent = (record.path() == null || record.path().isBlank())
                    && (permissionCode == null || permissionCode.isBlank());
            if (isPureGroupParent && children.isEmpty()) {
                continue;
            }
            result.add(new MenuNode(
                    record.code(),
                    record.name(),
                    record.icon(),
                    record.path(),
                    record.permissionCode(),
                    children
            ));
        }
        return result;
    }
}
