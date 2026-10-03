package com.falconx.wallet.consumer;

import com.falconx.wallet.application.WalletAddressAllocationApplicationService;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletAddressProvisionDlqRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-5-WALLET-PROVISION：监听 {@code falconx.identity.user.registered}，
 * 触发 ensureDefaultUsdtDepositAddresses 幂等分配 TRC20 / ERC20 入金地址。
 *
 * <p>幂等：ensureDefaultUsdtDepositAddresses 内部基于 t_wallet_address 主键唯一约束，
 * 重复触发不会产生新地址；因此本 consumer 不再单独维护 inbox 表。
 *
 * <p>失败语义：xpub 缺失 / wallet 链异常时抛出，Spring Kafka 默认重试 + 最终进 DLT
 * （由 `falconx.identity.user.registered-dlt` 兜底，DLT 列表+重试归阶段 5 Phase 2）。
 */
@Component
public class IdentityUserRegisteredEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdentityUserRegisteredEventConsumer.class);

    private final WalletAddressAllocationApplicationService allocationService;
    private final ObjectMapper objectMapper;
    private final WalletAddressProvisionDlqRepository dlqRepository;

    public IdentityUserRegisteredEventConsumer(WalletAddressAllocationApplicationService allocationService,
                                                ObjectMapper objectMapper,
                                                WalletAddressProvisionDlqRepository dlqRepository) {
        this.allocationService = allocationService;
        this.objectMapper = objectMapper;
        this.dlqRepository = dlqRepository;
    }

    @KafkaListener(
            topics = "${falconx.wallet.kafka.identity-user-registered-topic}",
            groupId = "${falconx.wallet.kafka.identity-user-registered-group}"
    )
    public void onUserRegistered(String payload) {
        long userId;
        String eventId;
        String uid;
        String email;
        try {
            JsonNode node = objectMapper.readTree(payload);
            userId = node.path("userId").asLong(0L);
            eventId = node.path("eventId").asString("");
            uid = node.path("uid").asString(null);
            email = node.path("email").asString(null);
            if (userId <= 0) {
                log.warn("wallet.consumer.user-registered.invalid-payload payload={}", payload);
                return;
            }
        } catch (RuntimeException ex) {
            log.error("wallet.consumer.user-registered.parse-failed reason={} payload={}", ex.toString(), payload, ex);
            // 解析失败不重试，直接放弃（坏消息不阻塞 partition）
            return;
        }
        log.info("wallet.consumer.user-registered.received eventId={} userId={}", eventId, userId);
        try {
            List<WalletAddressAssignment> assignments = allocationService.ensureDefaultUsdtDepositAddresses(userId);
            log.info("wallet.consumer.user-registered.completed eventId={} userId={} addressCount={}",
                    eventId, userId, assignments.size());
        } catch (WalletBusinessException ex) {
            // 区分瞬时错误 vs 配置/参数错误：
            //   ALLOCATION_FAILED 多源于 xpub 缺失等配置问题，重试无济于事 → 落 DLQ + 吞掉
            //   (运营在 console 列表看到，配齐 xpub 后点重试触发补分配)
            // 其它业务异常仍 rethrow 走 Spring Kafka 重试 + 最终 DLT
            if (ex.getErrorCode() == WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED) {
                log.error("wallet.consumer.user-registered.allocation-failed eventId={} userId={} reason={} action=enqueue-dlq",
                        eventId, userId, ex.getMessage());
                recordDlq(eventId, userId, uid, email, ex.getErrorCode().code(), ex.getMessage());
                return;
            }
            log.error("wallet.consumer.user-registered.business-failed eventId={} userId={} code={} reason={}",
                    eventId, userId, ex.getErrorCode().code(), ex.getMessage(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("wallet.consumer.user-registered.failed eventId={} userId={} reason={}",
                    eventId, userId, ex.toString(), ex);
            throw ex;
        }
    }

    private void recordDlq(String eventId, long userId, String uid, String email,
                           String errorCode, String errorMessage) {
        try {
            dlqRepository.recordFailure(new WalletAddressProvisionDlqEntry(
                    null,
                    eventId == null || eventId.isBlank() ? "user-registered-" + userId : eventId,
                    userId,
                    uid,
                    email,
                    1,
                    WalletAddressProvisionDlqStatus.PENDING,
                    errorCode,
                    truncate(errorMessage, 500),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    null,
                    OffsetDateTime.now(ZoneOffset.UTC)
            ));
        } catch (RuntimeException dlqEx) {
            // DLQ 入库失败不阻塞 consumer 提交 offset，只 log
            log.error("wallet.consumer.user-registered.dlq-record-failed eventId={} userId={} reason={}",
                    eventId, userId, dlqEx.toString(), dlqEx);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
