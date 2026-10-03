package com.falconx.console.role;

import com.falconx.console.api.AdminRoleDetailResponse;
import com.falconx.console.api.AdminRoleListResponse;
import com.falconx.console.api.AdminRolePermissionsResponse;
import com.falconx.console.entity.AdminPermission;
import com.falconx.console.entity.AdminRoleDetail;
import com.falconx.console.entity.AdminRoleListItem;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.repository.AdminRoleRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 1 P3 角色管理 ApplicationService。
 *
 * <p>支撑 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.2 全部 6 端点：
 * 列表 / 新建 / 编辑 / 删除 / 查询权限 / 分配权限。
 *
 * <p>关键业务约束：
 * <ul>
 *   <li>{@code is_system=1}（SUPER_ADMIN 等）角色禁止编辑 / 删除 / 修改权限</li>
 *   <li>有成员的角色不可删除（90223）</li>
 *   <li>权限分配为全量替换（不是增量），传入的 permissionCode 必须全部在字典中（90225）</li>
 * </ul>
 */
@Service
public class AdminRoleApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleApplicationService.class);
    private static final long SUPER_ADMIN_ROLE_ID = 1L;

    private final AdminRoleRepository adminRoleRepository;
    private final AdminPermissionRepository adminPermissionRepository;

    public AdminRoleApplicationService(AdminRoleRepository adminRoleRepository,
                                        AdminPermissionRepository adminPermissionRepository) {
        this.adminRoleRepository = adminRoleRepository;
        this.adminPermissionRepository = adminPermissionRepository;
    }

    @Transactional(readOnly = true)
    public AdminRoleListResponse listRoles(String code, String name, Boolean isSystem, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        Integer isSystemFlag = isSystem == null ? null : (isSystem ? 1 : 0);

        List<AdminRoleListItem> records = adminRoleRepository.findRolesForList(code, name, isSystemFlag, offset, safeSize);
        long total = adminRoleRepository.countRoles(code, name, isSystemFlag);

        List<AdminRoleListResponse.Item> items = records.stream()
                .map(r -> new AdminRoleListResponse.Item(
                        r.id(), r.code(), r.name(), r.description(), r.isSystem(),
                        r.memberCount(), r.permissionCount(), r.createdAt()))
                .toList();
        return new AdminRoleListResponse(items, total, page, safeSize);
    }

    @Transactional
    public AdminRoleDetailResponse createRole(String code, String name, String description) {
        if (adminRoleRepository.findRoleByCode(code).isPresent()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_CODE_DUPLICATE);
        }
        long id = adminRoleRepository.createRole(code, name, description);
        AdminRoleDetail detail = adminRoleRepository.findRoleById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_NOT_FOUND));
        log.info("admin.role.create.completed roleId={} code={}", id, code);
        return toResponse(detail);
    }

    @Transactional
    public AdminRoleDetailResponse updateRole(long id, String name, String description) {
        AdminRoleDetail detail = adminRoleRepository.findRoleById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_NOT_FOUND));
        if (detail.isSystem()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_IS_SYSTEM_NOT_EDITABLE);
        }
        adminRoleRepository.updateRole(id, name, description);
        return toResponse(adminRoleRepository.findRoleById(id).orElseThrow());
    }

    @Transactional
    public void deleteRole(long id) {
        AdminRoleDetail detail = adminRoleRepository.findRoleById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_NOT_FOUND));
        if (detail.isSystem()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_IS_SYSTEM_NOT_EDITABLE);
        }
        if (adminRoleRepository.countMembersByRoleId(id) > 0) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_HAS_MEMBERS_CANNOT_DELETE);
        }
        adminRoleRepository.replacePermissions(id, List.of()); // 清掉权限关系
        adminRoleRepository.deleteRole(id);
        log.info("admin.role.delete.completed roleId={} code={}", id, detail.code());
    }

    @Transactional(readOnly = true)
    public AdminRolePermissionsResponse getPermissions(long roleId) {
        adminRoleRepository.findRoleById(roleId)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_NOT_FOUND));
        return new AdminRolePermissionsResponse(adminRoleRepository.findPermissionCodesByRoleId(roleId));
    }

    @Transactional
    public AdminRolePermissionsResponse assignPermissions(long roleId, List<String> permissionCodes) {
        if (roleId == SUPER_ADMIN_ROLE_ID) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_SUPER_ADMIN_PERMISSIONS_LOCKED);
        }
        adminRoleRepository.findRoleById(roleId)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_NOT_FOUND));

        List<String> distinctCodes = permissionCodes == null ? List.of()
                : permissionCodes.stream().distinct().toList();
        if (!distinctCodes.isEmpty()) {
            Set<String> dictionaryCodes = adminPermissionRepository.findAllPermissions().stream()
                    .map(AdminPermission::code)
                    .collect(Collectors.toSet());
            for (String code : distinctCodes) {
                if (!dictionaryCodes.contains(code)) {
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_ROLE_PERMISSION_CODE_NOT_FOUND);
                }
            }
        }
        adminRoleRepository.replacePermissions(roleId, distinctCodes);
        log.info("admin.role.assign-permissions.completed roleId={} count={}", roleId, distinctCodes.size());
        return new AdminRolePermissionsResponse(distinctCodes);
    }

    private AdminRoleDetailResponse toResponse(AdminRoleDetail d) {
        return new AdminRoleDetailResponse(d.id(), d.code(), d.name(), d.description(), d.isSystem(), d.createdAt());
    }
}
