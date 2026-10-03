package com.falconx.identity.application;

import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.producer.IdentityKafkaEventPublisher;
import com.falconx.identity.repository.IdentityUserProfileRepository;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.identity.service.IdentitySecurityPolicyService;
import com.falconx.identity.service.IsoCountryCodes;
import com.falconx.identity.service.PasswordHashService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 注册应用服务。
 *
 * <p>该服务负责把注册请求串成 Stage 3A 的最小注册链路：
 *
 * <ol>
 *   <li>归一化邮箱并校验格式</li>
 *   <li>校验密码强度</li>
 *   <li>检查邮箱唯一性</li>
 *   <li>生成密码哈希并保存可登录用户</li>
 * </ol>
 *
 * <p>`UserStatus` 只表达账户可用性；注册完成后用户状态即为 `ACTIVE`。
 * 是否入金由资金事实或独立入金历史标记表达，不能通过 `PENDING_DEPOSIT`
 * 阻断用户登录。
 */
@Service
public class IdentityRegistrationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityRegistrationApplicationService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{M} '\\-]{1,64}$");
    private static final int MIN_AGE_YEARS = 18;

    private final IdentityUserRepository identityUserRepository;
    private final IdentityUserProfileRepository identityUserProfileRepository;
    private final IdentitySecurityPolicyService identitySecurityPolicyService;
    private final PasswordHashService passwordHashService;
    // STAGE-5-WALLET-PROVISION：注册成功后异步触发钱包预分配；可选注入，
    // 单测/无 Kafka 环境时为 null。
    private final IdentityKafkaEventPublisher kafkaEventPublisher;

    public IdentityRegistrationApplicationService(IdentityUserRepository identityUserRepository,
                                                  IdentityUserProfileRepository identityUserProfileRepository,
                                                  IdentitySecurityPolicyService identitySecurityPolicyService,
                                                  PasswordHashService passwordHashService,
                                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                  IdentityKafkaEventPublisher kafkaEventPublisher) {
        this.identityUserRepository = identityUserRepository;
        this.identityUserProfileRepository = identityUserProfileRepository;
        this.identitySecurityPolicyService = identitySecurityPolicyService;
        this.passwordHashService = passwordHashService;
        this.kafkaEventPublisher = kafkaEventPublisher;
    }

    /**
     * 执行用户注册。
     *
     * @param command 注册命令
     * @return 注册结果
     */
    @Transactional
    public RegisterResponse register(RegisterIdentityUserCommand command) {
        String normalizedEmail = normalizeEmail(command.email());
        String maskedEmail = maskEmail(normalizedEmail);
        validateEmail(normalizedEmail);
        validatePassword(command.password());
        // STAGE-1B-USER-PROFILE：注册时强制采集 5 PII 字段并校验
        validateName(command.firstName());
        if (command.middleName() != null && !command.middleName().isBlank()) {
            validateName(command.middleName());
        }
        validateName(command.lastName());
        validateBirthDate(command.birthDate());
        validateNationality(command.nationality());

        identitySecurityPolicyService.consumeRegisterQuota(command.clientIp());

        log.info("identity.register.received email={} clientIp={}", maskedEmail, command.clientIp());
        identityUserRepository.findByEmail(normalizedEmail).ifPresent(existing -> {
            throw new IdentityBusinessException(IdentityErrorCode.USER_ALREADY_EXISTS);
        });

        OffsetDateTime now = OffsetDateTime.now();
        IdentityUser user = new IdentityUser(
                null,
                null,
                normalizedEmail,
                passwordHashService.hash(command.password()),
                UserStatus.ACTIVE,
                "default",
                false,
                now,
                null,
                now,
                now
        );
        IdentityUser persisted = identityUserRepository.save(user);

        // 同事务内 INSERT t_user_profile（5 强制字段 + 默认 language/timezone）
        identityUserProfileRepository.createProfile(
                persisted.id(),
                command.firstName().trim(),
                command.middleName() == null || command.middleName().isBlank() ? null : command.middleName().trim(),
                command.lastName().trim(),
                command.birthDate(),
                command.nationality()
        );

        log.info("identity.register.completed userId={} uid={} status={} clientIp={}",
                persisted.id(),
                persisted.uid(),
                persisted.status(),
                command.clientIp());
        // STAGE-5-WALLET-PROVISION：注册事务提交后再发 Kafka，避免 Kafka 抖动让用户事务回滚。
        if (kafkaEventPublisher != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaEventPublisher.publishUserRegistered(persisted.id(), persisted.uid(), persisted.email());
                }
            });
        }
        return new RegisterResponse(
                persisted.id(),
                persisted.uid(),
                persisted.email(),
                persisted.status().name(),
                persisted.emailVerified()
        );
    }

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name.trim()).matches()) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_NAME_INVALID);
        }
    }

    private void validateBirthDate(LocalDate birthDate) {
        if (birthDate == null) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_BIRTH_DATE_INVALID);
        }
        LocalDate today = LocalDate.now();
        if (birthDate.isAfter(today)) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_BIRTH_DATE_INVALID);
        }
        int years = Period.between(birthDate, today).getYears();
        if (years < MIN_AGE_YEARS) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_AGE_BELOW_MINIMUM);
        }
    }

    private void validateNationality(String nationality) {
        if (!IsoCountryCodes.isValid(nationality)) {
            throw new IdentityBusinessException(IdentityErrorCode.USER_PROFILE_COUNTRY_INVALID);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IdentityBusinessException(IdentityErrorCode.EMAIL_FORMAT_INVALID);
        }
    }

    private void validatePassword(String password) {
        int length = password.length();
        if (length < 8 || length > 64) {
            throw new IdentityBusinessException(IdentityErrorCode.PASSWORD_TOO_WEAK);
        }
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(0, atIndex));
        }
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
}
