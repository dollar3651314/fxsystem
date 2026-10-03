package com.falconx.console.repository;

import com.falconx.console.entity.AdminRole;
import com.falconx.console.entity.AdminRoleDetail;
import com.falconx.console.entity.AdminRoleListItem;
import com.falconx.console.repository.mapper.AdminRoleMapper;
import com.falconx.console.repository.mapper.record.AdminRoleDetailRecord;
import com.falconx.infrastructure.id.IdGenerator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminRoleRepository implements AdminRoleRepository {

    private final AdminRoleMapper adminRoleMapper;
    private final IdGenerator idGenerator;

    public MybatisAdminRoleRepository(AdminRoleMapper adminRoleMapper, IdGenerator idGenerator) {
        this.adminRoleMapper = adminRoleMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public List<AdminRole> findRolesByUserId(long userId) {
        return adminRoleMapper.selectRolesByUserId(userId).stream()
                .map(record -> new AdminRole(record.id(), record.code(), record.name()))
                .toList();
    }

    @Override
    public List<AdminRoleListItem> findRolesForList(String code, String name, Integer isSystem, int offset, int limit) {
        return adminRoleMapper.selectRolesForList(code, name, isSystem, offset, limit).stream()
                .map(r -> new AdminRoleListItem(
                        r.id(), r.code(), r.name(), r.description(),
                        r.isSystem() != null && r.isSystem() == 1,
                        r.memberCount(), r.permissionCount(), r.createdAt()))
                .toList();
    }

    @Override
    public long countRoles(String code, String name, Integer isSystem) {
        return adminRoleMapper.countRoles(code, name, isSystem);
    }

    @Override
    public Optional<AdminRoleDetail> findRoleById(long id) {
        return Optional.ofNullable(adminRoleMapper.selectRoleById(id)).map(this::toDetail);
    }

    @Override
    public Optional<AdminRoleDetail> findRoleByCode(String code) {
        return Optional.ofNullable(adminRoleMapper.selectRoleByCode(code)).map(this::toDetail);
    }

    @Override
    public long createRole(String code, String name, String description) {
        long id = idGenerator.nextId();
        adminRoleMapper.insertRole(id, code, name, description);
        return id;
    }

    @Override
    public void updateRole(long id, String name, String description) {
        adminRoleMapper.updateRole(id, name, description);
    }

    @Override
    public void deleteRole(long id) {
        adminRoleMapper.deleteRole(id);
    }

    @Override
    public long countMembersByRoleId(long roleId) {
        return adminRoleMapper.countMembersByRoleId(roleId);
    }

    @Override
    public List<String> findPermissionCodesByRoleId(long roleId) {
        return adminRoleMapper.selectPermissionCodesByRoleId(roleId);
    }

    @Override
    public void replacePermissions(long roleId, List<String> permissionCodes) {
        adminRoleMapper.deletePermissionsByRoleId(roleId);
        if (permissionCodes != null && !permissionCodes.isEmpty()) {
            adminRoleMapper.insertRolePermissions(roleId, permissionCodes);
        }
    }

    private AdminRoleDetail toDetail(AdminRoleDetailRecord r) {
        return new AdminRoleDetail(
                r.id(), r.code(), r.name(), r.description(),
                r.isSystem() != null && r.isSystem() == 1,
                r.createdAt());
    }
}
