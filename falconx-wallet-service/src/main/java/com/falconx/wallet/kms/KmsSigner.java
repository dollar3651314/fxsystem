package com.falconx.wallet.kms;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金签名抽象（见 docs/domain/状态机规范.md §7B）。
 *
 * <p>实现一期两选一：
 * <ul>
 *   <li>{@code LocalKmsSigner}：从 {@code falconx.wallet.kms.signing-keys.{network}.private-key-pem}
 *       加载本地私钥，dev / staging profile 使用</li>
 *   <li>{@code KmsSignerStub}：占位实现，调用时直接抛 {@code UnsupportedOperationException}；
 *       生产环境必须显式启用 LocalKmsSigner 或对接真实 KMS / HSM</li>
 * </ul>
 *
 * <p>线程安全要求：实现类必须线程安全（broadcast 服务可能并发调用）。私钥常驻内存，禁止在
 * 日志中输出 / 序列化。
 */
public interface KmsSigner {

    /**
     * 对原始交易字节进行签名。
     *
     * <p>语义：
     * <ul>
     *   <li>输入：{@code unsignedTx} 为待签名内容（一期约定为待签名 hash 或 raw tx 编码字节，
     *       具体语义由 {@code network} 决定 + broadcast 服务确定）</li>
     *   <li>输出：签名后的字节，可被 broadcast 服务直接组装为可上链交易；
     *       实际格式（完整 signed tx vs ECDSA signature primitive）由具体实现声明并保持一致</li>
     * </ul>
     *
     * @param network ERC20 / TRC20
     * @param fromAddress 平台热钱包地址（必须与配置的 {@code signing-keys.{network}.from-address} 匹配）
     * @param unsignedTx 待签名内容
     * @return 签名后的字节
     * @throws KmsSignerException 私钥未配置 / 解析失败 / 算法错误 / KMS 故障
     */
    byte[] sign(String network, String fromAddress, byte[] unsignedTx) throws KmsSignerException;

    /**
     * 是否能为指定 network 提供签名能力。{@code KmsSignerStub} 永远返回 {@code false}；
     * {@code LocalKmsSigner} 在私钥配置存在时返回 {@code true}。
     *
     * <p>broadcast 服务可在启动时检查能力，避免在生产 profile 配置错误时静默接受新提交。
     */
    boolean supports(String network);
}
