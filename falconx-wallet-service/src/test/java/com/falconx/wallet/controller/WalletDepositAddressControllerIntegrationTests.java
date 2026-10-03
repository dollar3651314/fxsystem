package com.falconx.wallet.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.falconx.wallet.WalletServiceApplication;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import com.falconx.wallet.support.WalletTestXpubSupport;
import java.util.List;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 入金地址接口集成测试。
 *
 * <p>该测试覆盖用户首次进入入金页时，wallet-service 基于 account-level xpub
 * 幂等派生并返回 USDT-TRON 与 USDT-ETH 两条真实入金地址。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = WalletServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root"
        }
)
class WalletDepositAddressControllerIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletTestSupportMapper walletTestSupportMapper;

    @Autowired
    private List<Filter> filters;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerXpubs(DynamicPropertyRegistry registry) {
        registry.add("falconx.wallet.derivation.eth-account-xpub", WalletTestXpubSupport::ethAccountXpub);
        registry.add("falconx.wallet.derivation.tron-account-xpub", WalletTestXpubSupport::tronAccountXpub);
    }

    @BeforeEach
    void cleanWalletTables() {
        walletTestSupportMapper.clearOwnerTables();
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    void shouldEnsureUsdtTronAndEthDepositAddressesIdempotently() throws Exception {
        JsonNode first = ensureDepositAddresses(94001L);
        JsonNode second = ensureDepositAddresses(94001L);

        JsonNode firstAddresses = first.path("data").path("addresses");
        JsonNode secondAddresses = second.path("data").path("addresses");
        Assertions.assertEquals("0", first.path("code").asText());
        Assertions.assertEquals(2, firstAddresses.size());
        Assertions.assertEquals(firstAddresses.toString(), secondAddresses.toString());

        JsonNode trc20 = firstAddresses.get(0);
        Assertions.assertEquals("TRC20", trc20.path("network").asText());
        Assertions.assertEquals("TRON", trc20.path("chain").asText());
        Assertions.assertEquals("USDT", trc20.path("token").asText());
        Assertions.assertEquals(1, trc20.path("addressIndex").asInt());
        Assertions.assertEquals("m/44'/195'/0'/0/1", trc20.path("derivationPath").asText());
        Assertions.assertEquals(WalletTestXpubSupport.expectedTronAddress(1), trc20.path("address").asText());

        JsonNode erc20 = firstAddresses.get(1);
        Assertions.assertEquals("ERC20", erc20.path("network").asText());
        Assertions.assertEquals("ETH", erc20.path("chain").asText());
        Assertions.assertEquals("USDT", erc20.path("token").asText());
        Assertions.assertEquals(1, erc20.path("addressIndex").asInt());
        Assertions.assertEquals("m/44'/60'/0'/0/1", erc20.path("derivationPath").asText());
        Assertions.assertEquals(WalletTestXpubSupport.expectedEthAddress(1), erc20.path("address").asText());

        Assertions.assertEquals(2, walletTestSupportMapper.countWalletAddressByUserId(94001L));
        Assertions.assertEquals(1, walletTestSupportMapper.countWalletAddressByUserIdAndChain(94001L, "TRON"));
        Assertions.assertEquals(1, walletTestSupportMapper.countWalletAddressByUserIdAndChain(94001L, "ETH"));
        Assertions.assertEquals(WalletTestXpubSupport.expectedTronAddress(1),
                walletTestSupportMapper.selectWalletAddressByUserIdAndChain(94001L, "TRON"));
        Assertions.assertEquals("USDT", walletTestSupportMapper.selectWalletAddressTokenByUserIdAndChain(94001L, "TRON"));
        Assertions.assertEquals("TRC20", walletTestSupportMapper.selectWalletAddressNetworkByUserIdAndChain(94001L, "TRON"));
        Assertions.assertEquals("m/44'/195'/0'/0/1",
                walletTestSupportMapper.selectWalletAddressDerivationPathByUserIdAndChain(94001L, "TRON"));
    }

    private JsonNode ensureDepositAddresses(long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/wallet/deposit-addresses/ensure")
                        .header("X-User-Id", userId)
                        .contentType("application/json")
                        .content("{}"))
                .andReturn();
        Assertions.assertEquals(200, result.getResponse().getStatus());
        Assertions.assertNotNull(result.getResponse().getHeader("X-Trace-Id"));
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
