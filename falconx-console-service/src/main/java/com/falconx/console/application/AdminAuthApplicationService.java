package com.falconx.console.application;

import com.falconx.console.api.AdminAuthTokenResponse;
import com.falconx.console.entity.AdminRole;
import com.falconx.console.entity.AdminUser;
import com.falconx.console.entity.AdminUserStatus;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminRoleRepository;
import com.falconx.console.repository.AdminUserRepository;
import com.falconx.console.security.AdminLoginAttemptService;
import com.falconx.console.security.AdminPasswordEncoder;
import com.falconx.console.security.AdminPasswordPolicyValidator;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminRefreshTokenStore;
import com.falconx.console.security.AdminTokenBlacklistService;
import com.falconx.console.security.AdminTokenSupport;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
import com.falconx.console.security.AdminTokenSupport.ValidatedRefreshToken;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员鉴权链路编排。
 *
 * <p>承载 4 个鉴权接口的业务编排：
 *
 * <ul>
 *   <li>{@link #login(String, String, String)} - 登录 + 失败计数 + 锁定 + 颁发 token</li>
 *   <li>{@link #refresh(String)} - refresh token 一次性轮换</li>
 *   <li>{@link #logout(AdminPrincipal)} - access jti 黑名单 + 当前 refresh 吊销</li>
 *   <li>{@link #changePassword(AdminPrincipal, String, String)} - 改密 + 全设备 token 失效</li>
 * </ul>
 */
@Service
public class AdminAuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthApplicationService.class);

    private final AdminUserRepository adminUserRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final AdminPasswordEncoder passwordEncoder;
    private final AdminTokenSupport tokenSupport;
    private final AdminTokenBlacklistService tokenBlacklistService;
    private final AdminRefreshTokenStore refreshTokenStore;
    private final AdminLoginAttemptService loginAttemptService;

    public AdminAuthApplicationService(AdminUserRepository adminUserRepository,
                                       AdminRoleRepository adminRoleRepository,
                                       AdminPasswordEncoder passwordEncoder,
                                       AdminTokenSupport tokenSupport,
                                       AdminTokenBlacklistService tokenBlacklistService,
                                       AdminRefreshTokenStore refreshTokenStore,
                                       AdminLoginAttemptService loginAttemptService) {
        this.adminUserRepository = adminUserRepository;
        this.adminRoleRepository = adminRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenSupport = tokenSupport;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenStore = refreshTokenStore;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * 登录。
     *
     * @param username 登录用户名
     * @param password 明文密码
     * @param clientIp 客户端 IP（用于审计 last_login_ip）
     * @return 登录成功响应
     * @throws AdminBusinessException 失败时抛 90001 / 90006 / 90008
     */
    @Transactional
    public AdminAuthTokenResponse login(String username, String password, String clientIp) {
        log.info("admin.auth.login.received username={} ip={}", username, clientIp);

        if (loginAttemptService.isLocked(username)) {
            log.warn("admin.auth.login.rejected.locked username={}", username);
            throw new AdminBusinessException(AdminErrorCode.ADMIN_LOGIN_LOCKED);
        }

        AdminUser user = adminUserRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            boolean nowLocked = loginAttemptService.recordFailureAndCheckLock(username);
            if (nowLocked) {
                throw new AdminBusinessException(AdminErrorCode.ADMIN_LOGIN_LOCKED);
            }
            throw new AdminBusinessException(AdminErrorCode.ADMIN_AUTH_FAILED);
        }

        if (user.status() == AdminUserStatus.DISABLED) {
            log.warn("admin.auth.login.rejected.disabled username={} userId={}", username, user.id());
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_DISABLED);
        }

        loginAttemptService.clearFailures(username);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        adminUserRepository.updateLastLogin(user.id(), now, clientIp);

        return issueAndRespond(user);
    }

    /**
     * 刷新 token（一次性轮换：旧 refresh 立即失效）。
     *
     * @param refreshToken 旧 refresh token
     * @return 新登录响应
     * @throws AdminBusinessException 失败时抛 90002 / 90003
     */
    @Transactional
    public AdminAuthTokenResponse refresh(String refreshToken) {
        ValidatedRefreshToken validated = tokenSupport.parseAndVerifyRefreshToken(refreshToken);
        if (!refreshTokenStore.isActive(validated.jti())) {
            log.warn("admin.auth.refresh.rejected.inactive userId={} jti={}",
                    validated.adminUserId(), validated.jti());
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }

        AdminUser user = adminUserRepository.findById(validated.adminUserId())
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID));
        if (user.status() == AdminUserStatus.DISABLED) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_USER_DISABLED);
        }

        // 一次性消费旧 refresh
        refreshTokenStore.consume(user.id(), validated.jti());

        return issueAndRespond(user);
    }

    /**
     * 登出。仅吊销当前 access jti + 当前 refresh 是否吊销由调用方决定（本实现吊销当前用户全部 refresh，
     * 与 [`管理端 5 页面方案`](../../../../../../../../docs/design/falconx-console-pages-V1.md) §1.2 一致）。
     *
     * @param principal 当前已认证管理员（来自 filter 注入）
     */
    public void logout(AdminPrincipal principal) {
        log.info("admin.auth.logout.received userId={} jti={}", principal.adminUserId(), principal.jti());
        tokenBlacklistService.blacklistAccessToken(principal.jti(), principal.remainingTtl());
        refreshTokenStore.revokeAllByUserId(principal.adminUserId());
    }

    /**
     * 修改密码。
     *
     * <p>成功后：
     * <ol>
     *   <li>{@code password_hash} 更新为新 BCrypt</li>
     *   <li>{@code must_change_password} 清零</li>
     *   <li>当前 access jti 加入黑名单</li>
     *   <li>该用户全部 refresh 吊销</li>
     * </ol>
     *
     * @param principal 当前已认证管理员
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @throws AdminBusinessException 失败时抛 90001（旧密码错误）或专用密码策略错误码（待 R2 后续分配 90100+）
     */
    @Transactional
    public void changePassword(AdminPrincipal principal, String oldPassword, String newPassword) {
        log.info("admin.auth.change-password.received userId={}", principal.adminUserId());

        AdminUser user = adminUserRepository.findById(principal.adminUserId())
                .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID));

        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            log.warn("admin.auth.change-password.rejected.old-password-invalid userId={}", principal.adminUserId());
            throw new AdminBusinessException(AdminErrorCode.ADMIN_AUTH_FAILED);
        }

        if (!AdminPasswordPolicyValidator.isValid(newPassword, oldPassword, user.username())) {
            log.warn("admin.auth.change-password.rejected.policy userId={}", principal.adminUserId());
            // 临时复用 90001 作为策略不符响应（专用 90100 ADMIN_PASSWORD_POLICY_VIOLATION 待 R2 第二轮分配）。
            throw new AdminBusinessException(AdminErrorCode.ADMIN_AUTH_FAILED);
        }

        adminUserRepository.updatePassword(user.id(), passwordEncoder.encode(newPassword));
        tokenBlacklistService.blacklistAccessToken(principal.jti(), principal.remainingTtl());
        refreshTokenStore.revokeAllByUserId(user.id());

        log.info("admin.auth.change-password.completed userId={}", user.id());
    }

    private AdminAuthTokenResponse issueAndRespond(AdminUser user) {
        List<AdminRole> roles = adminRoleRepository.findRolesByUserId(user.id());
        List<String> roleCodes = roles.stream().map(AdminRole::code).toList();

        IssuedToken access = tokenSupport.issueAccessToken(
                user.id(), user.username(), roleCodes, user.mustChangePassword());
        IssuedToken refresh = tokenSupport.issueRefreshToken(user.id());

        Duration refreshTtl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), refresh.expiresAt());
        refreshTokenStore.register(user.id(), refresh.jti(), refreshTtl);

        log.info("admin.auth.tokens.issued userId={} accessJti={} refreshJti={}",
                user.id(), access.jti(), refresh.jti());

        return new AdminAuthTokenResponse(
                user.id(),
                user.username(),
                user.realName(),
                roleCodes,
                user.mustChangePassword(),
                access.token(),
                refresh.token(),
                access.ttlSeconds(),
                refresh.ttlSeconds()
        );
    }
}
