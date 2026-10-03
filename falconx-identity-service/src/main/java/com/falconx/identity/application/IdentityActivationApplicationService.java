package com.falconx.identity.application;

import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入金 credited 事件消费应用服务。
 *
 * <p>用户注册后即处于可登录的 `ACTIVE` 状态，因此该服务不再承担
 * “入金后改变账户可用状态”的主路径。它仍保留对历史 `PENDING_DEPOSIT` 记录的
 * 归一化能力，并配合 `t_inbox` 保证 `falconx.trading.deposit.credited`
 * 事件幂等消费。
 */
@Service
public class IdentityActivationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityActivationApplicationService.class);

    private final IdentityUserRepository identityUserRepository;

    public IdentityActivationApplicationService(IdentityUserRepository identityUserRepository) {
        this.identityUserRepository = identityUserRepository;
    }

    /**
     * 处理入账完成事件。
     *
     * <p>事务范围内先确认用户存在；若是历史 `PENDING_DEPOSIT` 记录，则归一化为
     * `ACTIVE`。已是 `ACTIVE / FROZEN / BANNED` 的用户不发生状态迁移，调用方仍可
     * 在业务成功后写入 inbox，保持事件幂等。
     *
     * @param eventId 事件唯一 ID
     * @param payload 入账完成事件 payload
     */
    @Transactional
    public void activateUserFromDepositCredited(String eventId, DepositCreditedEventPayload payload) {
        log.info("identity.activation.request eventId={} userId={} depositId={}",
                eventId,
                payload.userId(),
                payload.depositId());

        // `t_inbox` 只能在“业务真正处理成功或幂等跳过成立”后落库。
        // 因此这里必须先拿到真实用户；若事件先于用户记录到达，需要抛错让上层重试，
        // 不能把“用户不存在”误当成已消费成功。
        IdentityUser user = identityUserRepository.findById(payload.userId())
                .orElseThrow(() -> {
                    log.warn("identity.activation.user_not_found eventId={} userId={}", eventId, payload.userId());
                    return new IllegalStateException("Identity user not found for deposit activation");
                });

        if (user.status() == UserStatus.PENDING_DEPOSIT) {
            identityUserRepository.save(new IdentityUser(
                    user.id(),
                    user.uid(),
                    user.email(),
                    user.passwordHash(),
                    UserStatus.ACTIVE,
                    user.groupCode(),
                    user.emailVerified(),
                    payload.creditedAt() != null ? payload.creditedAt() : OffsetDateTime.now(),
                    user.lastLoginAt(),
                    user.createdAt(),
                    OffsetDateTime.now()
            ));
            log.info("identity.activation.completed eventId={} userId={} fromStatus={} toStatus={}",
                    eventId,
                    user.id(),
                    UserStatus.PENDING_DEPOSIT,
                    UserStatus.ACTIVE);
            return;
        }

        log.info("identity.activation.skipped eventId={} userId={} currentStatus={}",
                eventId,
                user.id(),
                user.status());
    }
}
