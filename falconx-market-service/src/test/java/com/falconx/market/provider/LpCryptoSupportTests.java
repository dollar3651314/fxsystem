package com.falconx.market.provider;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 自建 LP 签名与加解密测试。
 */
class LpCryptoSupportTests {

    @Test
    void shouldSignSortedTokenTimestampNonceAndEncrypt() {
        LpCryptoSupport cryptoSupport = new LpCryptoSupport();

        String signature = cryptoSupport.sign("ow-token", "1752738482", "falconxnonce", "abcXYZ");

        Assertions.assertEquals("9d516971961d230d47cd41f6b80e240a21b9d090", signature);
    }

    @Test
    void shouldEncryptAndDecryptMessageWithAppIdSuffix() {
        LpCryptoSupport cryptoSupport = new LpCryptoSupport();
        String appId = "APPID12345";
        String secretKey = "U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0";
        String message = "{\"symbol\":\"AUDUSD\"}";

        String encrypted = cryptoSupport.encrypt(appId, secretKey, message);
        String decrypted = cryptoSupport.decrypt(appId, secretKey, encrypted);

        Assertions.assertNotEquals(message, encrypted);
        Assertions.assertEquals(message, decrypted);
    }

    @Test
    void shouldRejectDecryptWhenAppIdDoesNotMatch() {
        LpCryptoSupport cryptoSupport = new LpCryptoSupport();
        String secretKey = "U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0";
        String encrypted = cryptoSupport.encrypt("APPID12345", secretKey, "token-value");

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cryptoSupport.decrypt("OTHERAPP", secretKey, encrypted)
        );

        Assertions.assertTrue(error.getMessage().contains("appId"));
    }

    @Test
    void shouldRejectInvalidSecretKeyLength() {
        LpCryptoSupport cryptoSupport = new LpCryptoSupport();

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cryptoSupport.encrypt("APPID12345", "short", "token-value")
        );

        Assertions.assertTrue(error.getMessage().contains("secretKey"));
    }

    @Test
    void shouldUseUtf8LengthForMessageLengthPrefix() {
        LpCryptoSupport cryptoSupport = new LpCryptoSupport();
        String appId = "APPID12345";
        String secretKey = "U7lFsM0Oo4lsbuQRBXoeuIf6vY7LU0FlmRD31zehov0";
        String message = "{\"symbol\":\"黄金\"}";

        String encrypted = cryptoSupport.encrypt(appId, secretKey, message);
        String decrypted = cryptoSupport.decrypt(appId, secretKey, encrypted);

        Assertions.assertEquals(message.getBytes(StandardCharsets.UTF_8).length, decrypted.getBytes(StandardCharsets.UTF_8).length);
        Assertions.assertEquals(message, decrypted);
    }
}
