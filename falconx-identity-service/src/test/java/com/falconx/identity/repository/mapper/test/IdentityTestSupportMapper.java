package com.falconx.identity.repository.mapper.test;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * identity-service 测试专用 Mapper。
 *
 * <p>该 Mapper 只在测试源码中存在，用来完成 Stage 5 集成测试的清表和结果断言。
 */
@Mapper
public interface IdentityTestSupportMapper {

    default void clearOwnerTables() {
        deleteInbox();
        deleteRefreshTokenSession();
        deleteKycDocument();
        deleteKycSubmission();
        deleteUser();
    }

    int deleteInbox();

    int deleteRefreshTokenSession();

    int deleteUser();

    int deleteKycSubmission();

    int deleteKycDocument();

    Integer selectKycLevelByUserId(@Param("userId") Long userId);

    Integer countKycSubmissionByUserId(@Param("userId") Long userId);

    Integer countKycDocumentBySubmissionId(@Param("submissionId") Long submissionId);

    Integer selectKycSubmissionStatusById(@Param("submissionId") Long submissionId);

    String selectKycRejectReasonById(@Param("submissionId") Long submissionId);

    Long selectKycReviewerIdById(@Param("submissionId") Long submissionId);

    String selectKycReviewAtById(@Param("submissionId") Long submissionId);

    String selectKycDocumentSha256(@Param("submissionId") Long submissionId,
                                    @Param("docType") int docType);

    int insertActiveUser(@Param("userId") Long userId,
                          @Param("uid") String uid,
                          @Param("email") String email,
                          @Param("passwordHash") String passwordHash);

    int forcePendingSubmissionToApproved(@Param("submissionId") Long submissionId);

    Integer selectUserStatusById(@Param("userId") Long userId);

    Integer countRefreshTokenSession();

    Integer countUsedRefreshTokenSession();

    Integer countProcessedInboxByEventId(@Param("eventId") String eventId);

    Integer countUsersById(@Param("userId") Long userId);

    String selectActivatedAtByUserId(@Param("userId") Long userId);

    Boolean selectEmailVerifiedByUserId(@Param("userId") Long userId);

    String selectProcessedInboxPayloadJsonByEventId(@Param("eventId") String eventId);

    int insertPendingDepositUser(@Param("userId") Long userId,
                                 @Param("uid") String uid,
                                 @Param("email") String email,
                                 @Param("passwordHash") String passwordHash);
}
