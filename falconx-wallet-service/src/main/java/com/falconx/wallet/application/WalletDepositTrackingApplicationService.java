package com.falconx.wallet.application;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositDetectedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ObservedDepositTransaction;
import com.falconx.wallet.producer.WalletEventPublisher;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.service.WalletDepositStatusService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 钱包原始入金跟踪应用服务。
 *
 * <p>该服务是 Stage 2B 的核心应用层编排骨架，用于把链监听观察结果串成完整钱包链路：
 *
 * <ol>
 *   <li>根据目标地址判断是否归平台所有</li>
 *   <li>根据确认数和回滚标记推进原始交易状态</li>
 *   <li>按 `(chain, txHash, logIndex)` 做去重和幂等覆盖</li>
 *   <li>把状态结果保存到 wallet owner 仓储</li>
 *   <li>在需要时发布 detected / confirmed / reversed 事件</li>
 * </ol>
 *
 * <p>当前阶段 owner 数据和 Kafka 事件都已经接入真实基础设施，
 * 仍待后续阶段补全的只有真实链轮询与交易解析逻辑。
 */
@Service
public class WalletDepositTrackingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositTrackingApplicationService.class);

    private final WalletServiceProperties properties;
    private final WalletAddressRepository walletAddressRepository;
    private final WalletDepositTransactionRepository walletDepositTransactionRepository;
    private final WalletDepositStatusService walletDepositStatusService;
    private final WalletEventPublisher walletEventPublisher;

    public WalletDepositTrackingApplicationService(WalletServiceProperties properties,
                                                   WalletAddressRepository walletAddressRepository,
                                                   WalletDepositTransactionRepository walletDepositTransactionRepository,
                                                   WalletDepositStatusService walletDepositStatusService,
                                                   WalletEventPublisher walletEventPublisher) {
        this.properties = properties;
        this.walletAddressRepository = walletAddressRepository;
        this.walletDepositTransactionRepository = walletDepositTransactionRepository;
        this.walletDepositStatusService = walletDepositStatusService;
        this.walletEventPublisher = walletEventPublisher;
    }

    /**
     * 跟踪一条链监听观察结果。
     *
     * <p>该方法当前承担 wallet-service 最小主调用链。后续真实链监听器、MySQL 仓储和 Kafka
     * producer 只允许作为该链路的实现替换，不应改变其依赖方向和状态推进顺序。
     *
     * @param observedDeposit 链监听观察结果
     * @return 当前落地后的原始交易对象
     */
    @Transactional
    public WalletDepositTransaction trackObservedDeposit(ObservedDepositTransaction observedDeposit) {
        log.info("wallet.deposit.observed chain={} txHash={} confirmations={} reversed={}",
                observedDeposit.chain(),
                observedDeposit.txHash(),
                observedDeposit.confirmations(),
                observedDeposit.reversed());

        // 先根据目标地址判断交易是否归平台地址所有。
        // 这一步决定了后续是进入用户入金链路，还是仅在 wallet owner 内部留痕后标记为 IGNORED。
        Optional<WalletAddressAssignment> assignment = walletAddressRepository.findByChainAndAddress(
                observedDeposit.chain(),
                observedDeposit.toAddress()
        );
        Optional<WalletDepositTransaction> existingTransaction = walletDepositTransactionRepository.findByChainAndTxHashAndLogIndex(
                observedDeposit.chain(),
                observedDeposit.txHash(),
                observedDeposit.logIndex()
        );
        WalletDepositStatus newStatus = walletDepositStatusService.determineStatus(
                observedDeposit,
                existingTransaction,
                assignment.isPresent()
        );

        // 相同 `(chain, txHash, logIndex)` 只要状态没有推进、确认数也没有变大，就直接幂等跳过。
        // 这样可以避免链监听重复扫描同一区块时重复写库、重复发事件。
        if (shouldSkipDuplicate(existingTransaction, observedDeposit, newStatus)) {
            log.info("wallet.deposit.duplicate.skipped chain={} txHash={} status={} confirmations={}",
                    observedDeposit.chain(),
                    observedDeposit.txHash(),
                    existingTransaction.get().status(),
                    existingTransaction.get().confirmations());
            return existingTransaction.get();
        }

        // owner 表只保留“当前最新原始链事实”。
        // 每次状态推进都覆盖同一条原始交易记录，保证 wallet-service 对链事实的视图始终最新。
        WalletDepositTransaction persisted = walletDepositTransactionRepository.save(
                buildTransaction(existingTransaction, assignment, observedDeposit, newStatus)
        );

        // 事件发布严格依赖状态迁移结果，而不是依赖“本轮观察到了什么”。
        // 这样 detected / confirmed / reversed 的对外语义才是稳定的，不会因为重复扫描而重复通知下游。
        publishIfNecessary(existingTransaction, persisted);

        log.info("wallet.deposit.tracked chain={} txHash={} status={} userId={}",
                persisted.chain(),
                persisted.txHash(),
                persisted.status(),
                persisted.userId());
        return persisted;
    }

    private boolean shouldSkipDuplicate(Optional<WalletDepositTransaction> existingTransaction,
                                        ObservedDepositTransaction observedDeposit,
                                        WalletDepositStatus newStatus) {
        return existingTransaction
                .filter(existing -> existing.status() == newStatus)
                .filter(existing -> existing.confirmations() >= observedDeposit.confirmations())
                .isPresent();
    }

    private WalletDepositTransaction buildTransaction(Optional<WalletDepositTransaction> existingTransaction,
                                                      Optional<WalletAddressAssignment> assignment,
                                                      ObservedDepositTransaction observedDeposit,
                                                      WalletDepositStatus newStatus) {
        int requiredConfirmations = properties.chain(observedDeposit.chain()).getRequiredConfirmations();
        OffsetDateTime detectedAt = existingTransaction.map(WalletDepositTransaction::detectedAt)
                .orElse(observedDeposit.observedAt());
        OffsetDateTime confirmedAt = resolveConfirmedAt(existingTransaction, observedDeposit, newStatus);
        return new WalletDepositTransaction(
                existingTransaction.map(WalletDepositTransaction::id).orElse(null),
                assignment.map(WalletAddressAssignment::userId).orElse(null),
                observedDeposit.chain(),
                observedDeposit.token(),
                observedDeposit.tokenContractAddress(),
                observedDeposit.txHash(),
                observedDeposit.logIndex(),
                observedDeposit.fromAddress(),
                observedDeposit.toAddress(),
                observedDeposit.amount(),
                observedDeposit.blockNumber(),
                observedDeposit.confirmations(),
                requiredConfirmations,
                newStatus,
                detectedAt,
                confirmedAt,
                observedDeposit.observedAt()
        );
    }

    private OffsetDateTime resolveConfirmedAt(Optional<WalletDepositTransaction> existingTransaction,
                                              ObservedDepositTransaction observedDeposit,
                                              WalletDepositStatus newStatus) {
        // `confirmedAt` 表达“首次达到最终确认阈值的时间”，不是“最近一次确认扫描时间”。
        // 因此只有状态第一次进入 CONFIRMED 时才写入，后续确认数继续增长必须保留原值。
        if (newStatus == WalletDepositStatus.CONFIRMED
                && existingTransaction.map(WalletDepositTransaction::status).orElse(null) != WalletDepositStatus.CONFIRMED) {
            return observedDeposit.observedAt();
        }
        return existingTransaction.map(WalletDepositTransaction::confirmedAt).orElse(null);
    }

    private void publishIfNecessary(Optional<WalletDepositTransaction> existingTransaction,
                                    WalletDepositTransaction persisted) {
        // 无归属地址或被明确忽略的链事实，不应进入跨服务业务链路。
        // 这些记录只为 wallet owner 自己保留审计和排障事实。
        if (persisted.userId() == null || persisted.status() == WalletDepositStatus.IGNORED) {
            return;
        }

        WalletDepositStatus previousStatus = existingTransaction.map(WalletDepositTransaction::status).orElse(null);
        switch (persisted.status()) {
            case DETECTED, CONFIRMING -> {
                // detected 事件只在首次发现时发布一次。
                // 后续从 DETECTED 推进到 CONFIRMING 不单独发新事件，避免 trading-core 把“确认数推进”误判为新入金。
                if (previousStatus == null) {
                    walletEventPublisher.publishDepositDetected(new WalletDepositDetectedEventPayload(
                            persisted.id(),
                            persisted.userId(),
                            persisted.chain(),
                            persisted.token(),
                            persisted.txHash(),
                            persisted.fromAddress(),
                            persisted.toAddress(),
                            persisted.amount(),
                            persisted.confirmations(),
                            persisted.requiredConfirmations(),
                            persisted.detectedAt()
                    ));
                }
            }
            case CONFIRMED -> {
                // confirmed 事件是业务入账的关键触发器，只允许从“未确认”迁移到“已确认”时发一次。
                if (previousStatus != WalletDepositStatus.CONFIRMED) {
                    walletEventPublisher.publishDepositConfirmed(new WalletDepositConfirmedEventPayload(
                            persisted.id(),
                            persisted.userId(),
                            persisted.chain(),
                            persisted.token(),
                            persisted.txHash(),
                            persisted.fromAddress(),
                            persisted.toAddress(),
                            persisted.amount(),
                            persisted.confirmations(),
                            persisted.requiredConfirmations(),
                            persisted.confirmedAt()
                    ));
                }
            }
            case REVERSED -> {
                // reversed 事件用于通知下游回滚已确认或待确认的链事实，同样只能发一次。
                if (previousStatus != WalletDepositStatus.REVERSED) {
                    walletEventPublisher.publishDepositReversed(new WalletDepositReversedEventPayload(
                            persisted.id(),
                            persisted.userId(),
                            persisted.chain(),
                            persisted.token(),
                            persisted.txHash(),
                            persisted.fromAddress(),
                            persisted.toAddress(),
                            persisted.amount(),
                            persisted.confirmations(),
                            persisted.requiredConfirmations(),
                            persisted.updatedAt()
                    ));
                }
            }
            case IGNORED -> {
                // 无归属地址或无效交易不对外发事件，只在 wallet owner 内部留痕。
            }
        }
    }
}
