import { useCallback, useEffect, useState } from "react";
import { Radio, Select, message } from "antd";
import { riskApi } from "./riskApi";
import { symbolApi } from "../symbol/symbolApi";
import type { RiskActionType } from "./types";
import type { SymbolQuoteMappingItem } from "../symbol/types";
import { HighRiskConfirmModal } from "../customer/HighRiskConfirmModal";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";
import { ApiError } from "../../lib/api/apiClient";

interface Props {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

const ACTION_OPTIONS: { value: RiskActionType; label: string }[] = [
  { value: "REJECT_OPEN", label: "REJECT_OPEN（禁止新开仓）" },
  { value: "REDUCE_ONLY", label: "REDUCE_ONLY（仅允许减仓）" },
  { value: "SUSPEND_SYMBOL", label: "SUSPEND_SYMBOL（品种全停）" },
  { value: "GLOBAL_PAUSE", label: "GLOBAL_PAUSE（全局暂停所有交易）" },
];

/**
 * 激活风控动作走 HighRiskConfirmModal 三重门：reason ≥10 字符 + 用户名挑战 + confirm checkbox。
 * actionType / symbol 通过 children 注入到 modal 内部，submit 时一并发往后端。
 *
 * 错误码 90800（已激活）→ 视作成功结束。
 */
export function RiskActionActivateModal({ open, onClose, onSuccess }: Props) {
  const adminUser = useAdminAuthStore((s) => s.user);
  const [actionType, setActionType] = useState<RiskActionType>("REJECT_OPEN");
  const [symbol, setSymbol] = useState("");
  const [symbolOptions, setSymbolOptions] = useState<SymbolQuoteMappingItem[]>([]);
  const [symbolOptionsLoading, setSymbolOptionsLoading] = useState(false);

  const loadSymbolOptions = useCallback(async (platformSymbolLike?: string) => {
    setSymbolOptionsLoading(true);
    try {
      const resp = await symbolApi.listQuoteMappings({
        platformSymbolLike: platformSymbolLike?.trim() || undefined,
        page: 0,
        size: 100,
      });
      setSymbolOptions(resp.items);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      void message.error(`加载 platform symbol 失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSymbolOptionsLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (open) void loadSymbolOptions();
  }, [open, loadSymbolOptions]);

  const handleActionTypeChange = (next: RiskActionType) => {
    setActionType(next);
    if (next === "GLOBAL_PAUSE") {
      setSymbol("");
    }
  };

  const reset = () => {
    setActionType("REJECT_OPEN");
    setSymbol("");
  };

  if (!open) return null;

  return (
    <HighRiskConfirmModal<void>
      open={true}
      title="激活风控动作"
      okText="确认激活"
      description="激活后将影响开仓 / 平仓行为，请谨慎。"
      details={[
        ["动作类型", actionType],
        ["Symbol", actionType === "GLOBAL_PAUSE" ? "(全局)" : symbol || "(未填)"],
      ]}
      requireConfirmCheckbox
      usernameChallenge={
        adminUser
          ? { expected: adminUser.username, label: `请输入您的用户名 "${adminUser.username}" 确认` }
          : undefined
      }
      validate={() => {
        if (actionType !== "GLOBAL_PAUSE" && !symbol.trim()) {
          return "非全局动作时 Symbol 必填";
        }
        return null;
      }}
      onSubmit={async (reason) => {
        try {
          await riskApi.activateAction({
            symbol: actionType === "GLOBAL_PAUSE" ? undefined : symbol.trim(),
            actionType,
            reason,
          });
        } catch (err) {
          if (err instanceof ApiError && err.code === "90800") {
            void message.warning("该 symbol+类型+管理端来源已激活，无需重复");
            return;
          }
          throw err;
        }
      }}
      onSuccess={() => {
        void message.success("风控动作已激活");
        reset();
        onClose();
        onSuccess();
      }}
      onCancel={() => {
        reset();
        onClose();
      }}
    >
      <div style={{ marginBottom: 12 }}>
        <div style={{ marginBottom: 6 }}>动作类型</div>
        <Radio.Group
          value={actionType}
          onChange={(e) => handleActionTypeChange(e.target.value as RiskActionType)}
          options={ACTION_OPTIONS}
        />
      </div>
      <div style={{ marginBottom: 12 }}>
        <div style={{ marginBottom: 6 }}>Symbol（GLOBAL_PAUSE 禁用）</div>
        <Select
          value={symbol}
          onChange={setSymbol}
          showSearch
          filterOption={false}
          loading={symbolOptionsLoading}
          onSearch={(value) => void loadSymbolOptions(value)}
          onFocus={() => {
            if (symbolOptions.length === 0) void loadSymbolOptions();
          }}
          options={symbolOptions.map((item) => ({
            value: item.platformSymbol,
            label: `${item.platformSymbol} · ${item.marketCode}`,
          }))}
          placeholder="从 t_symbol_quote_mapping 选择"
          disabled={actionType === "GLOBAL_PAUSE"}
          style={{ width: "100%" }}
        />
      </div>
    </HighRiskConfirmModal>
  );
}
