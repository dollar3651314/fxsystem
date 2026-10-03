package com.falconx.wallet.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.falconx.wallet.WalletServiceApplication;
import com.falconx.wallet.repository.mapper.test.WalletTestSupportMapper;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 入金地址接口 fail-closed 集成测试。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = WalletServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_wallet_missing_xpub_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "falconx.wallet.derivation.eth-account-xpub=",
                "falconx.wallet.derivation.tron-account-xpub="
        }
)
class WalletDepositAddressFailClosedIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletTestSupportMapper walletTestSupportMapper;

    @Autowired
    private List<Filter> filters;

    private MockMvc mockMvc;

    @BeforeEach
    void cleanWalletTables() {
        walletTestSupportMapper.clearOwnerTables();
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    void shouldFailClosedWithoutAccountXpub() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/wallet/deposit-addresses/ensure")
                        .header("X-User-Id", 94002L)
                        .contentType("application/json")
                        .content("{}"))
                .andReturn();

        Assertions.assertEquals(200, result.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals("20006", body.path("code").asText());
        Assertions.assertTrue(body.path("data").isNull());
        Assertions.assertEquals(0, walletTestSupportMapper.countWalletAddressByUserId(94002L));
    }
}
