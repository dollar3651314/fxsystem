package com.falconx.console.repository;

import com.falconx.console.entity.AdminCustomer;
import com.falconx.console.repository.mapper.AdminCustomerMapper;
import com.falconx.console.repository.mapper.record.AdminCustomerRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-2-CUSTOMER 客户跨 schema 只读 Repository 的 MyBatis 实现。
 *
 * <p>UserStatus 枚举值映射（与 identity.UserStatus.ordinal 一致）：
 * 0=PENDING_DEPOSIT, 1=ACTIVE, 2=FROZEN, 3=BANNED。
 */
@Repository
public class MybatisAdminCustomerRepository implements AdminCustomerRepository {

    private static final String[] STATUS_NAMES = {"PENDING_DEPOSIT", "ACTIVE", "FROZEN", "BANNED"};

    private final AdminCustomerMapper adminCustomerMapper;

    public MybatisAdminCustomerRepository(AdminCustomerMapper adminCustomerMapper) {
        this.adminCustomerMapper = adminCustomerMapper;
    }

    @Override
    public List<AdminCustomer> findCustomers(String emailFragment,
                                             List<String> statusNames,
                                             OffsetDateTime fromCreatedAt,
                                             OffsetDateTime toCreatedAt,
                                             int offset,
                                             int limit) {
        return adminCustomerMapper.selectCustomers(
                        normalizeEmailLike(emailFragment),
                        normalizeStatusCodes(statusNames),
                        toLocalDateTime(fromCreatedAt),
                        toLocalDateTime(toCreatedAt),
                        offset,
                        limit
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countCustomers(String emailFragment,
                               List<String> statusNames,
                               OffsetDateTime fromCreatedAt,
                               OffsetDateTime toCreatedAt) {
        return adminCustomerMapper.countCustomers(
                normalizeEmailLike(emailFragment),
                normalizeStatusCodes(statusNames),
                toLocalDateTime(fromCreatedAt),
                toLocalDateTime(toCreatedAt));
    }

    @Override
    public Optional<AdminCustomer> findCustomerById(long userId) {
        return Optional.ofNullable(toDomain(adminCustomerMapper.selectCustomerById(userId)));
    }

    @Override
    public Optional<com.falconx.console.entity.AdminCustomerProfile> findCustomerProfileById(long userId) {
        var r = adminCustomerMapper.selectCustomerProfileById(userId);
        if (r == null) return Optional.empty();
        return Optional.of(new com.falconx.console.entity.AdminCustomerProfile(
                r.userId(), r.firstName(), r.middleName(), r.lastName(),
                r.birthDate(), r.nationality(), r.gender(),
                r.residenceCountry(), r.residenceState(), r.residenceCity(),
                r.residenceAddress(), r.residencePostalCode(),
                r.phoneCountryCode(), r.phoneNumber(),
                r.languagePreference(), r.timezone(),
                r.profileVerified() != null && r.profileVerified() == 1
        ));
    }

    private AdminCustomer toDomain(AdminCustomerRecord record) {
        if (record == null) return null;
        return new AdminCustomer(
                record.userId(),
                record.uid(),
                record.email(),
                fromStatusCode(record.status()),
                record.emailVerified() != null && record.emailVerified() == 1,
                record.groupCode(),
                nullToZero(record.balance()),
                nullToZero(record.frozen()),
                nullToZero(record.marginUsed()),
                toOffsetDateTime(record.activatedAt()),
                toOffsetDateTime(record.lastLoginAt()),
                record.lastLoginIp(),
                toOffsetDateTime(record.createdAt()),
                record.kycLevel() == null ? 0 : record.kycLevel(),
                joinFullName(record.firstName(), record.lastName())
        );
    }

    /** 拼接姓 + 名；任一为空时仅返回非空部分；全空返回 null。 */
    private static String joinFullName(String firstName, String lastName) {
        boolean hasFirst = firstName != null && !firstName.isBlank();
        boolean hasLast = lastName != null && !lastName.isBlank();
        if (!hasFirst && !hasLast) return null;
        if (!hasFirst) return lastName;
        if (!hasLast) return firstName;
        return firstName + " " + lastName;
    }

    private String fromStatusCode(Integer code) {
        if (code == null || code < 0 || code >= STATUS_NAMES.length) {
            return "UNKNOWN";
        }
        return STATUS_NAMES[code];
    }

    private List<Integer> normalizeStatusCodes(List<String> statusNames) {
        if (statusNames == null || statusNames.isEmpty()) {
            return List.of();
        }
        List<Integer> codes = new java.util.ArrayList<>(statusNames.size());
        for (String name : statusNames) {
            for (int i = 0; i < STATUS_NAMES.length; i++) {
                if (STATUS_NAMES[i].equalsIgnoreCase(name)) {
                    codes.add(i);
                    break;
                }
            }
        }
        return codes;
    }

    private String normalizeEmailLike(String emailFragment) {
        if (emailFragment == null || emailFragment.isBlank()) {
            return null;
        }
        return "%" + emailFragment.trim() + "%";
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static LocalDateTime toLocalDateTime(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atOffset(ZoneOffset.UTC);
    }
}
