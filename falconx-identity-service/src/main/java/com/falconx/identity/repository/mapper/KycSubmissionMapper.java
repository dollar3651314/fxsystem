package com.falconx.identity.repository.mapper;

import com.falconx.identity.repository.mapper.record.KycDocumentRecord;
import com.falconx.identity.repository.mapper.record.KycSubmissionRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KycSubmissionMapper {

    int insertSubmission(KycSubmissionRecord record);

    int insertDocument(KycDocumentRecord record);

    KycSubmissionRecord selectById(@Param("id") long id);

    KycSubmissionRecord selectLatestByUserId(@Param("userId") long userId);

    /** 用户当前是否有 PENDING 申请（防止重复提交）。 */
    Integer countPendingByUserId(@Param("userId") long userId);

    List<KycSubmissionRecord> selectAdminPaginated(@Param("status") Integer status,
                                                    @Param("userId") Long userId,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    long countAdminFiltered(@Param("status") Integer status, @Param("userId") Long userId);

    List<KycDocumentRecord> selectDocumentsBySubmission(@Param("submissionId") long submissionId);

    int updateReview(@Param("id") long id,
                     @Param("status") int status,
                     @Param("reviewerId") long reviewerId,
                     @Param("reviewAt") LocalDateTime reviewAt,
                     @Param("rejectReason") String rejectReason);

    int updateUserKycLevel(@Param("userId") long userId, @Param("level") int level);
}
