package com.falconx.console.repository;

import com.falconx.console.entity.AdminRole;
import com.falconx.console.entity.AdminRoleDetail;
import com.falconx.console.entity.AdminRoleListItem;
import java.util.List;
import java.util.Optional;

/**
 * 管理后台角色 Repository。
 *
 * <p>R9.5 仅支持读路径；R9.11（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.2）
 * 扩展为完整 CRUD + 角色权限关系维护。
 */
public interface AdminRoleRepository {

    /** 按管理员 ID 加载已绑定的角色集合（登录链路）。 */
    List<AdminRole> findRolesByUserId(long userId);

    /** P3 列表分页查询。 */
    List<AdminRoleListItem> findRolesForList(String code, String name, Integer isSystem, int offset, int limit);

    /** P3 列表计数。 */
    long countRoles(String code, String name, Integer isSystem);

    /** P3 详情。 */
    Optional<AdminRoleDetail> findRoleById(long id);

    /** UNIQUE 校验。 */
    Optional<AdminRoleDetail> findRoleByCode(String code);

    /** 新建角色，返回生成的 id。 */
    long createRole(String code, String name, String description);

    /** 编辑角色（code 不可改）。 */
    void updateRole(long id, String name, String description);

    /** 物理删除。 */
    void deleteRole(long id);

    /** 角色成员数。 */
    long countMembersByRoleId(long roleId);

    /** 查询角色已分配的权限码集合。 */
    List<String> findPermissionCodesByRoleId(long roleId);

    /** 全量替换角色权限关系（先 DELETE 再 INSERT）。 */
    void replacePermissions(long roleId, List<String> permissionCodes);
}
