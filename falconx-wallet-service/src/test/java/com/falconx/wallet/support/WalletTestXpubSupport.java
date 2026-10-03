package com.falconx.wallet.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.trident.utils.Base58Check;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * 钱包地址派生测试支撑。
 */
public final class WalletTestXpubSupport {

    private static final int HARDENED = Bip32ECKeyPair.HARDENED_BIT;
    private static final byte[] SEED = Hash.sha256("falconx-wallet-deposit-address-it".getBytes(StandardCharsets.UTF_8));

    private WalletTestXpubSupport() {
    }

    public static String ethAccountXpub() {
        return accountXpub(60);
    }

    public static String tronAccountXpub() {
        return accountXpub(195);
    }

    public static String expectedEthAddress(int addressIndex) {
        Bip32ECKeyPair child = derivePrivateChild(60, addressIndex);
        return Keys.toChecksumAddress(Keys.getAddress(child));
    }

    public static String expectedTronAddress(int addressIndex) {
        Bip32ECKeyPair child = derivePrivateChild(195, addressIndex);
        byte[] publicKey = Numeric.toBytesPadded(child.getPublicKey(), 64);
        byte[] address20 = Arrays.copyOfRange(Hash.sha3(publicKey), 12, 32);
        byte[] tronAddress = new byte[21];
        tronAddress[0] = 0x41;
        System.arraycopy(address20, 0, tronAddress, 1, address20.length);
        return Base58Check.bytesToBase58(tronAddress);
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

    private static Bip32ECKeyPair derivePrivateChild(int coinType, int addressIndex) {
        Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(SEED);
        return Bip32ECKeyPair.deriveKeyPair(master, new int[]{
                44 | HARDENED,
                coinType | HARDENED,
                0 | HARDENED,
                0,
                addressIndex
        });
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
            throw new IllegalStateException("Unable to serialize test xpub", exception);
        }
    }
}
