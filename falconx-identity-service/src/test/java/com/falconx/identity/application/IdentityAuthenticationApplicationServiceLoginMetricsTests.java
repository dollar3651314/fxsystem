package com.falconx.identity.application;

import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.command.LoginIdentityUserCommand;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.repository.RefreshTokenSessionRepository;
import com.falconx.identity.service.IdentitySecurityPolicyService;
import com.falconx.identity.service.IdentityTokenBlacklistService;
import com.falconx.identity.service.IdentityTokenService;
import com.falconx.identity.service.PasswordHashService;
import com.falconx.identity.service.model.AuthTokenBundle;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * `IdentityAuthenticationApplicationService` 登录指标埋点单元测试（PROD-OPS-EVIDENCE-01 C4）。
 *
 * <p>该测试用真实 `SimpleMeterRegistry` 验证登录热路径埋点：
 *
 * <ul>
 *   <li>成功登录：`falconx.identity.login.total{outcome=success}` 计数 +1，且 duration timer 有记录</li>
 *   <li>失败登录：`falconx.identity.login.total{outcome=failure}` 计数 +1，且 duration timer 有记录</li>
 * </ul>
 *
 * <p>指标标签固定低基数（仅 `outcome=success|failure`），不含 email/token 等敏感值。
 */
class IdentityAuthenticationApplicationServiceLoginMetricsTests {

    private static final String LOGIN_TOTAL_METRIC = "falconx.identity.login.total";
    private static final String LOGIN_DURATION_METRIC = "falconx.identity.login.duration";
    private static final String OUTCOME_TAG = "outcome";

    private IdentityUserRepository identityUserRepository;
    private IdentitySecurityPolicyService identitySecurityPolicyService;
    private PasswordHashService passwordHashService;
    private IdentityTokenService identityTokenService;
    private IdentityTokenBlacklistService identityTokenBlacklistService;
    private RefreshTokenSessionRepository refreshTokenSessionRepository;
    private SimpleMeterRegistry meterRegistry;

    private IdentityAuthenticationApplicationService service;

    @BeforeEach
    void setUp() {
        identityUserRepository = mock(IdentityUserRepository.class);
        identitySecurityPolicyService = mock(IdentitySecurityPolicyService.class);
        passwordHashService = mock(PasswordHashService.class);
        identityTokenService = mock(IdentityTokenService.class);
        identityTokenBlacklistService = mock(IdentityTokenBlacklistService.class);
        refreshTokenSessionRepository = mock(RefreshTokenSessionRepository.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new IdentityAuthenticationApplicationService(
                identityUserRepository,
                identitySecurityPolicyService,
                passwordHashService,
                identityTokenService,
                identityTokenBlacklistService,
                refreshTokenSessionRepository,
                meterRegistry
        );
    }

    @Test
    void shouldRecordSuccessLoginMetricWhenCredentialsValid() {
        IdentityUser user = activeUser();
        when(identityUserRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordHashService.matches("secret", "hash")).thenReturn(true);
        when(passwordHashService.needsRehash("hash")).thenReturn(false);
        when(identityUserRepository.save(any(IdentityUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(identityTokenService.issueTokens(any(IdentityUser.class)))
                .thenReturn(new AuthTokenBundle("access", "refresh", 3600L, 7200L, "ACTIVE", true));

        service.login(new LoginIdentityUserCommand("user@example.com", "secret", "127.0.0.1"));

        Assertions.assertEquals(
                1.0,
                meterRegistry.counter(LOGIN_TOTAL_METRIC, OUTCOME_TAG, "success").count(),
                "成功登录应记一次 outcome=success 计数");
        Timer successTimer = meterRegistry.timer(LOGIN_DURATION_METRIC, OUTCOME_TAG, "success");
        Assertions.assertEquals(1L, successTimer.count(), "成功登录 duration timer 应有一次记录");
        // 失败计数应保持为 0（未误记）。
        Assertions.assertEquals(
                0.0,
                meterRegistry.counter(LOGIN_TOTAL_METRIC, OUTCOME_TAG, "failure").count(),
                "成功登录不应记 failure 计数");
    }

    @Test
    void shouldRecordFailureLoginMetricWhenCredentialsInvalid() {
        when(identityUserRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        Assertions.assertThrows(
                IdentityBusinessException.class,
                () -> service.login(new LoginIdentityUserCommand("user@example.com", "wrong", "127.0.0.1")),
                "凭证无效应抛出业务异常");

        Assertions.assertEquals(
                1.0,
                meterRegistry.counter(LOGIN_TOTAL_METRIC, OUTCOME_TAG, "failure").count(),
                "失败登录应记一次 outcome=failure 计数");
        Timer failureTimer = meterRegistry.timer(LOGIN_DURATION_METRIC, OUTCOME_TAG, "failure");
        Assertions.assertEquals(1L, failureTimer.count(), "失败登录 duration timer 应有一次记录");
        Assertions.assertEquals(
                0.0,
                meterRegistry.counter(LOGIN_TOTAL_METRIC, OUTCOME_TAG, "success").count(),
                "失败登录不应记 success 计数");
    }

    @Test
    void shouldNotFailWhenMeterRegistryIsNull() {
        IdentityAuthenticationApplicationService nullRegistryService = new IdentityAuthenticationApplicationService(
                identityUserRepository,
                identitySecurityPolicyService,
                passwordHashService,
                identityTokenService,
                identityTokenBlacklistService,
                refreshTokenSessionRepository,
                null
        );
        when(identityUserRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // registry 为 null 时埋点应被守卫跳过，不影响原有业务异常语义。
        Assertions.assertThrows(
                IdentityBusinessException.class,
                () -> nullRegistryService.login(new LoginIdentityUserCommand("user@example.com", "wrong", "127.0.0.1")));
    }

    private IdentityUser activeUser() {
        OffsetDateTime now = OffsetDateTime.now();
        return new IdentityUser(
                1L,
                "UID-1",
                "user@example.com",
                "hash",
                UserStatus.ACTIVE,
                "DEFAULT",
                true,
                now,
                now,
                now,
                now
        );
    }
}
