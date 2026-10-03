package com.falconx.identity.repository.mapper;

import com.falconx.identity.repository.mapper.record.RefreshTokenSessionRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Refresh Token 会话 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `falconx_identity.t_refresh_token_session` 的 SQL 声明，
 * 让 Refresh Token 一次性使用语义可以通过 XML SQL 明确表达。
 */
@Mapper
public interface RefreshTokenSessionMapper {

    /**
     * 新增或覆盖一条 Refresh Token 会话。
     *
     * @param record Refresh Token 持久化记录
     * @return 影响行数
     */
    int upsertRefreshTokenSession(RefreshTokenSessionRecord record);

    /**
     * 按 jti 查询会话。
     *
     * @param jti Refresh Token 唯一 ID
     * @return 会话记录
     */
    RefreshTokenSessionRecord selectByJti(@Param("jti") String jti);

    /**
     * 按 jti 查询会话并对该行加悲观锁。
     *
     * @param jti Refresh Token 唯一 ID
     * @return 加锁后的会话记录
     */
    RefreshTokenSessionRecord selectByJtiForUpdate(@Param("jti") String jti);

    /**
     * 把指定会话标记为已使用。
     *
     * @param jti 会话唯一 ID
     * @param usedAt 标记使用时间
     * @return 影响行数
     */
    int markUsedIfUnused(@Param("jti") String jti, @Param("usedAt") LocalDateTime usedAt);

    /**
     * 批量撤销指定用户的所有未使用 refresh token 会话。
     *
     * @param userId 用户主键
     * @param usedAt 撤销时间
     * @return 受影响行数
     */
    int revokeAllByUserId(@Param("userId") long userId, @Param("usedAt") LocalDateTime usedAt);
}
