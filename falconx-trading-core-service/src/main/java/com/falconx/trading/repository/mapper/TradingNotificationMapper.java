package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingNotificationRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradingNotificationMapper {

    int insert(TradingNotificationRecord record);

    TradingNotificationRecord selectById(@Param("id") Long id);

    List<TradingNotificationRecord> selectByUserId(@Param("userId") Long userId,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);

    int countUnreadByUserId(@Param("userId") Long userId);

    /** 单条标记已读，幂等（status 已是 1 时不更新 read_at）。返回影响行数。 */
    int markRead(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("readAt") LocalDateTime readAt);

    /** 整批标记已读（用户点"全部标为已读"）。返回影响行数。 */
    int markAllRead(@Param("userId") Long userId,
                    @Param("readAt") LocalDateTime readAt);

    /** STAGE-6-KYC Phase 4：按 relatedKey + relatedId 查重；返回是否存在记录。 */
    int countByRelated(@Param("relatedKey") String relatedKey,
                        @Param("relatedId") Long relatedId);

    /** STAGE-8-NOTIFICATION Phase 3：管理端跨用户多条件分页。 */
    List<TradingNotificationRecord> selectByAdminFilters(@Param("userId") Long userId,
                                                          @Param("type") String type,
                                                          @Param("templateCode") String templateCode,
                                                          @Param("levelCode") Integer levelCode,
                                                          @Param("statusCode") Integer statusCode,
                                                          @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                                                          @Param("toCreatedAt") LocalDateTime toCreatedAt,
                                                          @Param("offset") int offset,
                                                          @Param("limit") int limit);

    /** STAGE-8-NOTIFICATION Phase 3：管理端 count。 */
    long countByAdminFilters(@Param("userId") Long userId,
                              @Param("type") String type,
                              @Param("templateCode") String templateCode,
                              @Param("levelCode") Integer levelCode,
                              @Param("statusCode") Integer statusCode,
                              @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                              @Param("toCreatedAt") LocalDateTime toCreatedAt);
}
