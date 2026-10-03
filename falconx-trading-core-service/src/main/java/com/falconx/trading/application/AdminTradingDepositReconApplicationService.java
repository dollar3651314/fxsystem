package com.falconx.trading.application;

import com.falconx.trading.api.AdminTradingDepositReconItem;
import com.falconx.trading.api.AdminTradingDepositReconListResponse;
import com.falconx.trading.entity.TradingDeposit;
import com.falconx.trading.entity.TradingDepositStatus;
import com.falconx.trading.repository.TradingDepositRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-11-OBS-RECON §11.4：trading-core admin 入金对账查询应用服务（只读）。
 */
@Service
public class AdminTradingDepositReconApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminTradingDepositReconApplicationService.class);

    private final TradingDepositRepository repository;

    public AdminTradingDepositReconApplicationService(TradingDepositRepository repository) {
        this.repository = repository;
    }

    public AdminTradingDepositReconListResponse list(String chain, String token, String status,
                                                      OffsetDateTime fromCreatedAt, OffsetDateTime toCreatedAt,
                                                      int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 500);
        TradingDepositStatus enumStatus = parseStatus(status);
        List<TradingDeposit> rows = repository.findForRecon(
                normalize(chain), normalize(token), enumStatus,
                fromCreatedAt, toCreatedAt,
                (safePage - 1) * safeSize, safeSize);
        List<AdminTradingDepositReconItem> items = rows.stream().map(AdminTradingDepositReconApplicationService::toItem).toList();
        return new AdminTradingDepositReconListResponse(safePage, safeSize, items.size(), items);
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static TradingDepositStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TradingDepositStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("trading.admin.recon.status.invalid raw={}", raw);
            return null;
        }
    }

    private static AdminTradingDepositReconItem toItem(TradingDeposit d) {
        return new AdminTradingDepositReconItem(
                d.depositId(),
                d.walletTxId(),
                d.userId(),
                d.chain() == null ? null : d.chain().name(),
                d.token(),
                d.txHash(),
                d.amount(),
                d.status() == null ? null : d.status().name(),
                d.creditedAt(),
                d.reversedAt()
        );
    }
}
