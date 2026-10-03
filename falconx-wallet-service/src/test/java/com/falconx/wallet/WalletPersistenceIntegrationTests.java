package com.falconx.wallet;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.application.WalletAddressAllocationApplicationService;
import com.falconx.wallet.application.WalletDepositTrackingApplicationService;
import com.falconx.wallet.entity.WalletAddressAssignment;
import com.falconx.wallet.entity.WalletDepositStatus;
import com.falconx.wallet.entity.WalletDepositTransaction;
import com.falconx.wallet.listener.ObservedDepositTransaction;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.support.WalletTestXpubSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * wallet-service 真实持久化集成测试。
 *
 * <p>该测试验证 Stage 5 下 wallet owner 的两类基础事实已经真正落库：
 *
 * <ol>
 *   <li>用户链地址分配进入 `t_wallet_address`</li>
 *   <li>链上原始入金进入 `t_wallet_deposit_tx`</li>
 *   <li>低频关键事件先写入 `t_outbox`，再由本地调度器异步投递</li>
 * </ol>
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletPersistenceIntegrationTests {

    @Autowired
    private WalletAddressAllocationApplicationService walletAddressAllocationApplicationService;

    @Autowired
    private WalletDepositTrackingApplicationService walletDepositTrackingApplicationService;

    @Autowired
    private WalletDepositTransactionRepository walletDepositTransactionRepository;

    @Autowired
    private WalletTestSupportMapper walletTestSupportMapper;

    @DynamicPropertySource
    static void registerXpubs(DynamicPropertyRegistry registry) {
        registry.add("falconx.wallet.derivation.eth-account-xpub", WalletTestXpubSupport::ethAccountXpub);
        registry.add("falconx.wallet.derivation.tron-account-xpub", WalletTestXpubSupport::tronAccountXpub);
    }

    @BeforeEach
    void cleanWalletTables() {
        walletTestSupportMapper.clearOwnerTables();
    }

    @Test
    void shouldPersistAddressAssignmentAndObservedDeposit() {
        WalletAddressAssignment assignment = walletAddressAllocationApplicationService.allocateAddress(93001L, ChainType.ETH);
        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                null,
                "0xwallet-stage5-001",
                0,
                "0xsource",
                assignment.address(),
                new BigDecimal("88.50000000"),
                123456L,
                12,
                OffsetDateTime.now(),
                false
        ));

        Integer addressCount = walletTestSupportMapper.countWalletAddressByUserId(93001L);
        Integer depositCount = walletTestSupportMapper.countWalletDepositByUserId(93001L);
        Integer confirmedCount = walletTestSupportMapper.countConfirmedDepositByTxHash("0xwallet-stage5-001");
        Integer outboxCount = walletTestSupportMapper.countOutbox();
        Integer confirmedOutboxCount = walletTestSupportMapper.countOutboxByEventType("wallet.deposit.confirmed");

        Assertions.assertEquals(1, addressCount);
        Assertions.assertEquals(1, depositCount);
        Assertions.assertEquals(1, confirmedCount);
        Assertions.assertEquals(1, outboxCount);
        Assertions.assertEquals(1, confirmedOutboxCount);
    }

    @Test
    void shouldIgnorePreConfirmedReversalWithoutPublishingReversedEvent() {
        WalletAddressAssignment assignment = walletAddressAllocationApplicationService.allocateAddress(93002L, ChainType.ETH);

        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                null,
                "0xwallet-stage5-preconfirm-reversal",
                0,
                "0xsource",
                assignment.address(),
                new BigDecimal("10.00000000"),
                223456L,
                0,
                OffsetDateTime.now(),
                true
        ));

        Integer depositStatus = walletTestSupportMapper.selectWalletDepositStatusByTxHash("0xwallet-stage5-preconfirm-reversal");
        Integer reversedOutboxCount = walletTestSupportMapper.countOutboxByEventType("wallet.deposit.reversed");

        Assertions.assertEquals(WalletDepositStatus.IGNORED.ordinal(), depositStatus);
        Assertions.assertEquals(0, reversedOutboxCount);
    }

    @Test
    void shouldKeepFirstConfirmedAtWhenLaterScansIncreaseConfirmations() {
        WalletAddressAssignment assignment = walletAddressAllocationApplicationService.allocateAddress(93003L, ChainType.ETH);
        OffsetDateTime firstConfirmedAt = OffsetDateTime.now().withNano(0);
        OffsetDateTime rescanObservedAt = firstConfirmedAt.plusMinutes(5);

        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                null,
                "0xwallet-stage5-confirmed-at",
                0,
                "0xsource",
                assignment.address(),
                new BigDecimal("25.00000000"),
                323456L,
                12,
                firstConfirmedAt,
                false
        ));
        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                null,
                "0xwallet-stage5-confirmed-at",
                0,
                "0xsource",
                assignment.address(),
                new BigDecimal("25.00000000"),
                323456L,
                20,
                rescanObservedAt,
                false
        ));

        OffsetDateTime persistedConfirmedAt = walletTestSupportMapper.selectConfirmedAtByTxHash("0xwallet-stage5-confirmed-at");
        Integer confirmedOutboxCount = walletTestSupportMapper.countOutboxByEventType("wallet.deposit.confirmed");

        Assertions.assertEquals(firstConfirmedAt.toInstant(), persistedConfirmedAt.toInstant());
        Assertions.assertEquals(1, confirmedOutboxCount);
    }

    @Test
    void shouldAdvanceTrackedErc20DepositThroughDetectedConfirmingAndConfirmed() {
        WalletAddressAssignment assignment = walletAddressAllocationApplicationService.allocateAddress(93004L, ChainType.ETH);

        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                "0x0000000000000000000000000000000000000abc",
                "0xwallet-stage6a-erc20",
                7,
                "0xsource",
                assignment.address(),
                new BigDecimal("123.45678901"),
                423456L,
                0,
                OffsetDateTime.now().minusMinutes(2),
                false
        ));
        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                "0x0000000000000000000000000000000000000abc",
                "0xwallet-stage6a-erc20",
                7,
                "0xsource",
                assignment.address(),
                new BigDecimal("123.45678901"),
                423456L,
                3,
                OffsetDateTime.now().minusMinutes(1),
                false
        ));
        walletDepositTrackingApplicationService.trackObservedDeposit(new ObservedDepositTransaction(
                ChainType.ETH,
                "USDT",
                "0x0000000000000000000000000000000000000abc",
                "0xwallet-stage6a-erc20",
                7,
                "0xsource",
                assignment.address(),
                new BigDecimal("123.45678901"),
                423456L,
                12,
                OffsetDateTime.now(),
                false
        ));

        WalletDepositTransaction persisted = walletDepositTransactionRepository
                .findByChainAndTxHashAndLogIndex(ChainType.ETH, "0xwallet-stage6a-erc20", 7)
                .orElseThrow();

        Assertions.assertEquals("USDT", persisted.token());
        Assertions.assertEquals("0x0000000000000000000000000000000000000abc", persisted.tokenContractAddress());
        Assertions.assertEquals(new BigDecimal("123.45678901"), persisted.amount());
        Assertions.assertEquals(12, persisted.confirmations());
        Assertions.assertEquals(WalletDepositStatus.CONFIRMED, persisted.status());
        Assertions.assertEquals(1, walletTestSupportMapper.countOutboxByEventType("wallet.deposit.detected"));
        Assertions.assertEquals(1, walletTestSupportMapper.countOutboxByEventType("wallet.deposit.confirmed"));
        Assertions.assertEquals(0, walletTestSupportMapper.countOutboxByEventType("wallet.deposit.reversed"));
    }
}
