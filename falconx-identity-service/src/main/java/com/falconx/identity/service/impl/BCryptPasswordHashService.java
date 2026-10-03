package com.falconx.identity.service.impl;

import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.identity.service.PasswordHashService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 密码哈希服务。
 *
 * <p>该实现遵循安全规范中的密码要求，使用 bcrypt 对密码进行哈希。
 * 当前阶段只提供最小哈希与匹配能力，不承担复杂密码策略编排。
 */
public class BCryptPasswordHashService implements PasswordHashService {

    private final BCryptPasswordEncoder passwordEncoder;

    public BCryptPasswordHashService(IdentityServiceProperties properties) {
        this.passwordEncoder = new BCryptPasswordEncoder(properties.getPassword().getBcryptStrength());
    }

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return passwordEncoder.matches(rawPassword, passwordHash);
    }

    @Override
    public boolean needsRehash(String passwordHash) {
        return passwordEncoder.upgradeEncoding(passwordHash);
    }
}
