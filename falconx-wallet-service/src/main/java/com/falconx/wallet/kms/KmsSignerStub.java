package com.falconx.wallet.kms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * STAGE-7-WITHDRAW Phase 3：占位 {@link KmsSigner} 实现。
 *
 * <p>仅在没有其他 {@code KmsSigner} bean 时注入。生产环境如果误用此 stub，broadcast 服务在
 * 真正广播时会抛 {@link UnsupportedOperationException}，导致出金单进入 FAILED 状态并
 * 触发资金回滚链路（biz_type=18），属于"显式失败"而非"静默失败"。
 *
 * <p>未来 Commit 9 将引入 {@code LocalKmsSigner}（{@code @Profile("dev|staging")}），
 * 该 stub 仅在 prod 且未对接真实 KMS / HSM 时仍生效。
 */
public final class KmsSignerStub implements KmsSigner {

    private static final Logger log = LoggerFactory.getLogger(KmsSignerStub.class);

    @Override
    public byte[] sign(String network, String fromAddress, byte[] unsignedTx) {
        log.error("wallet.kms.signer.stub.invoked network={} fromAddress={} bytes={} -- 生产环境必须显式配置 LocalKmsSigner / 真实 KMS",
                network, fromAddress, unsignedTx == null ? 0 : unsignedTx.length);
        throw new UnsupportedOperationException(
                "KmsSignerStub 不应被调用；生产环境请显式启用 LocalKmsSigner 或接入 KMS / HSM");
    }

    @Override
    public boolean supports(String network) {
        return false;
    }

    /**
     * 默认装配：仅当没有其他 {@link KmsSigner} bean（例如 LocalKmsSigner）注入时，
     * 才把 stub 当作兜底 bean，避免静默静默忽略配置错误。
     */
    @Configuration
    static class StubAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(KmsSigner.class)
        public KmsSigner kmsSignerStub() {
            return new KmsSignerStub();
        }
    }
}
