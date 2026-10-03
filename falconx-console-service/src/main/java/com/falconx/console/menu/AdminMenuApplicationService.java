package com.falconx.console.menu;

import com.falconx.console.api.AdminMenuDetailResponse;
import com.falconx.console.api.AdminMenuListResponse;
import com.falconx.console.entity.AdminPermission;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminMenuRepository;
import com.falconx.console.repository.AdminPermissionRepository;
import com.falconx.console.repository.mapper.record.AdminMenuRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 1 P4 菜单管理 ApplicationService。
 *
 * <p>支撑 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.3 全部 5 端点：
 * 树形/扁平查询 / 新建 / 编辑 / 排序 / 删除。
 *
 * <p>关键业务约束：
 * <ul>
 *   <li>{@code permissionCode} 必填且必须存在于权限点字典（{@code 90233}）</li>
 *   <li>code UNIQUE（{@code 90230}）</li>
 *   <li>有子菜单的父菜单不可删除（{@code 90232}）</li>
 *   <li>排序到达同级首/尾时不可继续上/下移（{@code 90234}）</li>
 * </ul>
 */
@Service
public class AdminMenuApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminMenuApplicationService.class);

    private final AdminMenuRepository adminMenuRepository;
    private final AdminPermissionRepository adminPermissionRepository;

    public AdminMenuApplicationService(AdminMenuRepository adminMenuRepository,
                                        AdminPermissionRepository adminPermissionRepository) {
        this.adminMenuRepository = adminMenuRepository;
        this.adminPermissionRepository = adminPermissionRepository;
    }

    @Transactional(readOnly = true)
    public AdminMenuListResponse listMenus(boolean asTree) {
        List<AdminMenuRecord> records = adminMenuRepository.findAllOrdered();
        if (!asTree) {
            return new AdminMenuListResponse(records.stream().map(this::toFlatItem).toList());
        }
        return new AdminMenuListResponse(buildTree(records));
    }

    @Transactional
    public AdminMenuDetailResponse createMenu(Long parentId, String code, String name, String icon,
                                                String path, String permissionCode,
                                                int sortOrder, boolean visible) {
        if (adminMenuRepository.findByCode(code).isPresent()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_MENU_CODE_DUPLICATE);
        }
        verifyPermissionCodeExists(permissionCode);
        long id = adminMenuRepository.createMenu(parentId, code, name, icon, path, permissionCode, sortOrder, visible);
        log.info("admin.menu.create.completed menuId={} code={}", id, code);
        return toDetailResponse(adminMenuRepository.findById(id).orElseThrow());
    }

    @Transactional
    public AdminMenuDetailResponse updateMenu(long id, Long parentId, String name, String icon,
                                                String path, String permissionCode,
                                                int sortOrder, boolean visible) {
        adminMenuRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_MENU_NOT_FOUND));
        verifyPermissionCodeExists(permissionCode);
        adminMenuRepository.updateMenu(id, parentId, name, icon, path, permissionCode, sortOrder, visible);
        return toDetailResponse(adminMenuRepository.findById(id).orElseThrow());
    }

    @Transactional
    public AdminMenuDetailResponse moveMenu(long id, String direction) {
        AdminMenuRecord current = adminMenuRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_MENU_NOT_FOUND));
        AdminMenuRecord neighbor = adminMenuRepository
                .findSiblingNeighbor(current.parentId(), current.sortOrder(), direction)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_MENU_SORT_BOUNDARY));
        adminMenuRepository.updateSortOrder(current.id(), neighbor.sortOrder());
        adminMenuRepository.updateSortOrder(neighbor.id(), current.sortOrder());
        log.info("admin.menu.sort.completed menuId={} direction={} swappedWith={}", id, direction, neighbor.id());
        return toDetailResponse(adminMenuRepository.findById(id).orElseThrow());
    }

    @Transactional
    public void deleteMenu(long id) {
        adminMenuRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_MENU_NOT_FOUND));
        if (adminMenuRepository.countChildrenByParentId(id) > 0) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_MENU_HAS_CHILDREN_CANNOT_DELETE);
        }
        adminMenuRepository.deleteMenu(id);
        log.info("admin.menu.delete.completed menuId={}", id);
    }

    private void verifyPermissionCodeExists(String permissionCode) {
        Set<String> dictionary = adminPermissionRepository.findAllPermissions().stream()
                .map(AdminPermission::code).collect(Collectors.toSet());
        if (!dictionary.contains(permissionCode)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_MENU_PERMISSION_CODE_NOT_FOUND);
        }
    }

    private List<AdminMenuListResponse.Item> buildTree(List<AdminMenuRecord> records) {
        Map<Long, List<AdminMenuListResponse.Item>> childMap = new HashMap<>();
        Map<Long, AdminMenuListResponse.Item> idMap = new HashMap<>();
        for (AdminMenuRecord r : records) {
            List<AdminMenuListResponse.Item> children = new ArrayList<>();
            AdminMenuListResponse.Item item = new AdminMenuListResponse.Item(
                    r.id(), r.parentId(), r.code(), r.name(), r.icon(), r.path(),
                    r.permissionCode(), r.sortOrder() == null ? 0 : r.sortOrder(),
                    r.isVisible() != null && r.isVisible() == 1,
                    children);
            idMap.put(r.id(), item);
            if (r.parentId() != null) {
                childMap.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(item);
            }
        }
        List<AdminMenuListResponse.Item> roots = new ArrayList<>();
        for (AdminMenuRecord r : records) {
            AdminMenuListResponse.Item item = idMap.get(r.id());
            List<AdminMenuListResponse.Item> myChildren = childMap.getOrDefault(r.id(), List.of());
            // children 已经是 item 持有的 ArrayList 引用，append 即可
            item.children().addAll(myChildren);
            if (r.parentId() == null) {
                roots.add(item);
            }
        }
        return roots;
    }

    private AdminMenuListResponse.Item toFlatItem(AdminMenuRecord r) {
        return new AdminMenuListResponse.Item(
                r.id(), r.parentId(), r.code(), r.name(), r.icon(), r.path(),
                r.permissionCode(), r.sortOrder() == null ? 0 : r.sortOrder(),
                r.isVisible() != null && r.isVisible() == 1,
                List.of());
    }

    private AdminMenuDetailResponse toDetailResponse(AdminMenuRecord r) {
        return new AdminMenuDetailResponse(
                r.id(), r.parentId(), r.code(), r.name(), r.icon(), r.path(),
                r.permissionCode(), r.sortOrder() == null ? 0 : r.sortOrder(),
                r.isVisible() != null && r.isVisible() == 1);
    }
}
