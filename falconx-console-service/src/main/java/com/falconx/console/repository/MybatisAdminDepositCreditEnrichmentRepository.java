package com.falconx.console.repository;

import com.falconx.console.repository.mapper.AdminDepositCreditEnrichmentMapper;
import com.falconx.console.repository.mapper.record.AdminDepositCreditRecord;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminDepositCreditEnrichmentRepository implements AdminDepositCreditEnrichmentRepository {

    private final AdminDepositCreditEnrichmentMapper mapper;

    public MybatisAdminDepositCreditEnrichmentRepository(AdminDepositCreditEnrichmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AdminDepositCreditRecord> findByWalletTxIds(List<Long> walletTxIds) {
        if (walletTxIds == null || walletTxIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mapper.selectByWalletTxIds(walletTxIds);
    }
}
