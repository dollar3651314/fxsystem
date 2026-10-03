package com.falconx.console.repository.mapper.record;

/**
 * {@code t_admin_role} 行记录（最小集，仅含 id / code / name，足够鉴权与 me 接口使用）。
 *
 * @param id 角色主键
 * @param code 角色 code
 * @param name 角色显示名
 */
public record AdminRoleRecord(Long id, String code, String name) {
}
