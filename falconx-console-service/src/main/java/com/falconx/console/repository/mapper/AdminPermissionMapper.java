package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminPermissionListRecord;
import com.falconx.console.repository.mapper.record.AdminPermissionRecord;
import com.falconx.console.repository.mapper.record.AdminPermissionRoleRecord;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_admin_permission} + {@code t_admin_role_permission} 复合 Mapper。
 *
 * <p>权限点字典由 R9.7 启动扫描 {@code @RequiresPermission} 注解写入；
 * 本 Mapper 仅承担读路径（GET /admin/me/permissions）。
 */
@Mapper
public interface AdminPermissionMapper {

    /**
     * 查询权限点字典全集（SUPER_ADMIN 使用）。
     *
     * @return 字典全集
     */
    List<AdminPermissionRecord> selectAllPermissions();

    /**
     * 按管理员 id 查询其权限码并集（去重）。
     *
     * <p>JOIN 路径：{@code t_admin_user_role → t_admin_role_permission → t_admin_permission}。
     *
     * @param userId 管理员主键
     * @return 权限码列表（去重）
     */
    List<String> selectPermissionCodesByUserId(@Param("userId") long userId);

    /**
     * 插入权限点字典记录（启动扫描使用）。
     *
     * <p>使用 {@code INSERT IGNORE}：code 已存在时跳过（保留已有 description；
     * 由运营在管理后台手动维护描述。一期不做自动覆盖描述）。
     *
     * @param id 雪花 ID
     * @param code 权限码
     * @param module 模块
     * @param action 动作
     * @param description 描述
     * @return 受影响行数（已存在时为 0）
     */
    int insertIgnore(@Param("id") long id,
                     @Param("code") String code,
                     @Param("module") String module,
                     @Param("action") String action,
                     @Param("description") String description);

    /**
     * P5 字典分页查询。
     *
     * @param module      模块筛选（NULL 不筛）
     * @param action      动作筛选（NULL 不筛）
     * @param highRiskCodes 高风险权限码集合（{@code highRiskOnly=true} 时传入静态注册表 codes，
     *                    NULL 表示不按高风险筛选）
     * @param offset      偏移
     * @param limit       每页大小
     * @return 行记录列表
     */
    List<AdminPermissionListRecord> selectPermissionsForList(@Param("module") String module,
                                                              @Param("action") String action,
                                                              @Param("highRiskCodes") Set<String> highRiskCodes,
                                                              @Param("offset") int offset,
                                                              @Param("limit") int limit);

    /**
     * P5 字典分页计数（与 {@link #selectPermissionsForList} 同筛选条件）。
     */
    long countPermissions(@Param("module") String module,
                          @Param("action") String action,
                          @Param("highRiskCodes") Set<String> highRiskCodes);

    /**
     * P5 关联角色查询（按权限码反向 JOIN）。
     *
     * @param permissionCode 权限码
     * @return 关联的角色列表（按 role_code 排序）
     */
    List<AdminPermissionRoleRecord> selectRolesByPermissionCode(@Param("permissionCode") String permissionCode);
}
