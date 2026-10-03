package com.falconx.wallet.config;

import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.kms.KmsSigner;
import com.falconx.wallet.kms.LocalKmsSigner;
import com.falconx.wallet.listener.ChainDepositListener;
import com.falconx.wallet.listener.SolanaRpcChainDepositListener;
import com.falconx.wallet.listener.TronApiChainDepositListener;
import com.falconx.wallet.listener.Web3jChainDepositListener;
import com.falconx.wallet.producer.KafkaWalletEventPublisher;
import com.falconx.wallet.producer.OutboxBackedWalletEventPublisher;
import com.falconx.wallet.producer.WalletEventPublisher;
import com.falconx.wallet.producer.WalletOutboxEventPublisher;
import com.falconx.wallet.withdraw.EthNonceManager;
import com.falconx.wallet.repository.WalletAddressRepository;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import com.falconx.wallet.repository.WalletDepositTransactionRepository;
import com.falconx.wallet.repository.WalletOutboxRepository;
import com.falconx.wallet.service.WalletAddressAllocationService;
import com.falconx.wallet.service.WalletAddressDerivationService;
import com.falconx.wallet.service.WalletDepositStatusService;
import com.falconx.wallet.service.impl.DefaultWalletDepositStatusService;
import com.falconx.wallet.service.impl.SequentialWalletAddressAllocationService;
import com.falconx.wallet.service.impl.XpubWalletAddressDerivationService;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;

/**
 * wallet-service 基础配置入口。
 *
 * <p>当前阶段钱包 owner 数据已经切换到真实数据库仓储，
 * 事件发布和链监听入口也已切到真实 Kafka / 链 SDK 骨架。
 * 后续补全真实链轮询逻辑时，只允许继续扩展 listener / client 包，不应改变这里的装配边界。
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(WalletServiceProperties.class)
public class WalletServiceConfiguration {

    /**
     * STAGE-5-WALLET-PROVISION：注册 Kafka 监听容器工厂，承接
     * {@code falconx.identity.user.registered}。
     */
    @Bean(name = "kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    /**
     * 注册 wallet-service 自用的 Jackson `ObjectMapper`。
     *
     * <p>钱包服务内部的 Kafka 发布和链监听骨架使用 `com.fasterxml.jackson` 体系序列化
     * 事件 payload。这里显式统一 mapper，确保 Java Time 字段和运行时 Bean 装配一致。
     *
     * @return 统一 JSON 序列化器
     */
    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    @Bean
    WalletAddressAllocationService walletAddressAllocationService(WalletServiceProperties properties,
                                                                  WalletAddressRepository walletAddressRepository,
                                                                  WalletAddressDerivationService walletAddressDerivationService) {
        return new SequentialWalletAddressAllocationService(
                properties,
                walletAddressRepository,
                walletAddressDerivationService
        );
    }

    @Bean
    WalletAddressDerivationService walletAddressDerivationService(WalletServiceProperties properties) {
        return new XpubWalletAddressDerivationService(properties);
    }

    @Bean
    WalletDepositStatusService walletDepositStatusService(WalletServiceProperties properties) {
        return new DefaultWalletDepositStatusService(properties);
    }

    @Bean
    WalletEventPublisher walletEventPublisher(WalletOutboxRepository walletOutboxRepository) {
        return new OutboxBackedWalletEventPublisher(walletOutboxRepository);
    }

    @Bean
    WalletOutboxEventPublisher walletOutboxEventPublisher(WalletServiceProperties properties,
                                                          KafkaTemplate<String, String> kafkaTemplate,
                                                          ObjectMapper objectMapper,
                                                          IdGenerator idGenerator) {
        return new KafkaWalletEventPublisher(properties, kafkaTemplate, objectMapper, idGenerator);
    }

    @Bean
    WalletBlockchainClientFactory walletBlockchainClientFactory(ObjectMapper objectMapper) {
        return new WalletBlockchainClientFactory(objectMapper);
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：出金链路专用 Web3j 客户端（接 Alchemy 测试网，与入金扫块 Web3j
     * 隔离避免误触发入金扫真链）。
     */
    @Bean(name = "walletWithdrawWeb3j")
    Web3j walletWithdrawWeb3j(WalletServiceProperties properties,
                              WalletBlockchainClientFactory factory) {
        return factory.createEvmClient(properties.getWithdraw().getEth().getRpcUrl());
    }

    /**
     * STAGE-7-WITHDRAW Phase 3：仅当 {@code falconx.wallet.kms.erc20.private-key-pem} 配置且非空时
     * 加载本地签名器；否则 {@link com.falconx.wallet.kms.KmsSignerStub} 通过
     * {@code @ConditionalOnMissingBean} 兜底（调用即抛，避免静默失败）。
     *
     * <p>使用 {@code @ConditionalOnExpression} 而非 {@code @ConditionalOnProperty}，因为
     * application.yml 默认值为空字符串 {@code ${FALCONX_WALLET_ERC20_PRIVATE_KEY_PEM:}}，
     * {@code @ConditionalOnProperty} 把空字符串视为存在 → 会错误激活；改用 SpEL 显式判非空。
     */
    @Bean
    @ConditionalOnExpression("'${falconx.wallet.kms.erc20.private-key-pem:}' != ''")
    KmsSigner localKmsSigner(WalletServiceProperties properties) {
        return new LocalKmsSigner(properties);
    }

    @Bean
    EthNonceManager ethNonceManager(Web3j walletWithdrawWeb3j) {
        return new EthNonceManager(walletWithdrawWeb3j);
    }

    @Bean
    List<ChainDepositListener> chainDepositListeners(WalletServiceProperties properties,
                                                     WalletBlockchainClientFactory walletBlockchainClientFactory,
                                                     WalletAddressRepository walletAddressRepository,
                                                     WalletChainCursorRepository walletChainCursorRepository,
                                                     WalletDepositTransactionRepository walletDepositTransactionRepository,
                                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // PROD-OPS-EVIDENCE-01 C4：注册表通过 ObjectProvider 可选注入，未装配时为 null，
        // listener 内埋点守卫判空，不影响扫块/检测业务逻辑。
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return List.of(
                new Web3jChainDepositListener(
                        ChainType.ETH,
                        properties.chain(ChainType.ETH),
                        walletBlockchainClientFactory,
                        walletAddressRepository,
                        walletChainCursorRepository,
                        walletDepositTransactionRepository,
                        meterRegistry
                ),
                new Web3jChainDepositListener(
                        ChainType.BSC,
                        properties.chain(ChainType.BSC),
                        walletBlockchainClientFactory,
                        walletAddressRepository,
                        walletChainCursorRepository,
                        walletDepositTransactionRepository,
                        meterRegistry
                ),
                new TronApiChainDepositListener(
                        properties.chain(ChainType.TRON),
                        walletBlockchainClientFactory,
                        walletAddressRepository,
                        walletChainCursorRepository,
                        walletDepositTransactionRepository,
                        meterRegistry
                ),
                new SolanaRpcChainDepositListener(properties.chain(ChainType.SOL), walletBlockchainClientFactory)
        );
    }
}
