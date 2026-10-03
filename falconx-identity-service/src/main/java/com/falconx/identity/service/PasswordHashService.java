package com.falconx.identity.service;

/**
 * 密码哈希服务抽象。
 *
 * <p>该服务负责封装密码哈希与匹配逻辑，
 * 让应用层不直接依赖具体加密实现。
 */
public interface PasswordHashService {

    /**
     * 对明文密码做哈希。
     *
     * @param rawPassword 明文密码
     * @return 哈希值
     */
    String hash(String rawPassword);

    /**
     * 校验明文密码是否与哈希匹配。
     *
     * @param rawPassword 明文密码
     * @param passwordHash 哈希值
     * @return 是否匹配
     */
    boolean matches(String rawPassword, String passwordHash);

    /**
     * 判断现有哈希是否应按当前配置重新编码。
     *
     * @param passwordHash 现有密码哈希
     * @return `true` 表示建议升级哈希强度
     */
    boolean needsRehash(String passwordHash);
}
