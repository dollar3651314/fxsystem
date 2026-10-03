package com.falconx.identity.application;

import com.falconx.identity.contract.profile.UpdateProfileRequest;
import com.falconx.identity.contract.profile.UserProfileResponse;
import com.falconx.identity.entity.IdentityUserProfile;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.repository.IdentityUserProfileRepository;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.service.IsoCountryCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户基础资料 ApplicationService（STAGE-1B-USER-PROFILE）。
 *
 * <p>支撑 {@code GET /api/v1/me/profile} + {@code PUT /api/v1/me/profile}：
 *
 * <ul>
 *   <li>5 强制字段更新受 {@code profile_verified=1} 锁定（10022）</li>
 *   <li>nationality / residenceCountry 必须在 ISO 3166-1 alpha-3 字典内（10023）</li>
 *   <li>可选字段任何时候可改</li>
 * </ul>
 */
@Service
public class IdentityProfileApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityProfileApplicationService.class);

    private final IdentityUserProfileRepository profileRepository;
    private final IdentityUserRepository identityUserRepository;

    public IdentityProfileApplicationService(IdentityUserProfileRepository profileRepository,
                                              IdentityUserRepository identityUserRepository) {
        this.profileRepository = profileRepository;
        this.identityUserRepository = identityUserRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(long userId) {
        IdentityUserProfile p = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_NOT_FOUND));
        return toResponse(p);
    }

    @Transactional
    public UserProfileResponse updateProfile(long userId, UpdateProfileRequest request) {
        IdentityUserProfile current = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_NOT_FOUND));

        boolean mandatoryRequested = request.firstName() != null || request.middleName() != null
                || request.lastName() != null || request.birthDate() != null || request.nationality() != null;

        if (mandatoryRequested) {
            if (current.profileVerified()) {
                throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_VERIFIED_LOCKED);
            }
            // kyc_level ≥ 1 也视为已锁定（admin 直接 patch kyc_level 跳过正常 KYC 流程时
            // profile_verified 可能仍是 0，但用户事实上已经认证过，不能再改 5 强制字段）
            int kycLevel = identityUserRepository.findKycLevelByUserId(userId).orElse(0);
            if (kycLevel >= 1) {
                throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_VERIFIED_LOCKED);
            }
            String firstName = orDefault(request.firstName(), current.firstName());
            String middleName = request.middleName() != null ? request.middleName() : current.middleName();
            String lastName = orDefault(request.lastName(), current.lastName());
            var birthDate = request.birthDate() != null ? request.birthDate() : current.birthDate();
            String nationality = orDefault(request.nationality(), current.nationality());
            if (!IsoCountryCodes.isValid(nationality)) {
                throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_COUNTRY_INVALID);
            }
            int affected = profileRepository.updateMandatoryFields(
                    userId, firstName, middleName, lastName, birthDate, nationality);
            if (affected == 0) {
                // 并发：在我们读取后被 KYC 流程置 verified=1
                throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_VERIFIED_LOCKED);
            }
        }

        if (request.residenceCountry() != null && !IsoCountryCodes.isValid(request.residenceCountry())) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_COUNTRY_INVALID);
        }

        // 可选字段总是 UPDATE（COALESCE 保留原值，传 null 即不改）
        profileRepository.updateOptionalFields(
                userId,
                request.gender() != null ? request.gender() : current.gender(),
                request.residenceCountry() != null ? request.residenceCountry() : current.residenceCountry(),
                request.residenceState() != null ? request.residenceState() : current.residenceState(),
                request.residenceCity() != null ? request.residenceCity() : current.residenceCity(),
                request.residenceAddress() != null ? request.residenceAddress() : current.residenceAddress(),
                request.residencePostalCode() != null ? request.residencePostalCode() : current.residencePostalCode(),
                request.phoneCountryCode() != null ? request.phoneCountryCode() : current.phoneCountryCode(),
                request.phoneNumber() != null ? request.phoneNumber() : current.phoneNumber(),
                request.languagePreference(), request.timezone()
        );

        log.info("identity.profile.updated userId={} mandatoryChanged={}", userId, mandatoryRequested);
        return toResponse(profileRepository.findByUserId(userId).orElseThrow());
    }

    private static String orDefault(String requested, String fallback) {
        return requested != null ? requested : fallback;
    }

    private static UserProfileResponse toResponse(IdentityUserProfile p) {
        return new UserProfileResponse(
                String.valueOf(p.userId()),
                p.firstName(), p.middleName(), p.lastName(),
                p.birthDate(), p.nationality(),
                p.gender(),
                p.residenceCountry(), p.residenceState(), p.residenceCity(),
                p.residenceAddress(), p.residencePostalCode(),
                p.phoneCountryCode(), p.phoneNumber(),
                p.languagePreference(), p.timezone(),
                p.profileVerified()
        );
    }
}
