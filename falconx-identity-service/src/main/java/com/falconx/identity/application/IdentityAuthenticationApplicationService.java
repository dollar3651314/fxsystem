package com.falconx.identity.application;

import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.command.LoginIdentityUserCommand;
import com.falconx.identity.command.RefreshIdentityTokenCommand;
import com.falconx.identity.contract.auth.AuthTokenResponse;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.repository.RefreshTokenSessionRepository;
import com.falconx.identity.service.IdentitySecurityPolicyService;
import com.falconx.identity.service.IdentityTokenBlacklistService;
import com.falconx.identity.service.IdentityTokenService;
import com.falconx.identity.service.PasswordHashService;
import com.falconx.identity.service.model.AuthTokenBundle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务。
 *
 * <p>该服务负责把登录和刷新请求串成最小认证链路：
 *
 * <ol>
 *   <li>查询用户并校验密码</li>
 *   <li>检查用户状态限制</li>
 *   <li>签发或轮换 token</li>
 *   <li>记录最近登录时间</li>
 * </ol>
 */
@Service
public class IdentityAuthenticationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAuthenticationApplicationService.class);

    // PROD-OPS-EVIDENCE-01 C4：登录热路径指标名 / 标签（固定低基数，仅 success|failure，不含 PII）。
    private static final String LOGIN_TOTAL_METRIC = "falconx.identity.login.total";
    private static final String LOGIN_DURATION_METRIC = "falconx.identity.login.duration";
    private static final String OUTCOME_TAG = "outcome";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    private final IdentityUserRepository identityUserRepository;
    private final IdentitySecurityPolicyService identitySecurityPolicyService;
    private final PasswordHashService passwordHashService;
    private final IdentityTokenService identityTokenService;
    private final IdentityTokenBlacklistService identityTokenBlacklistService;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    // 登录链路指标采集器；测试或未装配 actuator 时可能为 null，记录前统一判空守卫。
    private final MeterRegistry meterRegistry;

    public IdentityAuthenticationApplicationService(IdentityUserRepository identityUserRepository,
                                                    IdentitySecurityPolicyService identitySecurityPolicyService,
                                                    PasswordHashService passwordHashService,
                                                    IdentityTokenService identityTokenService,
                                                    IdentityTokenBlacklistService identityTokenBlacklistService,
                                                    RefreshTokenSessionRepository refreshTokenSessionRepository,
                                                    MeterRegistry meterRegistry) {
        this.identityUserRepository = identityUserRepository;
        this.identitySecurityPolicyService = identitySecurityPolicyService;
        this.passwordHashService = passwordHashService;
        this.identityTokenService = identityTokenService;
        this.identityTokenBlacklistService = identityTokenBlacklistService;
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行邮箱密码登录。
     *
     * @param command 登录命令
     * @return token 结果
     */
    @Transactional
    public AuthTokenResponse login(LoginIdentityUserCommand command) {
        // PROD-OPS-EVIDENCE-01 C4：登录热路径埋点。入口计时，出口按结果记 success/failure；
        // 任何异常路径（凭证错误/账号冻结/封禁/安全策略拦截等）统一归 failure，不改业务逻辑与错误码。
        long startedAtNanos = System.nanoTime();
        String outcome = OUTCOME_FAILURE;
        try {
            AuthTokenResponse response = doLogin(command);
            outcome = OUTCOME_SUCCESS;
            return response;
        } finally {
            recordLoginMetrics(outcome, startedAtNanos);
        }
    }

    private AuthTokenResponse doLogin(LoginIdentityUserCommand command) {
        String normalizedEmail = command.email().trim().toLowerCase(Locale.ROOT);
        String maskedEmail = maskEmail(normalizedEmail);
        identitySecurityPolicyService.ensureLoginAllowed(command.clientIp());
        log.info("identity.login.received email={} clientIp={}", maskedEmail, command.clientIp());

        IdentityUser user = identityUserRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null || !passwordHashService.matches(command.password(), user.passwordHash())) {
            identitySecurityPolicyService.recordLoginFailure(command.clientIp());
            log.warn("identity.login.rejected email={} clientIp={} reason=invalid_credentials",
                    maskedEmail,
                    command.clientIp());
            throw new IdentityBusinessException(IdentityErrorCode.INVALID_CREDENTIALS);
        }
        if (user.status() == UserStatus.PENDING_DEPOSIT) {
            user = identityUserRepository.save(new IdentityUser(
                    user.id(),
                    user.uid(),
                    user.email(),
                    user.passwordHash(),
                    UserStatus.ACTIVE,
                    user.groupCode(),
                    user.emailVerified(),
                    user.activatedAt() != null ? user.activatedAt() : OffsetDateTime.now(),
                    user.lastLoginAt(),
                    user.createdAt(),
                    OffsetDateTime.now()
            ));
            log.info("identity.login.legacy_status_normalized userId={} fromStatus={} toStatus={}",
                    user.id(),
                    UserStatus.PENDING_DEPOSIT,
                    UserStatus.ACTIVE);
        }
        if (user.status() == UserStatus.FROZEN) {
            log.warn("identity.login.rejected email={} clientIp={} reason=user_frozen",
                    maskedEmail,
                    command.clientIp());
            throw new IdentityBusinessException(IdentityErrorCode.USER_FROZEN);
        }
        if (user.status() == UserStatus.BANNED) {
            log.warn("identity.login.rejected email={} clientIp={} reason=user_banned",
                    maskedEmail,
                    command.clientIp());
            throw new IdentityBusinessException(IdentityErrorCode.USER_BANNED);
        }
        identitySecurityPolicyService.clearLoginFailures(command.clientIp());

        IdentityUser passwordUpgradedUser = user;
        if (passwordHashService.needsRehash(user.passwordHash())) {
            passwordUpgradedUser = identityUserRepository.save(new IdentityUser(
                    user.id(),
                    user.uid(),
                    user.email(),
                    passwordHashService.hash(command.password()),
                    user.status(),
                    user.groupCode(),
                    user.emailVerified(),
                    user.activatedAt(),
                    user.lastLoginAt(),
                    user.createdAt(),
                    OffsetDateTime.now()
            ));
        }

        IdentityUser updatedUser = identityUserRepository.save(new IdentityUser(
                passwordUpgradedUser.id(),
                passwordUpgradedUser.uid(),
                passwordUpgradedUser.email(),
                passwordUpgradedUser.passwordHash(),
                passwordUpgradedUser.status(),
                passwordUpgradedUser.groupCode(),
                passwordUpgradedUser.emailVerified(),
                passwordUpgradedUser.activatedAt(),
                OffsetDateTime.now(),
                passwordUpgradedUser.createdAt(),
                OffsetDateTime.now()
        ));
        AuthTokenBundle tokenBundle = identityTokenService.issueTokens(updatedUser);
        log.info("identity.login.completed userId={} status={} clientIp={}",
                updatedUser.id(),
                updatedUser.status(),
                command.clientIp());
        return toResponse(tokenBundle);
    }

    /**
     * 使用 Refresh Token 刷新 token 对。
     *
     * @param command 刷新命令
     * @return 新 token 结果
     */
    @Transactional
    public AuthTokenResponse refresh(RefreshIdentityTokenCommand command) {
        log.info("identity.refresh.request");
        AuthTokenBundle tokenBundle = identityTokenService.refresh(command.refreshToken());
        log.info("identity.refresh.completed userStatus={}", tokenBundle.userStatus());
        return toResponse(tokenBundle);
    }

    /**
     * 吊销当前 Access Token。
     *
     * <p>当前阶段只把当前 Access Token 的 `jti` 写入黑名单，
     * 不扩展 Refresh Token 主动撤销语义。
     *
     * @param accessToken 当前 Bearer Access Token 文本
     */
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IdentityBusinessException(IdentityErrorCode.UNAUTHORIZED);
        }
        IdentityTokenService.ValidatedAccessToken tokenDetails =
                identityTokenService.parseAndValidateAccessToken(accessToken);
        log.info("identity.logout.request userId={} jti={}", tokenDetails.userId(), tokenDetails.jti());
        identityTokenBlacklistService.blacklistToken(tokenDetails.jti(), tokenDetails.remainingTtl());
        // STAGE-12 安全：之前 logout 只把 access token jti 黑名单，refresh token 仍可用最多 72h。
        // 攻击者拿到 refresh token 后用户 logout 不能阻止其换新 access。
        // 现在 logout 同时撤销该用户所有 refresh session（多端同步登出语义，符合用户预期）。
        int revokedRefresh = refreshTokenSessionRepository.revokeAllByUserId(Long.parseLong(tokenDetails.userId()));
        log.info("identity.logout.completed userId={} jti={} ttlSeconds={} revokedRefreshSessions={}",
                tokenDetails.userId(),
                tokenDetails.jti(),
                tokenDetails.remainingTtl().toSeconds(),
                revokedRefresh);
    }

    /**
     * 记录一次登录处理的计数与耗时指标。
     *
     * <p>仅 `outcome=success|failure` 一个固定低基数标签，
     * 不写入 email/username/token/password 等敏感值（§3.13.5）。
     *
     * @param outcome 登录结果，`success` 或 `failure`
     * @param startedAtNanos 登录处理起始 `System.nanoTime()`
     */
    private void recordLoginMetrics(String outcome, long startedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(LOGIN_TOTAL_METRIC, OUTCOME_TAG, outcome).increment();
        meterRegistry.timer(LOGIN_DURATION_METRIC, OUTCOME_TAG, outcome)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private AuthTokenResponse toResponse(AuthTokenBundle tokenBundle) {
        return new AuthTokenResponse(
                tokenBundle.accessToken(),
                tokenBundle.refreshToken(),
                tokenBundle.accessTokenExpiresIn(),
                tokenBundle.refreshTokenExpiresIn(),
                tokenBundle.userStatus(),
                tokenBundle.emailVerified()
        );
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(0, atIndex));
        }
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
}
