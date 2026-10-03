package com.falconx.wallet.application;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.listener.ChainDepositListener;
import com.falconx.wallet.repository.WalletChainCursorRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 钱包链监听启动引导器。
 *
 * <p>该组件在服务启动时完成两件事：
 *
 * <ol>
 *   <li>为各条链初始化监听游标</li>
 *   <li>启动对应链监听器，并把原始入金观察结果交给应用层</li>
 * </ol>
 *
 * <p>当前阶段已经切换到真实链 SDK 驱动的监听器骨架，
 * 用于固定“外部链输入 -> wallet 应用层”的启动顺序和调用方向。
 */
@Component
public class WalletListenerBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WalletListenerBootstrapRunner.class);

    private final List<ChainDepositListener> chainDepositListeners;
    private final WalletChainCursorRepository walletChainCursorRepository;
    private final WalletServiceProperties walletServiceProperties;
    private final WalletDepositTrackingApplicationService walletDepositTrackingApplicationService;

    public WalletListenerBootstrapRunner(List<ChainDepositListener> chainDepositListeners,
                                         WalletChainCursorRepository walletChainCursorRepository,
                                         WalletServiceProperties walletServiceProperties,
                                         WalletDepositTrackingApplicationService walletDepositTrackingApplicationService) {
        this.chainDepositListeners = chainDepositListeners;
        this.walletChainCursorRepository = walletChainCursorRepository;
        this.walletServiceProperties = walletServiceProperties;
        this.walletDepositTrackingApplicationService = walletDepositTrackingApplicationService;
    }

    @Override
    public void run(String... args) {
        log.info("wallet.listener.bootstrap.start");
        for (ChainDepositListener listener : chainDepositListeners) {
            WalletServiceProperties.Chain chainProperties = walletServiceProperties.chain(listener.chainType());
            walletChainCursorRepository.initializeIfAbsent(
                    listener.chainType(),
                    chainProperties.getCursorType(),
                    chainProperties.getInitialCursor()
            );
            listener.start(walletDepositTrackingApplicationService::trackObservedDeposit);
        }
        log.info("wallet.listener.bootstrap.ready listenerCount={}", chainDepositListeners.size());
    }
}
