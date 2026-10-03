package com.falconx.identity.repository;

import com.falconx.identity.entity.IdentityUserProfile;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 用户基础资料 Repository（STAGE-1B-USER-PROFILE）。
 */
public interface IdentityUserProfileRepository {

    Optional<IdentityUserProfile> findByUserId(long userId);

    /** 注册时调用：插入 5 强制字段 + 默认 language/timezone。 */
    void createProfile(long userId, String firstName, String middleName, String lastName,
                        LocalDate birthDate, String nationality);

    /** profile_verified=0 时更新 5 强制字段；返回受影响行数（=0 表示已锁定，需抛 10022）。 */
    int updateMandatoryFields(long userId, String firstName, String middleName, String lastName,
                               LocalDate birthDate, String nationality);

    /** 更新可选字段（任何时候可改）。 */
    void updateOptionalFields(long userId, Integer gender,
                               String residenceCountry, String residenceState, String residenceCity,
                               String residenceAddress, String residencePostalCode,
                               String phoneCountryCode, String phoneNumber,
                               String languagePreference, String timezone);

    /** 阶段 6 KYC 通过时调用。 */
    void markVerified(long userId);

    /**
     * 管理端 patch profile：所有字段允许 null（COALESCE 保留原值），绕过 profile_verified 锁。
     *
     * @return 受影响行数（0 = 用户 profile 不存在）
     */
    int updateProfileByAdmin(long userId,
                              String firstName, String middleName, String lastName,
                              LocalDate birthDate, String nationality,
                              Integer gender,
                              String residenceCountry, String residenceState, String residenceCity,
                              String residenceAddress, String residencePostalCode,
                              String phoneCountryCode, String phoneNumber,
                              String languagePreference, String timezone);
}
