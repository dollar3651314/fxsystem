package com.falconx.console.security;

import com.falconx.console.config.ConsoleServiceProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理端密码 BCrypt 编码器。
 *
 * <p>包装 Spring Security 的 {@link BCryptPasswordEncoder}，
 * 使用 {@link ConsoleServiceProperties.Password#getBcryptStrength()} 配置 strength：
 *
 * <ul>
 *   <li>{@code prod} / {@code staging}：默认 strength=12（高安全性，单次哈希约 250ms）</li>
 *   <li>{@code dev}：strength=12（与 prod 一致，便于本地体验真实性能）</li>
 *   <li>{@code test}：strength=4（加速集成测试，单次哈希约 1ms）</li>
 * </ul>
 *
 * <p>本编码器仅用于管理员密码；C 端用户密码由 {@code falconx-identity-service} 的
 * {@code BCryptPasswordHashService} 独立管理，互不影响。
 */
@Component
public class AdminPasswordEncoder {

    private final BCryptPasswordEncoder delegate;

    public AdminPasswordEncoder(ConsoleServiceProperties properties) {
        this.delegate = new BCryptPasswordEncoder(properties.getPassword().getBcryptStrength());
    }

    /**
     * 用 BCrypt 加密明文密码。
     *
     * @param rawPassword 明文密码
     * @return BCrypt hash 字符串（{@code $2a$<strength>$<salt+hash>}）
     */
    public String encode(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    /**
     * 校验明文密码与 BCrypt hash 是否匹配。
     *
     * @param rawPassword 用户输入的明文密码
     * @param encodedPassword 存储在 {@code t_admin_user.password_hash} 的 BCrypt hash
     * @return true 表示匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        return delegate.matches(rawPassword, encodedPassword);
    }
}
