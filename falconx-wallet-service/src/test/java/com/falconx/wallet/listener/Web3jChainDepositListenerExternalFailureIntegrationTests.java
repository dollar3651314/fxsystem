package com.falconx.wallet.listener;

import static com.falconx.wallet.support.WalletExternalTestSupport.combinedOutput;
import static com.falconx.wallet.support.WalletExternalTestSupport.countOccurrences;
import static com.falconx.wallet.support.WalletExternalTestSupport.waitForCondition;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.entity.WalletChainCursor;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.support.WalletExternalTestSupport;
import com.falconx.wallet.util.WalletLogSanitizer;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Web3j 监听器外部失败路径测试。
 *
 * <p>该测试使用真实 ETH 节点错误认证地址，验证链头抓取失败后不会推进游标，并会在下一轮轮询里继续重试。
 */
@EnabledIfEnvironmentVariable(named = "FALCONX_WALLET_EXTERNAL_TEST_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "FALCONX_WALLET_ETH_RPC_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class Web3jChainDepositListenerExternalFailureIntegrationTests {

    @Test
    void shouldRetryWhenRealEthRpcRejectsAuthentication(CapturedOutput output) {
        WalletAddressRepository walletAddressRepository = Mockito.mock(WalletAddressRepository.class);
        WalletChainCursorRepository walletChainCursorRepository = Mockito.mock(WalletChainCursorRepository.class);
        WalletDepositTransactionRepository walletDepositTransactionRepository = Mockito.mock(WalletDepositTransactionRepository.class);
        when(walletChainCursorRepository.findByChain(ChainType.ETH)).thenReturn(new WalletChainCursor(
                1L,
                ChainType.ETH,
                "block",
                "1",
                OffsetDateTime.now()
        ));

        WalletServiceProperties.Chain chainProperties = new WalletServiceProperties.Chain();
        URI invalidRpcUrl = WalletExternalTestSupport.externalEthInvalidRpcUrl();
        chainProperties.setRpcUrl(invalidRpcUrl);
        chainProperties.setScanInterval(Duration.ofSeconds(1));
        chainProperties.setRequiredConfirmations(12);
        chainProperties.setCursorType("block");
        chainProperties.setInitialCursor("0");

        @SuppressWarnings("unchecked")
        Consumer<ObservedDepositTransaction> depositConsumer = Mockito.mock(Consumer.class);
        Web3jChainDepositListener listener = new Web3jChainDepositListener(
                ChainType.ETH,
                chainProperties,
                new WalletBlockchainClientFactory(),
                walletAddressRepository,
                walletChainCursorRepository,
                walletDepositTransactionRepository
        );
        try {
            listener.start(depositConsumer);

            waitForCondition(
                    Duration.ofSeconds(12),
                    "未在超时内观察到真实 ETH 节点错误认证后的重复重试日志" + System.lineSeparator() + combinedOutput(output),
                    () -> countOccurrences(combinedOutput(output), "wallet.listener.chainHead.syncFailed chain=ETH") >= 2
            );

            String logs = combinedOutput(output);
            Assertions.assertTrue(logs.contains(WalletLogSanitizer.maskRpcUrl(invalidRpcUrl)));
            Assertions.assertFalse(logs.contains(invalidRpcUrl.toString()));
            Assertions.assertTrue(logs.contains("wallet.listener.chainHead.syncFailed chain=ETH"));
            verify(walletChainCursorRepository, never()).updateCursor(eq(ChainType.ETH), anyString());
            verify(depositConsumer, never()).accept(any());
        } finally {
            listener.destroy();
        }
    }
}
