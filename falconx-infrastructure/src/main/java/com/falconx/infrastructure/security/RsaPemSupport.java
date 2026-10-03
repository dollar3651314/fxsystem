package com.falconx.infrastructure.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA PEM 解析工具。
 *
 * <p>该工具类用于在本地开发和测试阶段从配置中的 PEM 文本解析 RSA 公私钥，
 * 让 identity-service 与 gateway 可以围绕同一套 `RS256` 密钥材料完成签发与验证。
 */
public final class RsaPemSupport {

    private RsaPemSupport() {
    }

    /**
     * 解析 PKCS#8 私钥 PEM。
     *
     * @param privateKeyPem 私钥 PEM 文本
     * @return RSA 私钥
     */
    public static PrivateKey parsePrivateKey(String privateKeyPem) {
        try {
            byte[] keyBytes = decodePem(privateKeyPem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to parse RSA private key", exception);
        }
    }

    /**
     * 解析 X.509 公钥 PEM。
     *
     * @param publicKeyPem 公钥 PEM 文本
     * @return RSA 公钥
     */
    public static PublicKey parsePublicKey(String publicKeyPem) {
        try {
            byte[] keyBytes = decodePem(publicKeyPem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to parse RSA public key", exception);
        }
    }

    private static byte[] decodePem(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("RSA " + type + " PEM must not be blank");
        }
        String normalized = pem
                .replace("\\n", "\n")
                .trim()
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
