package com.falconx.console.security;

import com.falconx.console.config.ConsoleServiceProperties;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.security.AdminTokenSupport.IssuedToken;
import com.falconx.console.security.AdminTokenSupport.ValidatedRefreshToken;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link RsaAdminTokenSupport} 单元测试，覆盖 R6 §4 token 校验路径（不依赖 Spring 容器）。
 */
class RsaAdminTokenSupportTests {

    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDc3xftBiG+MABF
            4D5tnqUEowtS9fZjSGQHIvM3LAJ3pCY2p/NuHVCE1YUiOY6MZiKaeQgfoPCMRdiq
            oa0RIa18q/7it2Ybhapov2LGYW9nxSfBU34jan/1fstCiQFWFWgzYqEwX/vnirDZ
            olhP/gOQxopd3M97ucAi9On2JWj9bwvhAbOIpLFV+lCM6oCmYqGpS3XSKTS4592p
            iwu7NKPF6EOqh8MMfYTNJl6ZMsLqObQ8y44B53xEZLIie2GeNMfEsAzuJImQdPMf
            wcJnXXb8+eYEKXKJBEr61u21QcEx3I0p+i8cPInZsqA56lDcVvYuK2e1SDgmxI9P
            VpupSFLBAgMBAAECggEAAjzgaS5euSaTWiHjgAjTczCTtMt5K5hYKxpXjH+Nptiq
            6nLZLIxvfGkNaENdBl8iip4fTvK6fTFX+KKatkm5EEJN2s4w4qaAC3+k6I2kK1D8
            DiFibclucnZOaOYqdUzSOXMOXwcZ7kahdBMJAgZ40sawMDNEhuRi5ffFRxEp9ydc
            DVLHUKA8EmzUS7+t7PtoJwBIsWnJV+tdHB1C32u+MQY1Hf9kC8fAUr1Prs/z9K5l
            Oe1KgYWO0swh0g8h7Z9vLHo4ZqX8qkt/lXaFySmwJwYT/fsVojc6CVfqOZAwY6la
            1fYOGIyULqBDPrBLu4Bd8Ylomw5Y0owo3smHlCUp4QKBgQD1V3eZLByJAMkhFy81
            ag3UUcZFT8dMXFwYSiOEZAuvUatiJU3oe9JTR1GjQh+MloMm6RlGibRtg4Bcd51o
            lDqIZKnyTNAqmNXUcV0m0fsLWEuHQzsXJk+rPoA+WxoEZAxaPTb1zznFPbMl1BW4
            zgMF/OkwErCt37LmzJRbNcmMEwKBgQDmd3v6NJvvygyqnEuctzDlFpS6pnBEO+NH
            HbgcqviGqg9VGp7ZV6/Hsex4+q9yOiHEuIJOGI90Zk6Bi6gAdWdvN2PWcJoIf0Tz
            IT5X2XFVx0qCM2hWQf0CNvWx3dngVNoIAgJuRc+YMWq1Z4y4hQlbaQbJ9kXaICLY
            uVoDqS9YWwKBgQDorV1lzSn63N3jHiPNmpknBa7uSS0QRH+rIZTxmBhk2yWY3Rw4
            IkZkaL0KAkn9gTk9C9DGzw5o1lBEYcTNS9b/R8jNXQAHhg81fZYEnRxjtAddbut5
            lwHzvEDP4oKYK3Jzmp6nHTMC1vMyKyO2stq3MRbOWsto+0CmFtuUbTyKNQKBgHom
            E+id35Qs45+9bPnnwht5Z2Sx+EjB8QjtQHq5RzWghrXVgSGyrvDJZYsNWtXQ57rr
            C+02aToJS0yv52Au2Z6BngG29nzQb4vpL7DCB6auFNiDRKaLHP0CgiA+dE7IyjJ6
            Vi16BLgmYOc6tcPKhxYSyU1boNQmOjHhs0rDbduHAoGBAK9OVaS30xhusIajVsg8
            CtlAuNhVPrIFUc8f5tUYb014plNb1gkPxDomDCsh0TSm2pyqAKt4QqaMI6GwOtb4
            jUcXDAtivhm8wjDLZiNiaYW7sVYlKcWG2lwMjMAoLGeCLpJngEqW1Xvkke1yfrEl
            4i3w2eVr/q3/FZ8i00dj89/u
            -----END PRIVATE KEY-----
            """;

    private static final String PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3N8X7QYhvjAAReA+bZ6l
            BKMLUvX2Y0hkByLzNywCd6QmNqfzbh1QhNWFIjmOjGYimnkIH6DwjEXYqqGtESGt
            fKv+4rdmG4WqaL9ixmFvZ8UnwVN+I2p/9X7LQokBVhVoM2KhMF/754qw2aJYT/4D
            kMaKXdzPe7nAIvTp9iVo/W8L4QGziKSxVfpQjOqApmKhqUt10ik0uOfdqYsLuzSj
            xehDqofDDH2EzSZemTLC6jm0PMuOAed8RGSyInthnjTHxLAM7iSJkHTzH8HCZ112
            /PnmBClyiQRK+tbttUHBMdyNKfovHDyJ2bKgOepQ3Fb2LitntUg4JsSPT1abqUhS
            wQIDAQAB
            -----END PUBLIC KEY-----
            """;

