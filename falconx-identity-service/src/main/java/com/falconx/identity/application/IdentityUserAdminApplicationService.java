package com.falconx.identity.application;

import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.api.AdminFreezeResponse;
import com.falconx.identity.api.AdminProfilePatchRequest;
import com.falconx.identity.api.AdminUserPatchRequest;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.IdentityUserProfileRepository;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.repository.KycRepository;
import com.falconx.identity.repository.RefreshTokenSessionRepository;
import com.falconx.identity.service.IsoCountryCodes;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员对客户的状态机操作（freeze / unfreeze）编排（STAGE-2-CUSTOMER）。
 *
 * <p>由 {@code AdminInternalUserController} 调用，通过 internal RPC 链路执行：
 *
 * <ol>
 *   <li>校验客户存在 + 当前状态允许迁移（按 {@code 状态机规范} §2）</li>
 *   <li>更新 {@code t_user.status}</li>
 *   <li>撤销该客户所有 refresh token（{@code RefreshTokenSessionRepository.revokeAllByUserId}）</li>
 *   <li>access token 黑名单：一期接受现有 access token 30 分钟内仍可用作为已知限制（用户级撤销时间戳推到后续阶段）</li>
 * </ol>
 */
@Service
public class IdentityUserAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityUserAdminApplicationService.class);

    private final IdentityUserRepository identityUserRepository;
    private final IdentityUserProfileRepository identityUserProfileRepository;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final KycRepository kycRepository;

    public IdentityUserAdminApplicationService(IdentityUserRepository identityUserRepository,
                                               IdentityUserProfileRepository identityUserProfileRepository,
                                               RefreshTokenSessionRepository refreshTokenSessionRepository,
                                               KycRepository kycRepository) {
        this.identityUserRepository = identityUserRepository;
        this.identityUserProfileRepository = identityUserProfileRepository;
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
        this.kycRepository = kycRepository;
    }

    /**
     * 管理端 patch t_user 元数据（email / emailVerified / groupCode / kycLevel / status）。
     *
     * <p>所有字段允许 null，COALESCE 保留原值；status 改为 FROZEN / 非 ACTIVE 时同步撤销 refresh token。
     *
     * @throws IdentityBusinessException 10012 用户不存在 / 10023 nationality 非 ISO（仅 profile patch 用）
     */
    @Transactional
    public void updateUserByAdmin(long userId, long adminUserId, AdminUserPatchRequest request) {
        IdentityUser before = loadOrThrow(userId);

        Integer statusCode = request.status() == null ? null : UserStatus.valueOf(request.status()).ordinal();
        Integer emailVerified = request.emailVerified() == null ? null : (request.emailVerified() ? 1 : 0);

        int affected = identityUserRepository.updateAdminFields(
                userId,
                request.email(),
                emailVerified,
                request.groupCode(),
                request.kycLevel(),
                statusCode);
        if (affected == 0) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_NOT_FOUND);
        }
        // kycLevel ≥ 1 时同步把用户最新 PENDING submission 标 APPROVED，
        // 避免 t_user.kyc_level=1 但 GET /api/v1/me/kyc 仍返回 PENDING 的状态错配
        if (request.kycLevel() != null && request.kycLevel() >= 1) {
            kycRepository.findLatestByUserId(userId).ifPresent(latest -> {
                if (latest.status() == KycSubmissionStatus.PENDING) {
                    boolean updated = kycRepository.updateReview(
                            latest.id(),
                            KycSubmissionStatus.APPROVED,
                            adminUserId,
                            OffsetDateTime.now(ZoneOffset.UTC),
                            null
                    );
                    log.info("identity.admin.user-patch.kyc-submission-auto-approve userId={} submissionId={} updated={}",
                            userId, latest.id(), updated);
                }
            });
        }
        // status 改为非 ACTIVE 时同步吊销 refresh token，强制重新登录
        if (request.status() != null && !"ACTIVE".equals(request.status())
                && before.status() == UserStatus.ACTIVE) {
            int revoked = refreshTokenSessionRepository.revokeAllByUserId(userId);
            log.info("identity.admin.user-patch.tokens-revoked userId={} adminUserId={} newStatus={} revoked={}",
                    userId, adminUserId, request.status(), revoked);
        }
        log.info("identity.admin.user-patch.completed userId={} adminUserId={} fields=[email={} verified={} group={} kyc={} status={}]",
                userId, adminUserId,
                request.email() != null, request.emailVerified() != null,
                request.groupCode() != null, request.kycLevel() != null, request.status() != null);
    }

    /**
     * 管理端 patch t_user_profile（绕过 profile_verified 锁，所有字段 COALESCE）。
     *
     * @throws IdentityBusinessException 10020 profile 不存在 / 10023 nationality / residenceCountry 非 ISO
     */
    @Transactional
    public void updateProfileByAdmin(long userId, long adminUserId, AdminProfilePatchRequest request) {
        if (request.nationality() != null && !IsoCountryCodes.isValid(request.nationality())) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_COUNTRY_INVALID);
        }
        if (request.residenceCountry() != null && !IsoCountryCodes.isValid(request.residenceCountry())) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_COUNTRY_INVALID);
        }
        int affected = identityUserProfileRepository.updateProfileByAdmin(userId,
                request.firstName(), request.middleName(), request.lastName(),
                request.birthDate(), request.nationality(),
                request.gender(),
                request.residenceCountry(), request.residenceState(), request.residenceCity(),
                request.residenceAddress(), request.residencePostalCode(),
                request.phoneCountryCode(), request.phoneNumber(),
                request.languagePreference(), request.timezone());
        if (affected == 0) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_NOT_FOUND);
        }
        log.info("identity.admin.profile-patch.completed userId={} adminUserId={}", userId, adminUserId);
    }

    /**
     * 冻结客户。
     *
     * @param userId 客户主键
     * @param adminUserId 操作管理员（仅日志）
     * @param reason 操作原因
     * @return 状态迁移结果
     * @throws IdentityBusinessException 10012 用户不存在 / 10013 已 FROZEN / 10014 终态 BANNED
     */
    @Transactional
    public AdminFreezeResponse freeze(long userId, long adminUserId, String reason) {
        IdentityUser user = loadOrThrow(userId);
        UserStatus currentStatus = user.status();

        if (currentStatus == UserStatus.BANNED) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_TERMINAL_STATUS);
        }
        if (currentStatus == UserStatus.FROZEN) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_ALREADY_FROZEN);
        }
        // ACTIVE / PENDING_DEPOSIT 都允许迁移到 FROZEN（PENDING_DEPOSIT 是历史兼容状态）

        IdentityUser updated = saveWithStatus(user, UserStatus.FROZEN);
        int revoked = refreshTokenSessionRepository.revokeAllByUserId(userId);

        log.info("identity.admin.freeze.completed userId={} adminUserId={} previousStatus={} revokedRefreshTokens={} reasonLength={}",
                userId, adminUserId, currentStatus, revoked, reason == null ? 0 : reason.length());

        return new AdminFreezeResponse(userId, currentStatus.name(), updated.status().name());
    }

    /**
     * STAGE-7-WITHDRAW：查询用户 KYC 等级（供 trading-core 出金前置校验）。
     *
     * @param userId 用户主键
     * @return KYC 状态响应（kycLevel: 0 未认证 / 1 已通过）
     * @throws IdentityBusinessException 10012 用户不存在
     */
    public com.falconx.identity.api.KycStatusResponse getKycStatus(long userId) {
        Integer level = identityUserRepository.findKycLevelByUserId(userId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.USER_NOT_FOUND));
        return new com.falconx.identity.api.KycStatusResponse(userId, level);
    }

    /**
     * 解冻客户。
     *
     * @param userId 客户主键
     * @param adminUserId 操作管理员
     * @param reason 操作原因
     * @return 状态迁移结果
     * @throws IdentityBusinessException 10012 用户不存在 / 10014 终态 BANNED / 10015 当前不是 FROZEN
     */
    @Transactional
    public AdminFreezeResponse unfreeze(long userId, long adminUserId, String reason) {
        IdentityUser user = loadOrThrow(userId);
        UserStatus currentStatus = user.status();

        if (currentStatus == UserStatus.BANNED) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_TERMINAL_STATUS);
        }
        if (currentStatus != UserStatus.FROZEN) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_NOT_FROZEN);
        }

        IdentityUser updated = saveWithStatus(user, UserStatus.ACTIVE);

        log.info("identity.admin.unfreeze.completed userId={} adminUserId={} reasonLength={}",
                userId, adminUserId, reason == null ? 0 : reason.length());

        return new AdminFreezeResponse(userId, currentStatus.name(), updated.status().name());
    }

    private IdentityUser loadOrThrow(long userId) {
        return identityUserRepository.findById(userId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.USER_NOT_FOUND));
    }

    private IdentityUser saveWithStatus(IdentityUser user, UserStatus newStatus) {
        OffsetDateTime now = OffsetDateTime.now();
        IdentityUser updated = new IdentityUser(
                user.id(),
                user.uid(),
                user.email(),
                user.passwordHash(),
                newStatus,
                user.groupCode(),
                user.emailVerified(),
                user.activatedAt(),
                user.lastLoginAt(),
                user.createdAt(),
                now
        );
        return identityUserRepository.save(updated);
    }
}
