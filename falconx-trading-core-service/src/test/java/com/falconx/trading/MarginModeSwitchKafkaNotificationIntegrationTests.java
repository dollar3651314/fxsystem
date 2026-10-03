package com.falconx.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.trading.application.MarginModeSwitchApplicationService;
import com.falconx.trading.dto.MarginModeSwitchResult;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-14D1 Task 4：margin mode 切换 Outbox 事件 + ACCOUNT_MODE_CHANGED 通知集成测试。
 *
 * <p>真实 falconx_trading_it 库（含 V33 列 + V34 模板 seed）。验证 cross_mode.enabled=true 下
 * ISOLATED→CROSS 切换成功后，同事务写入：
 * <ul>
 *   <li>1 条 eventType=trading.account.mode.changed 的 Outbox（topic falconx.trading.account.mode.changed），
 *       payload 含 userId/oldMode/newMode/changedAtMillis/coolingUntilMillis；</li>
 *   <li>1 条 ACCOUNT_MODE_CHANGED 站内信（relatedKey=ACCOUNT/relatedId=accountId，body 渲染 oldMode→newMode）。</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class MarginModeSwitchKafkaNotificationIntegrationTests {

    private static final long TEST_USER_ID = 990004001L;
    private static final String CURRENCY = "USDT";
    private static final String MODE_CHANGED_EVENT_TYPE = "trading.account.mode.changed";

    @Autowired
    private MarginModeSwitchApplicationService marginModeSwitchApplicationService;

    @Autowired
    private TradingAccountRepository tradingAccountRepository;

    @Autowired
    private RedisTradingRiskSwitchCache riskSwitchCache;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        tradingTestSupportMapper.clearOwnerTables();
    }

    @Test
    void switchMode_成功后写入1条Outbox事件与1条ACCOUNT_MODE_CHANGED通知() {
        // 开启 cross_mode.enabled 放行 CROSS 切换（D1 默认 gate）
        riskSwitchCache.write(new TradingRiskSwitch(
                TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, true, "IT", "it", null, null));

        TradingAccount created = tradingAccountRepository.save(new TradingAccount(
                null, TEST_USER_ID, CURRENCY,
                new BigDecimal("1000.00000000"),
                BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                TradingMarginMode.ISOLATED, null, null,
                OffsetDateTime.now(), OffsetDateTime.now()));

        MarginModeSwitchResult result =
                marginModeSwitchApplicationService.switchMode(TEST_USER_ID, TradingMarginMode.CROSS);

        assertThat(result.oldMode()).isEqualTo("ISOLATED");
        assertThat(result.newMode()).isEqualTo("CROSS");

        // Outbox：恰 1 条 mode.changed 事件
        assertThat(tradingTestSupportMapper.countOutboxByEventType(MODE_CHANGED_EVENT_TYPE)).isEqualTo(1);

        // payload JSON 字段正确
        String payloadJson = tradingTestSupportMapper.selectLatestOutboxPayloadByEventType(MODE_CHANGED_EVENT_TYPE);
        assertThat(payloadJson).isNotNull();
        JsonNode payload = objectMapper.readTree(payloadJson);
        assertThat(payload.get("userId").asLong()).isEqualTo(TEST_USER_ID);
        assertThat(payload.get("oldMode").asString()).isEqualTo("ISOLATED");
        assertThat(payload.get("newMode").asString()).isEqualTo("CROSS");
        assertThat(payload.get("changedAtMillis").asLong())
                .isEqualTo(result.modeChangedAt().toInstant().toEpochMilli());
        assertThat(payload.get("coolingUntilMillis").asLong())
                .isEqualTo(result.coolingUntil().toInstant().toEpochMilli());

        // 通知：恰 1 条 ACCOUNT_MODE_CHANGED，relatedKey/relatedId + body 渲染正确
        assertThat(tradingTestSupportMapper.countNotificationByUserIdAndType(TEST_USER_ID, "ACCOUNT_MODE_CHANGED"))
                .isEqualTo(1);
        assertThat(tradingTestSupportMapper.countNotificationByRelated("ACCOUNT", created.accountId()))
                .isEqualTo(1);
        assertThat(tradingTestSupportMapper.selectNotificationBodyByRelated("ACCOUNT", created.accountId()))
                .isEqualTo("您的保证金模式已从 ISOLATED 切换为 CROSS");
    }
}
