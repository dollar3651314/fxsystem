package com.falconx.console.repository;

import com.falconx.console.entity.AdminCustomer;
import com.falconx.console.entity.AdminCustomerProfile;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-2-CUSTOMER 客户跨 schema 只读 Repository。
 */
public interface AdminCustomerRepository {

    /** 分页查询客户列表（按 created_at DESC）。 */
    List<AdminCustomer> findCustomers(String emailFragment,
                                      List<String> statusNames,
                                      OffsetDateTime fromCreatedAt,
                                      OffsetDateTime toCreatedAt,
                                      int offset,
                                      int limit);

    /** 计数（同筛选条件）。 */
    long countCustomers(String emailFragment,
                        List<String> statusNames,
                        OffsetDateTime fromCreatedAt,
                        OffsetDateTime toCreatedAt);

    /** 详情查询。 */
    Optional<AdminCustomer> findCustomerById(long userId);

    /** STAGE-1B-USER-PROFILE：详情专用，跨 schema 单查 t_user_profile。 */
    Optional<AdminCustomerProfile> findCustomerProfileById(long userId);
}
