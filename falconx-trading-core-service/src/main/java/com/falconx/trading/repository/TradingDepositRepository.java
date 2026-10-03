package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingDeposit;
import com.falconx.trading.entity.TradingDepositStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 业务入金仓储接口。
 *
 * <p>该接口负责保存 `t_deposit` 业务事实，
 * 并以 `walletTxId` 作为最小业务幂等查询入口。
 */
public interface TradingDepositRepository {

    /**
     * 按 wallet owner 主键查询业务入金。
     */
    Optional<TradingDeposit> findByWalletTxId(Long walletTxId);

    /**
     * 保存业务入金事实。
     */
    TradingDeposit save(TradingDeposit deposit);

    /**
     * STAGE-11-OBS-RECON §11.4：admin 对账查询，按 chain / token / status / 时间窗 + 分页。
     */
    List<TradingDeposit> findForRecon(String chain,
                                       String token,
                                       TradingDepositStatus status,
                                       OffsetDateTime fromCreatedAt,
                                       OffsetDateTime toCreatedAt,
                                       int offset,
                                       int limit);
}
