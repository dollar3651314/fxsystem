package com.falconx.identity.repository;

import com.falconx.identity.entity.IdentityUserProfile;
import com.falconx.identity.repository.mapper.IdentityUserProfileMapper;
import com.falconx.identity.repository.mapper.record.IdentityUserProfileRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisIdentityUserProfileRepository implements IdentityUserProfileRepository {

    private final IdentityUserProfileMapper mapper;

    public MybatisIdentityUserProfileRepository(IdentityUserProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IdentityUserProfile> findByUserId(long userId) {
        return Optional.ofNullable(mapper.selectByUserId(userId)).map(this::toDomain);
    }

    @Override
    public void createProfile(long userId, String firstName, String middleName, String lastName,
                                LocalDate birthDate, String nationality) {
        mapper.insertProfile(userId, firstName, middleName, lastName, birthDate, nationality, null, null);
    }

    @Override
    public int updateMandatoryFields(long userId, String firstName, String middleName, String lastName,
                                       LocalDate birthDate, String nationality) {
        return mapper.updateMandatoryFields(userId, firstName, middleName, lastName, birthDate, nationality);
    }

    @Override
    public void updateOptionalFields(long userId, Integer gender,
                                       String residenceCountry, String residenceState, String residenceCity,
                                       String residenceAddress, String residencePostalCode,
                                       String phoneCountryCode, String phoneNumber,
                                       String languagePreference, String timezone) {
        mapper.updateOptionalFields(userId, gender, residenceCountry, residenceState, residenceCity,
                residenceAddress, residencePostalCode, phoneCountryCode, phoneNumber,
                languagePreference, timezone);
    }

    @Override
    public void markVerified(long userId) {
        mapper.updateProfileVerified(userId, 1);
    }

    @Override
    public int updateProfileByAdmin(long userId,
                                    String firstName, String middleName, String lastName,
                                    LocalDate birthDate, String nationality,
                                    Integer gender,
                                    String residenceCountry, String residenceState, String residenceCity,
                                    String residenceAddress, String residencePostalCode,
                                    String phoneCountryCode, String phoneNumber,
                                    String languagePreference, String timezone) {
        return mapper.updateProfileByAdmin(userId,
                firstName, middleName, lastName, birthDate, nationality, gender,
                residenceCountry, residenceState, residenceCity,
                residenceAddress, residencePostalCode,
                phoneCountryCode, phoneNumber,
                languagePreference, timezone);
    }

    private IdentityUserProfile toDomain(IdentityUserProfileRecord r) {
        return new IdentityUserProfile(
                r.userId(), r.firstName(), r.middleName(), r.lastName(),
                r.birthDate(), r.nationality(), r.gender(),
                r.residenceCountry(), r.residenceState(), r.residenceCity(),
                r.residenceAddress(), r.residencePostalCode(),
                r.phoneCountryCode(), r.phoneNumber(),
                r.languagePreference(), r.timezone(),
                r.profileVerified() != null && r.profileVerified() == 1,
                toOffset(r.createdAt()), toOffset(r.updatedAt())
        );
    }

    private OffsetDateTime toOffset(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
