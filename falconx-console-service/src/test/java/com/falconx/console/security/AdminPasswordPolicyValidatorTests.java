package com.falconx.console.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link AdminPasswordPolicyValidator} 单元测试，覆盖 R6 TC-CONSOLE-021/022/023 密码策略规则。
 */
class AdminPasswordPolicyValidatorTests {

    @Test
    void shouldAcceptValidPasswordWithAllRequiredCategories() {
        Assertions.assertTrue(AdminPasswordPolicyValidator.isValid(
                "Abc123!@#$XYZ", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordShorterThan12Chars() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "Abc1!", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordMissingUppercase() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "abc123!@#$xyz", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordMissingLowercase() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "ABC123!@#$XYZ", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordMissingDigit() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "AbcDef!@#$XYZ", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordMissingSpecialChar() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "Abc123DefXYZK", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldRejectPasswordEqualToOldPassword() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "Abc123!@#$XYZ", "Abc123!@#$XYZ", "alice"));
    }

    @Test
    void shouldRejectPasswordEqualToUsername() {
        Assertions.assertFalse(AdminPasswordPolicyValidator.isValid(
                "alice", "OldPassw0rd!", "alice"));
    }

    @Test
    void shouldAcceptWhenOldPasswordIsNull() {
        Assertions.assertTrue(AdminPasswordPolicyValidator.isValid(
                "Abc123!@#$XYZ", null, "alice"));
    }
}
