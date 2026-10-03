package com.falconx.identity.repository.mapper;

import com.falconx.identity.repository.mapper.record.IdentityUserProfileRecord;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_user_profile} MyBatis Mapper（STAGE-1B-USER-PROFILE）。
 */
@Mapper
public interface IdentityUserProfileMapper {

    IdentityUserProfileRecord selectByUserId(@Param("userId") long userId);

    int insertProfile(@Param("userId") long userId,
                       @Param("firstName") String firstName,
                       @Param("middleName") String middleName,
                       @Param("lastName") String lastName,
                       @Param("birthDate") LocalDate birthDate,
                       @Param("nationality") String nationality,
                       @Param("languagePreference") String languagePreference,
                       @Param("timezone") String timezone);

    /** 更新 5 强制字段（profile_verified=0 时允许）。 */
    int updateMandatoryFields(@Param("userId") long userId,
                                @Param("firstName") String firstName,
                                @Param("middleName") String middleName,
                                @Param("lastName") String lastName,
                                @Param("birthDate") LocalDate birthDate,
                                @Param("nationality") String nationality);

    /** 更新可选字段（无锁定，任何时候可改）。 */
    int updateOptionalFields(@Param("userId") long userId,
                              @Param("gender") Integer gender,
                              @Param("residenceCountry") String residenceCountry,
                              @Param("residenceState") String residenceState,
                              @Param("residenceCity") String residenceCity,
                              @Param("residenceAddress") String residenceAddress,
                              @Param("residencePostalCode") String residencePostalCode,
                              @Param("phoneCountryCode") String phoneCountryCode,
                              @Param("phoneNumber") String phoneNumber,
                              @Param("languagePreference") String languagePreference,
                              @Param("timezone") String timezone);

    /** KYC 通过时锁定 5 强制字段（阶段 6 用）。 */
    int updateProfileVerified(@Param("userId") long userId, @Param("verified") int verified);

    /**
     * 管理端 patch profile：所有字段允许 null（COALESCE 保留原值），
     * 绕过 profile_verified 锁。
     *
     * @return 受影响行数（0 = 用户 profile 不存在）
     */
    int updateProfileByAdmin(@Param("userId") long userId,
                              @Param("firstName") String firstName,
                              @Param("middleName") String middleName,
                              @Param("lastName") String lastName,
                              @Param("birthDate") LocalDate birthDate,
                              @Param("nationality") String nationality,
                              @Param("gender") Integer gender,
                              @Param("residenceCountry") String residenceCountry,
                              @Param("residenceState") String residenceState,
                              @Param("residenceCity") String residenceCity,
                              @Param("residenceAddress") String residenceAddress,
                              @Param("residencePostalCode") String residencePostalCode,
                              @Param("phoneCountryCode") String phoneCountryCode,
                              @Param("phoneNumber") String phoneNumber,
                              @Param("languagePreference") String languagePreference,
                              @Param("timezone") String timezone);
}
