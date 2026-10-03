package com.falconx.console.security;

import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminPermissionRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 接口级 RBAC 鉴权服务。
 *
 * <p>由 {@link PermissionGuardAspect} 调用：检查当前 {@link AdminPrincipal} 是否具备指定权限码。
 *
 * <ul>
 *   <li>未登录（principal=null）→ {@link AdminErrorCode#ADMIN_TOKEN_INVALID}（90003）</li>
 *   <li>SUPER_ADMIN → 放行（不查 DB）</li>
 *   <li>普通管理员 → 查 {@code t_admin_role_permission} 并集，检查是否包含目标权限码</li>
 *   <li>未通过 → {@link AdminErrorCode#ADMIN_PERMISSION_DENIED}（90004）</li>
 * </ul>
 *
 * <p>实现注：每次调用都直接查 DB（角色权限调整后立即生效，TC-CONSOLE-041）；
 * 高负载场景下可在后续阶段加 Redis 短 TTL 缓存，本阶段不做。
 */
@Service
public class PermissionGuardService {

    private static final Logger log = LoggerFactory.getLogger(PermissionGuardService.class);

    private final AdminPermissionRepository adminPermissionRepository;

    public PermissionGuardService(AdminPermissionRepository adminPermissionRepository) {
        this.adminPermissionRepository = adminPermissionRepository;
    }

    /**
     * 强制要求当前管理员具备指定权限。
     *
     * @param requiredCode 权限码
     * @throws AdminBusinessException 未登录 → 90003；无权限 → 90004
     */
    public void requirePermission(String requiredCode) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        if (principal.isSuperAdmin()) {
            log.debug("admin.permission.granted reason=super_admin code={} userId={}",
                    requiredCode, principal.adminUserId());
            return;
        }
        Collection<String> permissions = adminPermissionRepository
                .findPermissionCodesByUserId(principal.adminUserId());
        Set<String> set = new HashSet<>(permissions);
        if (!set.contains(requiredCode)) {
            log.warn("admin.permission.denied userId={} code={} ownedCount={}",
                    principal.adminUserId(), requiredCode, set.size());
            throw new AdminBusinessException(AdminErrorCode.ADMIN_PERMISSION_DENIED);
        }
        log.debug("admin.permission.granted reason=role_permission code={} userId={}",
                requiredCode, principal.adminUserId());
    }
}
