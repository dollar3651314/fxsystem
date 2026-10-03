package com.falconx.identity.repository;

import com.falconx.identity.entity.RefreshTokenSession;
import com.falconx.identity.repository.mapper.RefreshTokenSessionMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token 会话 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责把 Refresh Token 的一次性使用状态落到
 * `t_refresh_token_session`，不再通过字符串 SQL 直接访问数据库。
 */
@Repository
public class MybatisRefreshTokenSessionRepository implements RefreshTokenSessionRepository {

    private final RefreshTokenSessionMapper refreshTokenSessionMapper;

    public MybatisRefreshTokenSessionRepository(RefreshTokenSessionMapper refreshTokenSessionMapper) {
        this.refreshTokenSessionMapper = refreshTokenSessionMapper;
    }

    @Override
    public RefreshTokenSession save(RefreshTokenSession session) {
        OffsetDateTime usedAt = session.used() ? OffsetDateTime.now() : null;
        refreshTokenSessionMapper.upsertRefreshTokenSession(IdentityMybatisSupport.toRecord(session, usedAt));
        return session;
    }

    @Override
    public Optional<RefreshTokenSession> findByJti(String jti) {
        return Optional.ofNullable(IdentityMybatisSupport.toDomain(refreshTokenSessionMapper.selectByJti(jti)));
    }

    @Override
    public Optional<RefreshTokenSession> findByJtiForUpdate(String jti) {
        return Optional.ofNullable(IdentityMybatisSupport.toDomain(refreshTokenSessionMapper.selectByJtiForUpdate(jti)));
    }

    @Override
    public boolean markUsedIfUnused(String jti) {
        return refreshTokenSessionMapper.markUsedIfUnused(
                jti,
                IdentityMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        ) == 1;
    }

    @Override
    public int revokeAllByUserId(long userId) {
        return refreshTokenSessionMapper.revokeAllByUserId(
                userId,
                IdentityMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }
}
