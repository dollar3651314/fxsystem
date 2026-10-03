package com.falconx.console.permission;

import com.falconx.console.api.AdminPermissionListResponse;
import com.falconx.console.entity.AdminPermissionListItem;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.security.HighRiskPermissionRegistry;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 1 P5 权限点字典 ApplicationService（只读）。
 *
 * <p>承担 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.4 两个端点的业务编排：
 * <ul>
 *   <li>{@code GET /admin/admin-permissions} 字典分页查询（支持 module / action / highRiskOnly 筛选）</li>
 *   <li>{@code GET /admin/admin-permissions/&#123;code&#125;/roles} 关联角色查询</li>
 * </ul>
 *
 * <p>{@code isHighRisk} 由 {@link HighRiskPermissionRegistry} 静态匹配补齐，不在 DB 字段中。
 */
@Service
public class AdminPermissionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminPermissionApplicationService.class);

    private final AdminPermissionRepository adminPermissionRepository;

    public AdminPermissionApplicationService(AdminPermissionRepository adminPermissionRepository) {
        this.adminPermissionRepository = adminPermissionRepository;
    }

    @Transactional(readOnly = true)
    public AdminPermissionListResponse listPermissions(String module,
                                                        String action,
                                                        boolean highRiskOnly,
                                                        int page,
                                                        int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * safeSize;
        Set<String> highRiskCodes = highRiskOnly ? HighRiskPermissionRegistry.CODES : null;

        List<AdminPermissionListItem> records = adminPermissionRepository.findPermissions(
                module, action, highRiskCodes, offset, safeSize);
        long total = adminPermissionRepository.countPermissions(module, action, highRiskCodes);

        List<AdminPermissionListResponse.Item> items = records.stream()
                .map(r -> new AdminPermissionListResponse.Item(
                        r.code(),
                        r.module(),
                        r.action(),
                        r.description(),
                        HighRiskPermissionRegistry.CODES.contains(r.code()),
                        r.roleCount(),
                        r.createdAt()))
                .toList();
        log.info("admin.permissions.list.completed total={} returned={}", total, items.size());
        return new AdminPermissionListResponse(items, total, page, safeSize);
    }

    @Transactional(readOnly = true)
    public List<AdminPermissionListResponse.RoleItem> getRolesByPermissionCode(String permissionCode) {
        return adminPermissionRepository.findRolesByPermissionCode(permissionCode).stream()
                .map(r -> new AdminPermissionListResponse.RoleItem(r.roleId(), r.roleCode(), r.roleName()))
                .toList();
    }
}
