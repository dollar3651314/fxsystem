package com.falconx.wallet.kms;

import com.falconx.wallet.config.WalletServiceProperties;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKeyStructure;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;

/**
 * STAGE-7-WITHDRAW Phase 3：本地私钥签名实现（dev / staging / test profile）。
 *
 * <p>仅在配置了 {@code falconx.wallet.kms.{erc20,trc20}.private-key-pem} 时启用；
 * 私钥支持 3 种格式：
 * <ul>
 *   <li>32 字节 hex（前缀可选 {@code 0x}）— 直接读取 secp256k1 私钥数</li>
 *   <li>PKCS#8 PEM（{@code -----BEGIN PRIVATE KEY-----}）— 标准 Java 私钥</li>
 *   <li>SEC1 EC PEM（{@code -----BEGIN EC PRIVATE KEY-----}）— OpenSSL EC 私钥</li>
 * </ul>
 *
 * <p>签名输入语义：{@code unsignedTx} 为 keccak256(rawTxEncoded) 的 32 字节 hash；
 * 输出 65 字节 {@code r||s||v}，broadcast 服务负责把签名拼回 RLP signed tx。
 *
 * <p>线程安全：{@link ECKeyPair} 不可变，无共享状态。私钥仅启动加载一次，常驻内存。
 */
public class LocalKmsSigner implements KmsSigner {

    private static final Logger log = LoggerFactory.getLogger(LocalKmsSigner.class);

    private final ECKeyPair erc20KeyPair;
    private final String erc20FromAddress;
    private final ECKeyPair trc20KeyPair;
    private final String trc20FromAddress;

    public LocalKmsSigner(WalletServiceProperties properties) {
        WalletServiceProperties.SigningKey erc20Config = properties.getKms().getErc20();
        this.erc20KeyPair = loadKeyPair("ERC20", erc20Config.getPrivateKeyPem());
        this.erc20FromAddress = normalizeAddress(erc20Config.getFromAddress());
        WalletServiceProperties.SigningKey trc20Config = properties.getKms().getTrc20();
        this.trc20KeyPair = loadKeyPair("TRC20", trc20Config.getPrivateKeyPem());
        this.trc20FromAddress = normalizeAddress(trc20Config.getFromAddress());
        log.info("wallet.kms.local-signer.initialized erc20Loaded={} trc20Loaded={}",
                erc20KeyPair != null, trc20KeyPair != null);
    }

    @Override
    public byte[] sign(String network, String fromAddress, byte[] unsignedTx) throws KmsSignerException {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(fromAddress, "fromAddress");
        Objects.requireNonNull(unsignedTx, "unsignedTx");
        if (unsignedTx.length != 32) {
            throw new KmsSignerException("LocalKmsSigner expects 32-byte keccak hash, got " + unsignedTx.length);
        }
        ECKeyPair keyPair = keyPairOf(network);
        String expectedAddress = expectedAddressOf(network);
        if (keyPair == null) {
            throw new KmsSignerException("LocalKmsSigner missing private key for network=" + network);
        }
        if (expectedAddress != null && !expectedAddress.equalsIgnoreCase(normalizeAddress(fromAddress))) {
            throw new KmsSignerException("LocalKmsSigner fromAddress mismatch: expected="
                    + expectedAddress + " got=" + fromAddress);
        }
        Sign.SignatureData sig = Sign.signMessage(unsignedTx, keyPair, false);
        byte[] r = sig.getR();
        byte[] s = sig.getS();
        byte[] v = sig.getV();
        byte[] out = new byte[r.length + s.length + v.length];
        System.arraycopy(r, 0, out, 0, r.length);
        System.arraycopy(s, 0, out, r.length, s.length);
        System.arraycopy(v, 0, out, r.length + s.length, v.length);
        return out;
    }

    @Override
    public boolean supports(String network) {
        return keyPairOf(network) != null;
    }

    private ECKeyPair keyPairOf(String network) {
        if (network == null) return null;
        return switch (network) {
            case "ERC20" -> erc20KeyPair;
            case "TRC20" -> trc20KeyPair;
            default -> null;
        };
    }

    private String expectedAddressOf(String network) {
        if (network == null) return null;
        return switch (network) {
            case "ERC20" -> erc20FromAddress;
            case "TRC20" -> trc20FromAddress;
            default -> null;
        };
    }

    private static String normalizeAddress(String address) {
        return address == null || address.isBlank() ? null : address.trim().toLowerCase(Locale.ROOT);
    }

    private static ECKeyPair loadKeyPair(String network, String privateKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            return null;
        }
        try {
            String trimmed = privateKeyPem.trim();
            if (trimmed.startsWith("-----BEGIN ")) {
                return loadKeyPairFromPem(trimmed);
            }
            String hex = trimmed.startsWith("0x") || trimmed.startsWith("0X") ? trimmed.substring(2) : trimmed;
            return ECKeyPair.create(new BigInteger(hex, 16));
        } catch (Exception ex) {
            throw new KmsSignerException("Failed to load private key for network=" + network, ex);
        }
    }

    private static ECKeyPair loadKeyPairFromPem(String pem) throws Exception {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        if (pem.contains("EC PRIVATE KEY")) {
            ECPrivateKeyStructure ecKey = new ECPrivateKeyStructure(ASN1Sequence.getInstance(der));
            return ECKeyPair.create(ecKey.getKey());
        }
        PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(der);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC", new BouncyCastleProvider());
        PrivateKey pk = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkInfo.getEncoded()));
        if (pk instanceof ECPrivateKey ecPk) {
            return ECKeyPair.create(ecPk.getS());
        }
        throw new KmsSignerException("Unsupported PEM key type: " + pk.getClass().getName());
    }
}
