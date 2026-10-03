package com.falconx.identity.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.application.IdentityAuthenticationApplicationService;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.LoginIdentityUserCommand;
import com.falconx.identity.command.RefreshIdentityTokenCommand;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.contract.auth.AuthTokenResponse;
import com.falconx.identity.contract.auth.LoginRequest;
import com.falconx.identity.contract.auth.RefreshTokenRequest;
import com.falconx.identity.contract.auth.RegisterRequest;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份认证控制器。
 *
 * <p>该控制器对外暴露 Stage 3A 已落地的最小身份接口：
 *
 * <ul>
 *   <li>注册</li>
 *   <li>登录</li>
 *   <li>刷新 token</li>
 * </ul>
 *
 * <p>当前阶段仍是 identity-service 直出接口，后续在 Stage 4 会由 gateway 统一接入。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final IdentityRegistrationApplicationService identityRegistrationApplicationService;
    private final IdentityAuthenticationApplicationService identityAuthenticationApplicationService;

    public AuthController(IdentityRegistrationApplicationService identityRegistrationApplicationService,
                          IdentityAuthenticationApplicationService identityAuthenticationApplicationService) {
        this.identityRegistrationApplicationService = identityRegistrationApplicationService;
        this.identityAuthenticationApplicationService = identityAuthenticationApplicationService;
    }

    /**
     * 注册接口。
     *
     * @param request 注册请求
     * @return 注册结果
     */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                  HttpServletRequest servletRequest) {
        log.info("identity.http.register.received");
        RegisterResponse response = identityRegistrationApplicationService.register(
                new RegisterIdentityUserCommand(
                        request.email(), request.password(), resolveClientIp(servletRequest),
                        request.firstName(), request.middleName(), request.lastName(),
                        request.birthDate(), request.nationality())
        );
        return success(response);
    }

    /**
     * 登录接口。
     *
     * @param request 登录请求
     * @return token 结果
     */
    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest servletRequest) {
        log.info("identity.http.login.received");
        AuthTokenResponse response = identityAuthenticationApplicationService.login(
                new LoginIdentityUserCommand(request.email(), request.password(), resolveClientIp(servletRequest))
        );
        return success(response);
    }

    /**
     * Refresh Token 刷新接口。
     *
     * @param request 刷新请求
     * @return 新 token 结果
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("identity.http.refresh.received");
        AuthTokenResponse response = identityAuthenticationApplicationService.refresh(
                new RefreshIdentityTokenCommand(request.refreshToken())
        );
        return success(response);
    }

    /**
     * 当前 Access Token 登出接口。
     *
     * <p>该接口只把当前 Access Token 吊销进黑名单，
     * 不扩展 Refresh Token 主动撤销语义。
     *
     * @param authorizationHeader Bearer Access Token
     * @return 通用成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        log.info("identity.http.logout.received");
        identityAuthenticationApplicationService.logout(extractBearerToken(authorizationHeader));
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "0",
                "success",
                data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedClientIp = request.getHeader("X-Client-Ip");
        if (forwardedClientIp != null && !forwardedClientIp.isBlank()) {
            return forwardedClientIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IdentityBusinessException(IdentityErrorCode.UNAUTHORIZED);
        }
        String accessToken = authorizationHeader.substring("Bearer ".length()).trim();
        if (accessToken.isBlank()) {
            throw new IdentityBusinessException(IdentityErrorCode.UNAUTHORIZED);
        }
        return accessToken;
    }
}
