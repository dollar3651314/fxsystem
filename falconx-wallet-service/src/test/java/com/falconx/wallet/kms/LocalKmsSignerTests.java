package com.falconx.wallet.kms;

import com.falconx.wallet.config.WalletServiceProperties;
import java.math.BigInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：{@link LocalKmsSigner} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-WD-100 ERC20 secp256k1 签名往返 + supports()</li>
 *   <li>TC-WD-101 未配置 TRC20 私钥 → sign("TRC20",...) 抛 KmsSignerException + supports("TRC20")=false</li>
 * </ul>
 *
 * <p>TC-WD-103（profile 隔离）由 {@link com.falconx.wallet.kms.LocalKmsSignerInjectionIntegrationTests}
 * 覆盖（需 Spring context）。
 */
class LocalKmsSignerTests {

    /** 一个固定的 32 字节 hex secp256k1 私钥（仅测试用，对应公开测试地址）。 */
    private static final String ERC20_HEX_PRIVATE_KEY =
            "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";

    /** TC-WD-100：ERC20 secp256k1 签名往返。输入 32 字节 keccak hash → 输出 65 字节 r||s||v；supports("ERC20")=true。 */
    @Test
    void shouldSignErc20RoundTripAndSupportErc20() {
        WalletServiceProperties properties = newProperties(ERC20_HEX_PRIVATE_KEY, expectedAddress(ERC20_HEX_PRIVATE_KEY),
                null, null);
        LocalKmsSigner signer = new LocalKmsSigner(properties);

        Assertions.assertTrue(signer.supports("ERC20"));

        byte[] hash = Hash.sha3("hello-falconx-withdraw".getBytes());
        Assertions.assertEquals(32, hash.length);
        byte[] signature = signer.sign("ERC20", expectedAddress(ERC20_HEX_PRIVATE_KEY), hash);

        Assertions.assertNotNull(signature);
        Assertions.assertEquals(65, signature.length, "签名输出必须为 65 字节 r||s||v");

        // 验签：拼回 SignatureData 并用 Sign.signedMessageHashToKey 恢复公钥，应等于私钥派生的公钥。
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        byte[] v = new byte[]{signature[64]};
        System.arraycopy(signature, 0, r, 0, 32);
        System.arraycopy(signature, 32, s, 0, 32);
        Sign.SignatureData sigData = new Sign.SignatureData(v, r, s);

        ECKeyPair expectedKeyPair = ECKeyPair.create(new BigInteger(ERC20_HEX_PRIVATE_KEY, 16));
        try {
            BigInteger recoveredPubKey = Sign.signedMessageHashToKey(hash, sigData);
            Assertions.assertEquals(expectedKeyPair.getPublicKey(), recoveredPubKey,
                    "ECDSA 验签：恢复的公钥应等于私钥派生公钥");
        } catch (Exception ex) {
            Assertions.fail("ECDSA 验签失败：" + ex);
        }
    }

    /** TC-WD-101：未配置 TRC20 私钥 → sign("TRC20",...) 抛 KmsSignerException；supports("TRC20")=false。 */
    @Test
    void shouldThrowWhenTrc20PrivateKeyMissing() {
        // 只配 ERC20，TRC20 留空
        WalletServiceProperties properties = newProperties(
                ERC20_HEX_PRIVATE_KEY, expectedAddress(ERC20_HEX_PRIVATE_KEY),
                null, null);
        LocalKmsSigner signer = new LocalKmsSigner(properties);

        Assertions.assertFalse(signer.supports("TRC20"));

        KmsSignerException ex = Assertions.assertThrows(KmsSignerException.class,
                () -> signer.sign("TRC20", "TFakeTronAddressXXXXXXXXXXXXXXXXXXX", Hash.sha3(new byte[]{0x01, 0x02})));
        Assertions.assertTrue(ex.getMessage().contains("TRC20"),
                "异常 message 应说明 network=TRC20，实际：" + ex.getMessage());
    }

    private static WalletServiceProperties newProperties(String erc20Pem, String erc20Address,
                                                          String trc20Pem, String trc20Address) {
        WalletServiceProperties properties = new WalletServiceProperties();
        properties.getKms().getErc20().setPrivateKeyPem(erc20Pem);
        properties.getKms().getErc20().setFromAddress(erc20Address);
        properties.getKms().getTrc20().setPrivateKeyPem(trc20Pem);
        properties.getKms().getTrc20().setFromAddress(trc20Address);
        return properties;
    }

    private static String expectedAddress(String hexPrivateKey) {
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger(hexPrivateKey, 16));
        return Keys.toChecksumAddress(Keys.getAddress(keyPair));
    }
}
