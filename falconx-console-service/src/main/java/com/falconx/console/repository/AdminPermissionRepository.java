package com.falconx.console.repository;

import com.falconx.console.entity.AdminPermission;
import com.falconx.console.entity.AdminPermissionListItem;
import com.falconx.console.entity.AdminPermissionRoleRef;
import java.util.List;
import java.util.Set;

/**
 * 管理后台权限点 Repository。
 */
public interface AdminPermissionRepository {

    /**
     * 加载权限点字典全集（SUPER_ADMIN 用）。
     *
     * @return 字典全集
     */
    List<AdminPermission> findAllPermissions();

    /**
     * 按管理员 id 加载其权限码并集。
     *
     * @param userId 管理员主键
     * @return 权限码列表（去重）
     */
    List<String> findPermissionCodesByUserId(long userId);

    /**
     * 插入权限点字典（启动扫描使用，已存在则跳过）。
     *
     * @param code 权限码
     * @param module 模块
     * @param action 动作
     * @param description 描述
     * @return 是否实际插入（{@code true} 表示新增；{@code false} 表示已存在）
     */
    boolean insertIfAbsent(String code, String module, String action, String description);

    /**
     * P5 字典分页查询。
     *
     * @param module 模块筛选（NULL 不筛）
     * @param action 动作筛选（NULL 不筛）
     * @param highRiskCodes 仅查这些权限码（NULL 不按高风险筛选）
     * @param offset 偏移
     * @param limit 每页大小
     * @return 列表项
     */
    List<AdminPermissionListItem> findPermissions(String module,
                                                   String action,
                                                   Set<String> highRiskCodes,
                                                   int offset,
                                                   int limit);

    /**
     * P5 字典分页计数（同筛选条件）。
     */
    long countPermissions(String module, String action, Set<String> highRiskCodes);

    /**
     * P5 关联角色查询。
     *
     * @param permissionCode 权限码
     * @return 关联的角色引用列表（可能为空，code 不存在时也返回空数组）
     */
    List<AdminPermissionRoleRef> findRolesByPermissionCode(String permissionCode);
}
