package com.falconx.console.bootstrap;

import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.security.AdminPasswordEncoder;
import com.falconx.infrastructure.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 默认超管启动初始化器。
 *
 * <p>console-service 启动时执行：
 *
 * <ol>
 *   <li>检查 {@code t_admin_user} 是否存在 {@code username = falconx.console.initial-super-admin.username} 的记录</li>
 *   <li>若不存在，使用 BCrypt 加密 {@code falconx.console.initial-super-admin.default-password}，
 *       插入 {@code t_admin_user}（{@code must_change_password=1} 强制首次登录改密）</li>
 *   <li>绑定 {@code SUPER_ADMIN} 角色（{@code role_id=1}，由 V1 init schema 写入）</li>
 *   <li>若已存在则跳过（幂等）</li>
 * </ol>
 *
 * <p>本类替代 V1 schema 中的占位 INSERT 语句，避免在 SQL 中硬编码假 BCrypt hash 导致用户无法登录。
 *
 * <p>多实例部署时由于 {@code t_admin_user.username} 唯一索引保护，并发 INSERT 也只会成功一次；
 * 失败实例捕获 {@link DataIntegrityViolationException} 后跳过。
 */
@Component
public class DefaultSuperAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultSuperAdminInitializer.class);
    private static final long SUPER_ADMIN_ROLE_ID = 1L;

    private final ConsoleServiceProperties properties;
    private final AdminPasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;

    public DefaultSuperAdminInitializer(ConsoleServiceProperties properties,
                                        AdminPasswordEncoder passwordEncoder,
                                        IdGenerator idGenerator,
                                        JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ConsoleServiceProperties.InitialSuperAdmin config = properties.getInitialSuperAdmin();
        String username = config.getUsername();

        Long existingId = queryExistingUserId(username);
        if (existingId != null) {
            log.info("admin.bootstrap.super-admin.skip username={} existingId={} reason=already_exists",
                    username, existingId);
            return;
        }

        long userId = idGenerator.nextId();
        String passwordHash = passwordEncoder.encode(config.getDefaultPassword());

        try {
            jdbcTemplate.update(
                    "INSERT INTO t_admin_user (id, username, password_hash, real_name, status, must_change_password) "
                            + "VALUES (?, ?, ?, ?, 1, 1)",
                    userId, username, passwordHash, config.getRealName());
            jdbcTemplate.update(
                    "INSERT INTO t_admin_user_role (user_id, role_id) VALUES (?, ?)",
                    userId, SUPER_ADMIN_ROLE_ID);
            log.info("admin.bootstrap.super-admin.created username={} userId={} mustChangePassword=1",
                    username, userId);
        } catch (DataIntegrityViolationException ex) {
            log.warn("admin.bootstrap.super-admin.race username={} reason=concurrent_insert_detected message={}",
                    username, ex.getMessage());
        }
    }

    private Long queryExistingUserId(String username) {
        return jdbcTemplate.query(
                "SELECT id FROM t_admin_user WHERE username = ? LIMIT 1",
                rs -> rs.next() ? rs.getLong("id") : null,
                username
        );
    }
}
