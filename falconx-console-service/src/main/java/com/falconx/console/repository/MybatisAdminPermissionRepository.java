package com.falconx.console.repository;

import com.falconx.console.entity.AdminPermission;
import com.falconx.console.entity.AdminPermissionListItem;
import com.falconx.console.entity.AdminPermissionRoleRef;
import com.falconx.console.repository.mapper.AdminPermissionMapper;
import com.falconx.infrastructure.id.IdGenerator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminPermissionRepository implements AdminPermissionRepository {

    private final AdminPermissionMapper adminPermissionMapper;
    private final IdGenerator idGenerator;

    public MybatisAdminPermissionRepository(AdminPermissionMapper adminPermissionMapper,
                                            IdGenerator idGenerator) {
        this.adminPermissionMapper = adminPermissionMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public List<AdminPermission> findAllPermissions() {
        return adminPermissionMapper.selectAllPermissions().stream()
                .map(record -> new AdminPermission(record.code(), record.module(), record.action(), record.description()))
                .toList();
    }

    @Override
    public List<String> findPermissionCodesByUserId(long userId) {
        return adminPermissionMapper.selectPermissionCodesByUserId(userId);
    }

    @Override
    public boolean insertIfAbsent(String code, String module, String action, String description) {
        long id = idGenerator.nextId();
        return adminPermissionMapper.insertIgnore(id, code, module, action, description) > 0;
    }

    @Override
    public List<AdminPermissionListItem> findPermissions(String module,
                                                          String action,
                                                          Set<String> highRiskCodes,
                                                          int offset,
                                                          int limit) {
        return adminPermissionMapper.selectPermissionsForList(module, action, highRiskCodes, offset, limit).stream()
                .map(r -> new AdminPermissionListItem(
                        r.code(), r.module(), r.action(), r.description(), r.createdAt(), r.roleCount()))
                .toList();
    }

    @Override
    public long countPermissions(String module, String action, Set<String> highRiskCodes) {
        return adminPermissionMapper.countPermissions(module, action, highRiskCodes);
    }

    @Override
    public List<AdminPermissionRoleRef> findRolesByPermissionCode(String permissionCode) {
        return adminPermissionMapper.selectRolesByPermissionCode(permissionCode).stream()
                .map(r -> new AdminPermissionRoleRef(r.roleId(), r.roleCode(), r.roleName()))
                .toList();
    }
}
