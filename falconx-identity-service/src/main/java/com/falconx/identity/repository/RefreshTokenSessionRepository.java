package com.falconx.identity.repository;

import com.falconx.identity.entity.RefreshTokenSession;
import java.util.Optional;

/**
 * Refresh Token 会话仓储抽象。
 *
 * <p>该仓储负责刷新令牌会话的保存、查询和一次性使用标记。
 * 正式数据库实现统一通过 `MyBatis Mapper + XML` 承载 SQL。
 */
public interface RefreshTokenSessionRepository {

    /**
     * 保存新的 Refresh Token 会话。
     *
     * @param session 会话对象
     * @return 持久化后的会话对象
     */
    RefreshTokenSession save(RefreshTokenSession session);

    /**
     * 按 jti 查询会话。
     *
     * @param jti token 唯一 ID
     * @return 会话对象
     */
    Optional<RefreshTokenSession> findByJti(String jti);

    /**
     * 按 jti 查询会话并对结果行加悲观锁。
     *
     * @param jti token 唯一 ID
     * @return 加锁后的会话对象
     */
    Optional<RefreshTokenSession> findByJtiForUpdate(String jti);

    /**
     * 将会话标记为已使用。
     *
     * @param jti token 唯一 ID
     */
    boolean markUsedIfUnused(String jti);

    /**
     * 批量撤销指定用户的所有未使用 refresh token 会话。
     *
     * <p>由 {@code STAGE-2-CUSTOMER} 管理员冻结客户 / 重置密码 / 改密时调用。
     *
     * @param userId 用户主键
     * @return 实际被撤销的会话数（已 used 不计入）
     */
    int revokeAllByUserId(long userId);
}
