package com.falconx.wallet;

import com.falconx.wallet.application.WalletWithdrawWhitelistApplicationService;
import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.entity.WalletWithdrawWhitelistStatus;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * STAGE-7-WITHDRAW Phase 2 R6：wallet-service 白名单业务 IT。
 *
 * <p>覆盖 TC-WD-020 / 022 / 023 / 025 / 028（部分）/ 029 在 wallet 端的真实数据库行为。
 * trading-core 翻译层在 {@link com.falconx.trading.WithdrawWhitelistIntegrationTests} 单独覆盖。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletWithdrawWhitelistIntegrationTests {

    private static final long USER_ID = 80000101L;
    private static final String ERC20_ADDRESS_A = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String ERC20_ADDRESS_B = "0xBbbbbbbbBbbbbbbbBbbbbbbbBbbbbbbbBbbbbbbb";

    @Autowired
    private WalletWithdrawWhitelistApplicationService applicationService;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
    }

    /** TC-WD-020：添加白名单 → status=PENDING（24h 冷静期未过）。 */
    @Test
    void shouldAddNewWhitelistAsPending() {
        WalletWithdrawWhitelist added = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, "我的 Ledger");
        Assertions.assertEquals(WalletWithdrawWhitelistStatus.PENDING, added.status());
        Assertions.assertEquals(1, supportMapper.countWithdrawWhitelistByUserIdAndStatus(USER_ID, 0));
        Assertions.assertNull(added.activatedAt());
    }

    /** TC-WD-022：超 10 条 PENDING/ACTIVE → 20021。 */
    @Test
    void shouldRejectWhenWhitelistLimitExceeded() {
        for (int i = 0; i < 10; i++) {
            // 10 个唯一地址：把最后一个十六进制位换成 i
            String addr = "0xCcccccccCcccccccCcccccccCcccccccCcccccc" + i;
            applicationService.add(USER_ID, "ERC20", addr, null);
        }
        WalletBusinessException ex = Assertions.assertThrows(WalletBusinessException.class,
                () -> applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null));
        Assertions.assertEquals(WalletErrorCode.WITHDRAW_WHITELIST_LIMIT_EXCEEDED, ex.getErrorCode());
    }

    /** TC-WD-023：同 user+network+address 已 PENDING/ACTIVE → 20022。 */
    @Test
    void shouldRejectDuplicateWhitelist() {
        applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null);
        WalletBusinessException ex = Assertions.assertThrows(WalletBusinessException.class,
                () -> applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null));
        Assertions.assertEquals(WalletErrorCode.WITHDRAW_WHITELIST_DUPLICATE, ex.getErrorCode());
    }

    /** TC-WD-024：删除已 REMOVED 不存在 → 20020。 */
    @Test
    void shouldRejectDeleteWhenWhitelistAlreadyRemoved() {
        WalletWithdrawWhitelist added = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null);
        applicationService.delete(USER_ID, added.id());

        WalletBusinessException ex = Assertions.assertThrows(WalletBusinessException.class,
                () -> applicationService.delete(USER_ID, added.id()));
        Assertions.assertEquals(WalletErrorCode.WITHDRAW_WHITELIST_NOT_FOUND, ex.getErrorCode());
    }

    /** TC-WD-025：删除后重新添加同地址 → 允许（status=REMOVED 与 PENDING 唯一键不冲突）。 */
    @Test
    void shouldAllowReAddingWhitelistAfterRemoval() {
        WalletWithdrawWhitelist first = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null);
        applicationService.delete(USER_ID, first.id());

        WalletWithdrawWhitelist second = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null);
        Assertions.assertEquals(WalletWithdrawWhitelistStatus.PENDING, second.status());
        Assertions.assertNotEquals(first.id(), second.id());
        // 数据库里：1 个 REMOVED + 1 个 PENDING
        Assertions.assertEquals(1, supportMapper.countWithdrawWhitelistByUserIdAndStatus(USER_ID, 0));
        Assertions.assertEquals(1, supportMapper.countWithdrawWhitelistByUserIdAndStatus(USER_ID, 2));
    }

    /** TC-WD-021：列表只返回非 REMOVED（PENDING + ACTIVE）。 */
    @Test
    void shouldListOnlyNonRemovedWhitelists() {
        WalletWithdrawWhitelist a = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, null);
        applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_B, null);
        applicationService.delete(USER_ID, a.id());

        List<WalletWithdrawWhitelist> list = applicationService.list(USER_ID);
        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals(ERC20_ADDRESS_B, list.get(0).address());
    }

    /** TC-WD-029：label 超 64 → 截断到 64。 */
    @Test
    void shouldTruncateLongLabel() {
        String longLabel = "a".repeat(80);
        WalletWithdrawWhitelist added = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS_A, longLabel);
        Assertions.assertEquals(64, added.label().length());
    }
}
