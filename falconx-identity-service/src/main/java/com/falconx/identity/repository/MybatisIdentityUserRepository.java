package com.falconx.identity.repository;

import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.repository.mapper.IdentityUserMapper;
import com.falconx.identity.repository.mapper.record.IdentityUserRecord;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.id.PublicIdentifierFormatter;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * identity 用户 Repository 的 MyBatis 实现。
 *
 * <p>该实现负责把注册、登录和用户状态归一化链路落到 `t_user`，
 * 同时保持领域实体与数据库记录之间的清晰分层。
 */
@Repository
public class MybatisIdentityUserRepository implements IdentityUserRepository {

    private final IdentityUserMapper identityUserMapper;
    private final IdGenerator idGenerator;

    public MybatisIdentityUserRepository(IdentityUserMapper identityUserMapper, IdGenerator idGenerator) {
        this.identityUserMapper = identityUserMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<IdentityUser> findByEmail(String email) {
        return Optional.ofNullable(IdentityMybatisSupport.toDomain(identityUserMapper.selectByEmail(email)));
    }

    @Override
    public Optional<IdentityUser> findById(long userId) {
        return Optional.ofNullable(IdentityMybatisSupport.toDomain(identityUserMapper.selectById(userId)));
    }

    @Override
    public IdentityUser save(IdentityUser user) {
        if (user.id() == null) {
            long id = idGenerator.nextId();
            OffsetDateTime now = user.createdAt() == null ? OffsetDateTime.now() : user.createdAt();
            IdentityUser persisted = new IdentityUser(
                    id,
                    PublicIdentifierFormatter.userUid(id),
                    user.email(),
                    user.passwordHash(),
                    user.status(),
                    IdentityMybatisSupport.normalizeGroupCode(user.groupCode()),
                    user.emailVerified(),
                    user.activatedAt(),
                    user.lastLoginAt(),
                    now,
                    user.updatedAt() == null ? now : user.updatedAt()
            );
            identityUserMapper.insertIdentityUser(IdentityMybatisSupport.toRecord(persisted));
            return persisted;
        }

        IdentityUser persisted = new IdentityUser(
                user.id(),
                user.uid(),
                user.email(),
                user.passwordHash(),
                user.status(),
                IdentityMybatisSupport.normalizeGroupCode(user.groupCode()),
                user.emailVerified(),
                user.activatedAt(),
                user.lastLoginAt(),
                user.createdAt(),
                user.updatedAt() == null ? OffsetDateTime.now() : user.updatedAt()
        );
        identityUserMapper.updateIdentityUser(IdentityMybatisSupport.toRecord(persisted));
        return persisted;
    }

    @Override
    public Optional<Integer> findKycLevelByUserId(long userId) {
        return Optional.ofNullable(identityUserMapper.selectKycLevelById(userId));
    }

    @Override
    public int updateAdminFields(long userId,
                                 String email,
                                 Integer emailVerified,
                                 String groupCode,
                                 Integer kycLevel,
                                 Integer statusCode) {
        return identityUserMapper.updateAdminFields(userId, email, emailVerified, groupCode, kycLevel, statusCode);
    }
}