    private RsaAdminTokenSupport tokenSupport;
    private ConsoleServiceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ConsoleServiceProperties();
        properties.getKeyPair().setPrivateKeyPem(PRIVATE_KEY_PEM);
        properties.getKeyPair().setPublicKeyPem(PUBLIC_KEY_PEM);
        properties.getToken().setAccessTokenTtl(Duration.ofMinutes(30));
        properties.getToken().setRefreshTokenTtl(Duration.ofHours(8));
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        tokenSupport = new RsaAdminTokenSupport(properties, objectMapper);
    }

    @Test
    void shouldIssueAndVerifyAccessTokenRoundtrip() {
        IssuedToken issued = tokenSupport.issueAccessToken(
                100L, "alice", List.of("FINANCE"), false);
        AdminPrincipal principal = tokenSupport.parseAndVerifyAccessToken(issued.token());

        Assertions.assertEquals(100L, principal.adminUserId());
        Assertions.assertEquals("alice", principal.username());
        Assertions.assertEquals(List.of("FINANCE"), principal.roles());
        Assertions.assertEquals(issued.jti(), principal.jti());
        Assertions.assertFalse(principal.mustChangePassword());
        Assertions.assertFalse(principal.isSuperAdmin());
    }

    @Test
    void shouldMarkSuperAdminWhenRolesContainSuperAdmin() {
        IssuedToken issued = tokenSupport.issueAccessToken(
                1L, "superadmin", List.of("SUPER_ADMIN"), true);
        AdminPrincipal principal = tokenSupport.parseAndVerifyAccessToken(issued.token());

        Assertions.assertTrue(principal.isSuperAdmin());
        Assertions.assertTrue(principal.mustChangePassword());
    }

    @Test
    void shouldRejectAccessTokenAsRefreshToken() {
        IssuedToken access = tokenSupport.issueAccessToken(
                100L, "alice", List.of("FINANCE"), false);
        AdminBusinessException ex = Assertions.assertThrows(AdminBusinessException.class,
                () -> tokenSupport.parseAndVerifyRefreshToken(access.token()));
        Assertions.assertEquals(AdminErrorCode.ADMIN_TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void shouldRejectRefreshTokenAsAccessToken() {
        IssuedToken refresh = tokenSupport.issueRefreshToken(100L);
        AdminBusinessException ex = Assertions.assertThrows(AdminBusinessException.class,
                () -> tokenSupport.parseAndVerifyAccessToken(refresh.token()));
        Assertions.assertEquals(AdminErrorCode.ADMIN_TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void shouldRejectMalformedToken() {
        AdminBusinessException ex = Assertions.assertThrows(AdminBusinessException.class,
                () -> tokenSupport.parseAndVerifyAccessToken("not.a.valid.jwt.token"));
        Assertions.assertEquals(AdminErrorCode.ADMIN_TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void shouldRejectTokenWithTamperedPayload() {
        IssuedToken access = tokenSupport.issueAccessToken(
                100L, "alice", List.of("FINANCE"), false);
        // 篡改 payload 部分
        String[] parts = access.token().split("\\.");
        String tampered = parts[0] + ".dGFtcGVyZWQ.invalid"; // 替换 payload 部分
        AdminBusinessException ex = Assertions.assertThrows(AdminBusinessException.class,
                () -> tokenSupport.parseAndVerifyAccessToken(tampered));
        Assertions.assertEquals(AdminErrorCode.ADMIN_TOKEN_INVALID, ex.getErrorCode());
    }

    @Test
    void shouldVerifyRefreshTokenSubAndJti() {
        IssuedToken refresh = tokenSupport.issueRefreshToken(200L);
        ValidatedRefreshToken validated = tokenSupport.parseAndVerifyRefreshToken(refresh.token());

        Assertions.assertEquals(200L, validated.adminUserId());
        Assertions.assertEquals(refresh.jti(), validated.jti());
        Assertions.assertNotNull(validated.expiresAt());
    }
}
