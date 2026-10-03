package com.falconx.console.repository;

import com.falconx.console.repository.mapper.record.AdminMenuRecord;
import java.util.List;
import java.util.Optional;

/**
 * 管理后台菜单 Repository。
 *
 * <p>R9.6 仅支持读路径；R9.12 扩展为完整 CRUD + 排序交换，支撑
 * [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.3。
 */
public interface AdminMenuRepository {

    /** 全部菜单（构建树用）。 */
    List<AdminMenuRecord> findAllOrdered();

    /** 单菜单详情。 */
    Optional<AdminMenuRecord> findById(long id);

    /** UNIQUE 校验。 */
    Optional<AdminMenuRecord> findByCode(String code);

    /** 同级相邻菜单（上/下移）。 */
    Optional<AdminMenuRecord> findSiblingNeighbor(Long parentId, int currentSortOrder, String direction);

    /** 子菜单数。 */
    long countChildrenByParentId(long parentId);

    /** 新建菜单。 */
    long createMenu(Long parentId, String code, String name, String icon, String path,
                    String permissionCode, int sortOrder, boolean visible);

    /** 编辑菜单（不含 sort_order）。 */
    void updateMenu(long id, Long parentId, String name, String icon, String path,
                    String permissionCode, int sortOrder, boolean visible);

    /** 单独更新 sort_order（排序交换专用）。 */
    void updateSortOrder(long id, int sortOrder);

    /** 物理删除。 */
    void deleteMenu(long id);
}
