package com.falconx.console.security;

/**
 * 管理员密码强度策略校验工具。
 *
 * <p>策略与 [`管理端 5 页面方案`](../../../../../../../../docs/design/falconx-console-pages-V1.md) §1.3 保持一致：
 *
 * <ul>
 *   <li>长度 ≥ 12</li>
 *   <li>至少 1 个大写字母</li>
 *   <li>至少 1 个小写字母</li>
 *   <li>至少 1 个数字</li>
 *   <li>至少 1 个特殊字符（{@code !@#$%^&*()_+-=[]{};:'\"\\|,.<>/?`~}）</li>
 *   <li>不得与旧密码相同</li>
 *   <li>不得与用户名相同</li>
 * </ul>
 */
public final class AdminPasswordPolicyValidator {

    private static final int MIN_LENGTH = 12;
    private static final String SPECIAL_CHARS = "!@#$%^&*()_+-=[]{};:'\"\\|,.<>/?`~";

    private AdminPasswordPolicyValidator() {
    }

    /**
     * 校验密码是否符合策略。
     *
     * @param newPassword 新密码（明文）
     * @param oldPassword 旧密码（明文，可空）
     * @param username 登录用户名
     * @return true 表示通过；false 表示拒绝（不区分具体不符合点；前端有更详细的实时提示）
     */
    public static boolean isValid(String newPassword, String oldPassword, String username) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            return false;
        }
        if (username != null && newPassword.equals(username)) {
            return false;
        }
        if (oldPassword != null && newPassword.equals(oldPassword)) {
            return false;
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (char c : newPassword.toCharArray()) {
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (SPECIAL_CHARS.indexOf(c) >= 0) {
                hasSpecial = true;
            }
        }
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }
}
