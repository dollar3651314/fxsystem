package com.falconx.wallet.kms;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * STAGE-7-WITHDRAW Phase 3 R6：TC-WD-102 KmsSignerStub 调用直接抛 UnsupportedOperationException。
 */
class KmsSignerStubTests {

    private final KmsSigner signer = new KmsSignerStub();

    @Test
    void shouldThrowUnsupportedOnSign() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> signer.sign("ERC20", "0xabc", new byte[]{0x01, 0x02, 0x03}));
    }

    @Test
    void shouldReturnFalseForSupportsRegardlessOfNetwork() {
        Assertions.assertFalse(signer.supports("ERC20"));
        Assertions.assertFalse(signer.supports("TRC20"));
        Assertions.assertFalse(signer.supports(null));
    }
}
