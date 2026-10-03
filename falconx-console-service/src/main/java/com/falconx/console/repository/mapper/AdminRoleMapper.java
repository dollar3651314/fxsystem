package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminRoleDetailRecord;
import com.falconx.console.repository.mapper.record.AdminRoleListRecord;
import com.falconx.console.repository.mapper.record.AdminRoleRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_admin_role} MyBatis Mapper。
 *
 * <p>R9.5 仅有读路径（按 user_id 加载已绑定角色）；R9.11 新增完整 CRUD + 角色 ↔ 权限关系维护，
 * 支撑 [`管理端接口规范`](../../../../../../../../../docs/api/管理端接口规范.md) §5.2 全部 6 端点。
 */
@Mapper
public interface AdminRoleMapper {

    /** 按管理员 ID 加载已绑定的角色集合。 */
    List<AdminRoleRecord> selectRolesByUserId(@Param("userId") long userId);

    /** P3 列表分页查询。 */
    List<AdminRoleListRecord> selectRolesForList(@Param("code") String code,
                                                  @Param("name") String name,
                                                  @Param("isSystem") Integer isSystem,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);

    /** P3 列表计数。 */
    long countRoles(@Param("code") String code,
                    @Param("name") String name,
                    @Param("isSystem") Integer isSystem);

    /** P3 详情。 */
    AdminRoleDetailRecord selectRoleById(@Param("id") long id);

    /** UNIQUE 校验。 */
    AdminRoleDetailRecord selectRoleByCode(@Param("code") String code);

    /** 新建角色。 */
    int insertRole(@Param("id") long id,
                   @Param("code") String code,
                   @Param("name") String name,
                   @Param("description") String description);

    /** 编辑角色（code 不可改；is_system=1 角色禁止编辑由 service 层拦截）。 */
    int updateRole(@Param("id") long id,
                   @Param("name") String name,
                   @Param("description") String description);

    /** 物理删除。 */
    int deleteRole(@Param("id") long id);

    /** 角色成员数（删除前检查 + 列表 memberCount 派生用）。 */
    long countMembersByRoleId(@Param("roleId") long roleId);

    /** 查询角色已分配的权限码集合。 */
    List<String> selectPermissionCodesByRoleId(@Param("roleId") long roleId);

    /** 全量替换前清空角色权限关系。 */
    int deletePermissionsByRoleId(@Param("roleId") long roleId);

    /** 批量插入角色权限关系（{@code permissionCodes} 由 service 层去重）。 */
    int insertRolePermissions(@Param("roleId") long roleId,
                               @Param("permissionCodes") List<String> permissionCodes);
}
