package com.falconx.wallet;

import com.falconx.wallet.application.WalletWithdrawWhitelistApplicationService;
import com.falconx.wallet.application.WalletWithdrawWhitelistCoolingScheduler;
import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.entity.WalletWithdrawWhitelistStatus;
import com.falconx.wallet.repository.WalletWithdrawWhitelistRepository;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * STAGE-7-WITHDRAW Phase 2 R6：wallet 白名单 24h 冷静期调度器 IT。
 *
 * <p>覆盖 TC-WD-028。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "falconx.wallet.withdraw-whitelist-cooling-scheduler.interval-ms=86400000"
        }
)
class WalletWithdrawWhitelistCoolingSchedulerIntegrationTests {

    private static final long USER_ID = 80010101L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";

    @Autowired
    private WalletWithdrawWhitelistApplicationService applicationService;

    @Autowired
    private WalletWithdrawWhitelistCoolingScheduler scheduler;

    @Autowired
    private WalletWithdrawWhitelistRepository whitelistRepository;

    @Autowired
    private WalletTestSupportMapper supportMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        supportMapper.clearOwnerTables();
    }

    /** TC-WD-028：PENDING 已超 24h → 调度器推进到 ACTIVE + activated_at 落库。 */
    @Test
    void shouldActivateExpiredPendingWhitelist() {
        WalletWithdrawWhitelist added = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS, null);
        Assertions.assertEquals(WalletWithdrawWhitelistStatus.PENDING, added.status());
        // 直接 SQL 把 created_at 倒推 25h
        jdbcTemplate.update("UPDATE t_withdraw_whitelist SET created_at = DATE_SUB(NOW(3), INTERVAL 25 HOUR) WHERE id = ?",
                added.id());

        int activated = scheduler.advance();

        Assertions.assertEquals(1, activated);
        Assertions.assertEquals(1, supportMapper.selectWithdrawWhitelistStatusById(added.id()));
        WalletWithdrawWhitelist refreshed = whitelistRepository.findById(added.id()).orElseThrow();
        Assertions.assertNotNull(refreshed.activatedAt());
    }

    /** PENDING 未满 24h → 不推进。 */
    @Test
    void shouldNotActivateWhitelistStillWithinCooling() {
        WalletWithdrawWhitelist added = applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS, null);
        // 不修改 created_at，默认 = now，远未到 24h

        int activated = scheduler.advance();

        Assertions.assertEquals(0, activated);
        Assertions.assertEquals(0, supportMapper.selectWithdrawWhitelistStatusById(added.id()));
    }
}
