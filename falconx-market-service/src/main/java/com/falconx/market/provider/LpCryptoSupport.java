package com.falconx.market.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 自建 LP 签名与 AES 加解密支持。
 */
@Component
public class LpCryptoSupport {

    private static final int LP_SECRET_KEY_LENGTH = 43;
    private static final int AES_KEY_BYTES = 32;
    private static final int IV_BYTES = 16;
    private static final int RANDOM_BYTES = 16;
    private static final int MESSAGE_LENGTH_BYTES = 4;

    private final SecureRandom secureRandom;

    public LpCryptoSupport() {
        this(new SecureRandom());
    }

    LpCryptoSupport(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * 计算 LP 签名。
     *
     * @param token app token
     * @param timestamp 时间戳
     * @param nonce 随机串
     * @param encrypt 加密串
     * @return 小写 SHA-1 hex
     */
    public String sign(String token, String timestamp, String nonce, String encrypt) {
        try {
            List<String> sorted = java.util.stream.Stream.of(token, timestamp, nonce, encrypt)
                    .map(value -> value == null ? "" : value)
                    .sorted()
                    .toList();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("", sorted).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to build LP signature", exception);
        }
    }

    /**
     * 按 LP 文档格式加密消息。
     *
     * @param appId app id
     * @param secretKey 43 字符 AES key 原文
     * @param message 明文消息
     * @return Base64 加密串
     */
    public String encrypt(String appId, String secretKey, String message) {
        validateAppId(appId);
        byte[] aesKey = decodeAesKey(secretKey);
        byte[] messageBytes = valueOrEmpty(message).getBytes(StandardCharsets.UTF_8);
        byte[] appIdBytes = appId.getBytes(StandardCharsets.UTF_8);
        byte[] randomBytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);

        ByteBuffer plainBuffer = ByteBuffer.allocate(RANDOM_BYTES
                + MESSAGE_LENGTH_BYTES
                + messageBytes.length
                + appIdBytes.length);
        plainBuffer.put(randomBytes);
        plainBuffer.putInt(messageBytes.length);
        plainBuffer.put(messageBytes);
        plainBuffer.put(appIdBytes);

        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(Arrays.copyOf(aesKey, IV_BYTES)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainBuffer.array()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt LP message", exception);
        }
    }

    /**
     * 按 LP 文档格式解密消息。
     *
     * @param appId app id
     * @param secretKey 43 字符 AES key 原文
     * @param encrypted Base64 加密串
     * @return 明文消息
     */
    public String decrypt(String appId, String secretKey, String encrypted) {
        validateAppId(appId);
        byte[] aesKey = decodeAesKey(secretKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(Arrays.copyOf(aesKey, IV_BYTES)));
            byte[] plainBytes = cipher.doFinal(Base64.getDecoder().decode(valueOrEmpty(encrypted)));
            if (plainBytes.length < RANDOM_BYTES + MESSAGE_LENGTH_BYTES) {
                throw new IllegalArgumentException("Invalid LP encrypted payload");
            }
            ByteBuffer plainBuffer = ByteBuffer.wrap(plainBytes);
            plainBuffer.position(RANDOM_BYTES);
            int messageLength = plainBuffer.getInt();
            if (messageLength < 0 || plainBuffer.remaining() < messageLength) {
                throw new IllegalArgumentException("Invalid LP message length");
            }
            byte[] messageBytes = new byte[messageLength];
            plainBuffer.get(messageBytes);
            byte[] appIdBytes = new byte[plainBuffer.remaining()];
            plainBuffer.get(appIdBytes);
            String actualAppId = new String(appIdBytes, StandardCharsets.UTF_8);
            if (!appId.equals(actualAppId)) {
                throw new IllegalArgumentException("Invalid LP encrypted payload appId");
            }
            return new String(messageBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalStateException("Unable to decrypt LP message", exception);
        }
    }

    private byte[] decodeAesKey(String secretKey) {
        if (secretKey == null || secretKey.length() != LP_SECRET_KEY_LENGTH) {
            throw new IllegalArgumentException("LP secretKey must be 43 characters");
        }
        byte[] aesKey = Base64.getDecoder().decode(secretKey + "=");
        if (aesKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("LP secretKey must decode to 32 bytes");
        }
        return aesKey;
    }

    private void validateAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("LP appId must not be blank");
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
