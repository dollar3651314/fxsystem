package com.falconx.wallet.service.impl;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.service.WalletAddressDerivationService;
import com.falconx.wallet.service.model.WalletDerivedAddress;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.tron.trident.utils.Base58Check;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * 基于 account-level xpub 的 USDT 入金地址派生服务。
 */
public class XpubWalletAddressDerivationService implements WalletAddressDerivationService {

    private static final byte[] XPUB_VERSION = new byte[]{0x04, (byte) 0x88, (byte) 0xB2, 0x1E};
    private static final int XPUB_PAYLOAD_LENGTH = 78;
    private static final int HARDENED_BIT = 0x80000000;
    private static final String TOKEN_USDT = "USDT";

    private final WalletServiceProperties properties;

    public XpubWalletAddressDerivationService(WalletServiceProperties properties) {
        this.properties = properties;
    }

    @Override
    public WalletDerivedAddress deriveAddress(ChainType chain, int addressIndex) {
        if (addressIndex < 0) {
            throw allocationFailed(chain, "invalid_address_index");
        }
        return switch (chain) {
            case ETH -> deriveEth(addressIndex);
            case TRON -> deriveTron(addressIndex);
            default -> throw new WalletBusinessException(
                    WalletErrorCode.UNSUPPORTED_CHAIN,
                    Map.of("chain", chain.name())
            );
        };
    }

    private WalletDerivedAddress deriveEth(int addressIndex) {
        ECPoint childPoint = deriveReceivingPoint(
                ChainType.ETH,
                requiredXpub(ChainType.ETH, properties.getDerivation().getEthAccountXpub()),
                addressIndex
        );
        byte[] address20 = ethereumAddressBytes(childPoint);
        String address = Keys.toChecksumAddress(Numeric.toHexStringNoPrefix(address20));
        return new WalletDerivedAddress(
                ChainType.ETH,
                TOKEN_USDT,
                "ERC20",
                address,
                addressIndex,
                "m/44'/60'/0'/0/" + addressIndex
        );
    }

    private WalletDerivedAddress deriveTron(int addressIndex) {
        ECPoint childPoint = deriveReceivingPoint(
                ChainType.TRON,
                requiredXpub(ChainType.TRON, properties.getDerivation().getTronAccountXpub()),
                addressIndex
        );
        byte[] address20 = ethereumAddressBytes(childPoint);
        byte[] tronAddress = new byte[21];
        tronAddress[0] = 0x41;
        System.arraycopy(address20, 0, tronAddress, 1, address20.length);
        return new WalletDerivedAddress(
                ChainType.TRON,
                TOKEN_USDT,
                "TRC20",
                Base58Check.bytesToBase58(tronAddress),
                addressIndex,
                "m/44'/195'/0'/0/" + addressIndex
        );
    }

    private String requiredXpub(ChainType chain, String xpub) {
        if (xpub == null || xpub.isBlank()) {
            throw allocationFailed(chain, "missing_account_xpub");
        }
        return xpub.trim();
    }

    private ECPoint deriveReceivingPoint(ChainType chain, String accountXpub, int addressIndex) {
        ExtendedPublicKey accountKey = parseAccountXpub(chain, accountXpub);
        ExtendedPublicKey receivingKey = deriveNonHardenedChild(chain, accountKey, 0);
        return deriveNonHardenedChild(chain, receivingKey, addressIndex).publicKeyPoint();
    }

    private ExtendedPublicKey parseAccountXpub(ChainType chain, String accountXpub) {
        byte[] payload;
        try {
            payload = Base58Check.base58ToBytes(accountXpub);
        } catch (RuntimeException exception) {
            throw allocationFailed(chain, "invalid_account_xpub");
        }
        if (payload.length != XPUB_PAYLOAD_LENGTH || !Arrays.equals(Arrays.copyOf(payload, 4), XPUB_VERSION)) {
            throw allocationFailed(chain, "invalid_account_xpub");
        }
        byte[] chainCode = Arrays.copyOfRange(payload, 13, 45);
        byte[] compressedPublicKey = Arrays.copyOfRange(payload, 45, 78);
        if (compressedPublicKey.length != 33
                || (compressedPublicKey[0] != 0x02 && compressedPublicKey[0] != 0x03)) {
            throw allocationFailed(chain, "invalid_account_xpub_public_key");
        }
        try {
            ECPoint point = Sign.CURVE_PARAMS.getCurve().decodePoint(compressedPublicKey).normalize();
            return new ExtendedPublicKey(point, chainCode);
        } catch (RuntimeException exception) {
            throw allocationFailed(chain, "invalid_account_xpub_public_key");
        }
    }

    private ExtendedPublicKey deriveNonHardenedChild(ChainType chain, ExtendedPublicKey parent, int childIndex) {
        if ((childIndex & HARDENED_BIT) != 0) {
            throw allocationFailed(chain, "hardened_child_not_supported");
        }
        byte[] data = ByteBuffer.allocate(37)
                .put(parent.publicKeyPoint().getEncoded(true))
                .putInt(childIndex)
                .array();
        byte[] digest = hmacSha512(parent.chainCode(), data);
        byte[] left = Arrays.copyOfRange(digest, 0, 32);
        byte[] childChainCode = Arrays.copyOfRange(digest, 32, 64);
        BigInteger tweak = new BigInteger(1, left);
        BigInteger curveOrder = Sign.CURVE_PARAMS.getN();
        if (tweak.signum() <= 0 || tweak.compareTo(curveOrder) >= 0) {
            throw allocationFailed(chain, "invalid_child_key");
        }
        ECPoint childPoint = Sign.CURVE_PARAMS.getG()
                .multiply(tweak)
                .add(parent.publicKeyPoint())
                .normalize();
        if (childPoint.isInfinity()) {
            throw allocationFailed(chain, "invalid_child_key");
        }
        return new ExtendedPublicKey(childPoint, childChainCode);
    }

    private byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA512 is not available", exception);
        }
    }

    private byte[] ethereumAddressBytes(ECPoint publicKeyPoint) {
        byte[] uncompressedPublicKey = publicKeyPoint.getEncoded(false);
        byte[] publicKeyWithoutPrefix = Arrays.copyOfRange(uncompressedPublicKey, 1, uncompressedPublicKey.length);
        return Arrays.copyOfRange(Hash.sha3(publicKeyWithoutPrefix), 12, 32);
    }

    private WalletBusinessException allocationFailed(ChainType chain, String reason) {
        return new WalletBusinessException(
                WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED,
                Map.of("chain", chain.name(), "reason", reason)
        );
    }

    private record ExtendedPublicKey(ECPoint publicKeyPoint, byte[] chainCode) {

        private ExtendedPublicKey {
            chainCode = Arrays.copyOf(chainCode, chainCode.length);
        }
    }
}
