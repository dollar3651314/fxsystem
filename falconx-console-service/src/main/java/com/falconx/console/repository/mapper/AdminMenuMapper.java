package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminMenuRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_admin_menu} MyBatis Mapper。
 *
 * <p>R9.6 仅支持读路径（GET /admin/me/menus）；R9.12 扩展为完整 CRUD + 排序交换，
 * 支撑 [`管理端接口规范`](../../../../../../../../../docs/api/管理端接口规范.md) §5.3。
 */
@Mapper
public interface AdminMenuMapper {

    /** 全部菜单按 parent_id, sort_order 排序（构建树用）。 */
    List<AdminMenuRecord> selectAllOrdered();

    /** 单菜单详情。 */
    AdminMenuRecord selectById(@Param("id") long id);

    /** UNIQUE 校验。 */
    AdminMenuRecord selectByCode(@Param("code") String code);

    /**
     * 同级（同一 parent_id）的相邻菜单（用于上/下移）。
     * direction=up 返回 sort_order 严格小于 cur 的最大者；
     * direction=down 返回 sort_order 严格大于 cur 的最小者；
     * 不存在则返回 NULL。
     */
    AdminMenuRecord selectSiblingNeighbor(@Param("parentId") Long parentId,
                                           @Param("currentSortOrder") int currentSortOrder,
                                           @Param("direction") String direction);

    /** 子菜单数（删除前检查）。 */
    long countChildrenByParentId(@Param("parentId") long parentId);

    int insertMenu(@Param("id") long id,
                   @Param("parentId") Long parentId,
                   @Param("code") String code,
                   @Param("name") String name,
                   @Param("icon") String icon,
                   @Param("path") String path,
                   @Param("permissionCode") String permissionCode,
                   @Param("sortOrder") int sortOrder,
                   @Param("isVisible") int isVisible);

    int updateMenu(@Param("id") long id,
                   @Param("parentId") Long parentId,
                   @Param("name") String name,
                   @Param("icon") String icon,
                   @Param("path") String path,
                   @Param("permissionCode") String permissionCode,
                   @Param("sortOrder") int sortOrder,
                   @Param("isVisible") int isVisible);

    int updateSortOrder(@Param("id") long id, @Param("sortOrder") int sortOrder);

    int deleteMenu(@Param("id") long id);
}
