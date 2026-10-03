package com.falconx.gateway;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.tron.trident.utils.Base58Check;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Hash;

/**
 * Gateway E2E 使用的入金地址派生测试 xpub。
 */
final class GatewayWalletTestXpubSupport {

    private static final int HARDENED = Bip32ECKeyPair.HARDENED_BIT;
    private static final byte[] SEED = Hash.sha256(
            "falconx-gateway-wallet-deposit-address-e2e".getBytes(StandardCharsets.UTF_8)
    );

    private GatewayWalletTestXpubSupport() {
    }

    static String ethAccountXpub() {
        return accountXpub(60);
    }

    static String tronAccountXpub() {
        return accountXpub(195);
    }

    private static String accountXpub(int coinType) {
        Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(SEED);
        Bip32ECKeyPair account = Bip32ECKeyPair.deriveKeyPair(master, new int[]{
                44 | HARDENED,
                coinType | HARDENED,
                0 | HARDENED
        });
        return serializeXpub(account);
    }

    private static String serializeXpub(Bip32ECKeyPair keyPair) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{0x04, (byte) 0x88, (byte) 0xB2, 0x1E});
            out.write((byte) keyPair.getDepth());
            out.write(ByteBuffer.allocate(4).putInt(keyPair.getParentFingerprint()).array());
            out.write(ByteBuffer.allocate(4).putInt(keyPair.getChildNumber()).array());
            out.write(keyPair.getChainCode());
            out.write(keyPair.getPublicKeyPoint().getEncoded(true));
            return Base58Check.bytesToBase58(out.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize gateway test xpub", exception);
        }
    }
}
