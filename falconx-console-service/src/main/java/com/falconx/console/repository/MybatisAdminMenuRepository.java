package com.falconx.console.repository;

import com.falconx.console.repository.mapper.AdminMenuMapper;
import com.falconx.console.repository.mapper.record.AdminMenuRecord;
import com.falconx.infrastructure.id.IdGenerator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminMenuRepository implements AdminMenuRepository {

    private final AdminMenuMapper adminMenuMapper;
    private final IdGenerator idGenerator;

    public MybatisAdminMenuRepository(AdminMenuMapper adminMenuMapper, IdGenerator idGenerator) {
        this.adminMenuMapper = adminMenuMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public List<AdminMenuRecord> findAllOrdered() {
        return adminMenuMapper.selectAllOrdered();
    }

    @Override
    public Optional<AdminMenuRecord> findById(long id) {
        return Optional.ofNullable(adminMenuMapper.selectById(id));
    }

    @Override
    public Optional<AdminMenuRecord> findByCode(String code) {
        return Optional.ofNullable(adminMenuMapper.selectByCode(code));
    }

    @Override
    public Optional<AdminMenuRecord> findSiblingNeighbor(Long parentId, int currentSortOrder, String direction) {
        return Optional.ofNullable(adminMenuMapper.selectSiblingNeighbor(parentId, currentSortOrder, direction));
    }

    @Override
    public long countChildrenByParentId(long parentId) {
        return adminMenuMapper.countChildrenByParentId(parentId);
    }

    @Override
    public long createMenu(Long parentId, String code, String name, String icon, String path,
                            String permissionCode, int sortOrder, boolean visible) {
        long id = idGenerator.nextId();
        adminMenuMapper.insertMenu(id, parentId, code, name, icon, path, permissionCode,
                sortOrder, visible ? 1 : 0);
        return id;
    }

    @Override
    public void updateMenu(long id, Long parentId, String name, String icon, String path,
                            String permissionCode, int sortOrder, boolean visible) {
        adminMenuMapper.updateMenu(id, parentId, name, icon, path, permissionCode,
                sortOrder, visible ? 1 : 0);
    }

    @Override
    public void updateSortOrder(long id, int sortOrder) {
        adminMenuMapper.updateSortOrder(id, sortOrder);
    }

    @Override
    public void deleteMenu(long id) {
        adminMenuMapper.deleteMenu(id);
    }
}
