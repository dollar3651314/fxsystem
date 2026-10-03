package com.falconx.trading;

import com.falconx.trading.application.WithdrawWhitelistApplicationService;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.error.TradingExternalRpcException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-7-WITHDRAW Phase 2 R6：trading-core 白名单 CRUD 应用层 IT。
 *
 * <p>覆盖 TC-WD-020 ~ 027 / 029。聚焦 trading-core 的本地校验（30044 / 30045）+
 * wallet 错误码翻译（20020→30053 / 20021→30051 / 20022→30052）。
 * 真 wallet 端业务在 wallet-service 模块的 IT 中单独覆盖。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
class WithdrawWhitelistIntegrationTests {

    private static final long USER_ID = 70030101L;
    private static final String ERC20_ADDRESS = "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa";
    private static final String TRC20_ADDRESS = "TRX9YnE9d3Wxc4nKZJ1JqAYPubsCJTNFB6";

    @Autowired
    private WithdrawWhitelistApplicationService applicationService;

    @MockitoBean
    private WithdrawWhitelistQueryClient whitelistClient;

    @BeforeEach
    void resetClient() {
        Mockito.reset(whitelistClient);
    }

    /** TC-WD-020：添加白名单成功 → 透传 wallet 返回 status=PENDING。 */
    @Test
    void shouldAddWhitelistAndReturnPending() {
        Mockito.when(whitelistClient.add(Mockito.eq(USER_ID), Mockito.eq("ERC20"),
                        Mockito.eq(ERC20_ADDRESS), Mockito.any()))
                .thenReturn(new WithdrawWhitelistQueryClient.WhitelistView(
                        1L, USER_ID, "ERC20", ERC20_ADDRESS, "我的 Ledger", "PENDING",
                        null, OffsetDateTime.now()));

        WithdrawWhitelistQueryClient.WhitelistView view = applicationService.add(
                USER_ID, "ERC20", ERC20_ADDRESS, "我的 Ledger");

        Assertions.assertEquals("PENDING", view.status());
        Assertions.assertEquals(ERC20_ADDRESS, view.address());
    }

    /** TC-WD-021：列表显示 ACTIVE 与 PENDING（trading-core 直接透传）。 */
    @Test
    void shouldListAllVisibleWhitelistsFromWallet() {
        Mockito.when(whitelistClient.listByUser(USER_ID)).thenReturn(List.of(
                new WithdrawWhitelistQueryClient.WhitelistView(
                        1L, USER_ID, "ERC20", ERC20_ADDRESS, "钱包A", "ACTIVE",
                        OffsetDateTime.now().minusHours(48), OffsetDateTime.now().minusHours(48)),
                new WithdrawWhitelistQueryClient.WhitelistView(
                        2L, USER_ID, "TRC20", TRC20_ADDRESS, "钱包B", "PENDING",
                        null, OffsetDateTime.now().minusHours(2))
        ));

        List<WithdrawWhitelistQueryClient.WhitelistView> list = applicationService.list(USER_ID);
        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals("ACTIVE", list.get(0).status());
        Assertions.assertEquals("PENDING", list.get(1).status());
    }

    /** TC-WD-022：wallet 返回 20021 LIMIT_EXCEEDED → trading 翻译 30051。 */
    @Test
    void shouldTranslateLimitExceededToWhitelistLimit() {
        Mockito.when(whitelistClient.add(Mockito.anyLong(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.any()))
                .thenThrow(new TradingExternalRpcException(200, "20021", "limit"));

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS, null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_WHITELIST_LIMIT_EXCEEDED, ex.getErrorCode());
    }

    /** TC-WD-023：wallet 返回 20022 DUPLICATE → trading 翻译 30052。 */
    @Test
    void shouldTranslateDuplicateToWhitelistDuplicate() {
        Mockito.when(whitelistClient.add(Mockito.anyLong(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.any()))
                .thenThrow(new TradingExternalRpcException(200, "20022", "duplicate"));

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.add(USER_ID, "ERC20", ERC20_ADDRESS, null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_WHITELIST_DUPLICATE, ex.getErrorCode());
    }

    /** TC-WD-024：删除 wallet 20020 NOT_FOUND → trading 翻译 30053。 */
    @Test
    void shouldTranslateNotFoundOnDeleteToWhitelistNotFound() {
        Mockito.when(whitelistClient.delete(Mockito.anyLong(), Mockito.anyLong()))
                .thenThrow(new TradingExternalRpcException(200, "20020", "not-found"));

        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.delete(USER_ID, 99999L));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_WHITELIST_NOT_FOUND, ex.getErrorCode());
    }

    /** TC-WD-026：ERC20 地址格式非法 → 30045（本地校验，不调 wallet）。 */
    @Test
    void shouldRejectInvalidErc20Address() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.add(USER_ID, "ERC20", "0xinvalid", null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_ADDRESS_INVALID, ex.getErrorCode());
        Mockito.verifyNoInteractions(whitelistClient);
    }

    /** TC-WD-027：TRC20 地址格式非法 → 30045。 */
    @Test
    void shouldRejectInvalidTrc20Address() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.add(USER_ID, "TRC20", "Tinvalid", null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_ADDRESS_INVALID, ex.getErrorCode());
    }

    /** network 非 ERC20/TRC20 → 30044（本地校验，不调 wallet）。 */
    @Test
    void shouldRejectUnsupportedNetwork() {
        TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                () -> applicationService.add(USER_ID, "BTC", ERC20_ADDRESS, null));
        Assertions.assertEquals(TradingErrorCode.WITHDRAW_NETWORK_UNSUPPORTED, ex.getErrorCode());
        Mockito.verifyNoInteractions(whitelistClient);
    }
}
