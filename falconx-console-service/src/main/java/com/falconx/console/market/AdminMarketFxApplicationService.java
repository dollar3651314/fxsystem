package com.falconx.console.market;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-14E2 Task3：管理端 FX 实时汇率监控编排。
 *
 * <p>透传 market-service 14A 已上线的 internal RPC
 * {@code GET /internal/v1/market/fx/rates}（全量 8 FX rate 快照）。链路
 * {@code console → gateway → market}：{@link InternalRpcClient} 按 path 打到 gateway
 * baseUrl，gateway 的 {@code internal-market-route}（{@code /internal/v1/market/**}）分发到
 * market-service 并注入 {@code X-Internal-Token}（与 D3b 透传 trading 同机制，仅 path 前缀不同）。
 *
 * <p>边界（照搬 D3b）：console 只透传不直写 market 业务表，纯只读。下游 not-found / 错误
 * （如 market 60010 单对未配置，或全量查询异常）翻译为 console {@code 90940}。
 */
@Service
public class AdminMarketFxApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminMarketFxApplicationService.class);

    /** market 14A 全量 FX 快照 internal RPC path（gateway internal-market-route → market）。 */
    private static final String FX_RATES_PATH = "/internal/v1/market/fx/rates";

    private static final ParameterizedTypeReference<ApiResponse<List<FxRateView>>> FX_RATES_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;

    public AdminMarketFxApplicationService(InternalRpcClient internalRpcClient) {
        this.internalRpcClient = internalRpcClient;
    }

    /** 透传 market 全量 FX rate 快照；下游错误 → 90940。 */
    public List<FxRateView> listFxRates() {
        try {
            List<FxRateView> rates = internalRpcClient.get(FX_RATES_PATH, FX_RATES_TYPE);
            return rates == null ? List.of() : rates;
        } catch (InternalRpcException ex) {
            log.warn("admin.market.fx.rates.downstream-error downstreamCode={} httpStatus={} message={}",
                    ex.getDownstreamCode(), ex.getHttpStatus(), ex.getDownstreamMessage());
            throw new AdminBusinessException(AdminErrorCode.ADMIN_FX_RATE_NOT_FOUND);
        }
    }
}
