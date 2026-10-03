package com.falconx.identity.service;

import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.service.model.AuthTokenBundle;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 身份令牌服务抽象。
 *
 * <p>该服务负责 Access Token / Refresh Token 的签发与轮换，
 * 当前阶段由 identity-service 自己持有签发私钥。
 */
public interface IdentityTokenService {

    /**
     * 为指定用户签发新的令牌对。
     *
     * @param user 用户对象
     * @return 令牌对
     */
    AuthTokenBundle issueTokens(IdentityUser user);

    /**
     * 使用 Refresh Token 换取新的令牌对。
     *
     * @param refreshToken Refresh Token
     * @return 新令牌对
     */
    AuthTokenBundle refresh(String refreshToken);

    /**
     * 解析并校验当前 Access Token。
     *
     * <p>该能力用于需要基于当前 Access Token 执行业务动作的 owner 场景，
     * 例如把当前令牌加入黑名单。
     *
     * @param accessToken Access Token 文本
     * @return 已校验的最小 Access Token 信息
     */
    ValidatedAccessToken parseAndValidateAccessToken(String accessToken);

    /**
     * 已校验 Access Token 的最小信息。
     *
     * @param userId 用户主键
     * @param jti Token 唯一标识
     * @param expiresAt 过期时间
     * @param remainingTtl 当前剩余 TTL
     */
    record ValidatedAccessToken(
            String userId,
            String jti,
            OffsetDateTime expiresAt,
            Duration remainingTtl
    ) {
    }
}
