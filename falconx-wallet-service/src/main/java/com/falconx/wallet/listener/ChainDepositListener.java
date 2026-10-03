package com.falconx.wallet.listener;

import com.falconx.domain.enums.ChainType;
import java.util.function.Consumer;

/**
 * 链上入金监听器抽象。
 *
 * <p>每条链的监听适配器都应实现该接口，
 * 并把监听到的原始链事实统一转成 {@link ObservedDepositTransaction} 后交给应用层。
 */
public interface ChainDepositListener {

    /**
     * 返回当前监听器负责的链类型。
     *
     * @return 链类型
     */
    ChainType chainType();

    /**
     * 启动监听流程。
     *
     * <p>当前 Stage 2B 骨架只冻结调用方向：
     * listener 作为外部输入源，把观察到的链上交易回调给应用层。
     *
     * @param depositConsumer 应用层的原始入金处理入口
     */
    void start(Consumer<ObservedDepositTransaction> depositConsumer);
}
