import { type FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FalconApiError } from "../../lib/api";
import { useAuthStore } from "../auth/authStore";
import { getLatestKyc, submitKyc } from "./kycApi";
import type { KycIdType, KycSubmissionResponse, SubmitKycCommand } from "./types";

type KycSubmitDrawerProps = {
  open: boolean;
  onClose: () => void;
};

const ID_TYPE_OPTIONS: Array<{ value: KycIdType; label: string }> = [
  { value: "ID_CARD", label: "身份证" },
  { value: "PASSPORT", label: "护照" },
  { value: "DRIVER_LICENSE", label: "驾照" },
];

const MAX_FILE_BYTES = 2 * 1024 * 1024;
const ACCEPTED_MIME = "image/jpeg,image/png,image/webp";

interface UploadedDocument {
  base64: string;
  mimeType: string;
  previewUrl: string;
}

export function KycSubmitDrawer({ open, onClose }: KycSubmitDrawerProps) {
  const session = useAuthStore((state) => state.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();

  const [idType, setIdType] = useState<KycIdType>("ID_CARD");
  const [idNumber, setIdNumber] = useState("");
  const [idFront, setIdFront] = useState<UploadedDocument | null>(null);
  const [idBack, setIdBack] = useState<UploadedDocument | null>(null);
  const [selfie, setSelfie] = useState<UploadedDocument | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const latestQuery = useQuery({
    queryKey: ["identity", "kyc", "latest"],
    queryFn: () => (token ? getLatestKyc(token) : Promise.resolve(null)),
    enabled: Boolean(token) && open,
    staleTime: 5_000,
  });

  const submitMutation = useMutation({
    mutationFn: (command: SubmitKycCommand) => {
      if (!token) throw new Error("no token");
      return submitKyc(token, command);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["identity", "kyc"] });
      setIdNumber("");
      setIdFront(null);
      setIdBack(null);
      setSelfie(null);
      setSubmitError(null);
      window.dispatchEvent(
        new CustomEvent("falconx:toast", { detail: { kind: "ok", text: "KYC 已提交，等待审核" } })
      );
    },
    onError: (cause) => {
      if (cause instanceof FalconApiError) {
        setSubmitError(`${cause.message} (${cause.code})`);
      } else {
        setSubmitError("提交失败");
      }
    },
  });

  // 收到 KYC reviewed 站内信即时刷新视图（trading-core 消费 Kafka 后 WS 推 notification.created）
  useEffect(() => {
    const handler = () => {
      void queryClient.invalidateQueries({ queryKey: ["identity", "kyc"] });
    };
    window.addEventListener("falconx:notification:created", handler);
    return () => window.removeEventListener("falconx:notification:created", handler);
  }, [queryClient]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !submitMutation.isPending) onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, submitMutation.isPending, onClose]);

  if (!open) return null;

  const latest = latestQuery.data ?? null;
  // 表单显示条件：未有任何记录、或最新 submission 被驳回且 kyc_level 仍 < 1
  // currentKycLevel ≥ 1 视为已认证（admin 可能直接 patch），不再允许重新提交
  const showForm =
    !latest ||
    (latest.currentKycLevel < 1 && latest.status === "REJECTED") ||
    (latest.currentKycLevel < 1 && latest.status == null && !latest.submissionId);

  async function handleFileChange(
    e: React.ChangeEvent<HTMLInputElement>,
    setter: (doc: UploadedDocument | null) => void
  ) {
    setFileError(null);
    const file = e.target.files?.[0];
    if (!file) {
      setter(null);
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setFileError("证件图片过大（最大 2MB）");
      e.target.value = "";
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result);
      const commaIdx = dataUrl.indexOf(",");
      const base64 = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;
      setter({ base64, mimeType: file.type || "image/jpeg", previewUrl: dataUrl });
    };
    reader.onerror = () => {
      setFileError("无法读取文件，请重试");
    };
    reader.readAsDataURL(file);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitError(null);
    if (!idNumber.trim()) {
      setSubmitError("请输入证件号");
      return;
    }
    if (!idFront || !idBack || !selfie) {
      setSubmitError("请上传 3 张证件图片");
      return;
    }
    submitMutation.mutate({
      idType,
      idNumber: idNumber.trim(),
      idFrontBase64: idFront.base64,
      idFrontMimeType: idFront.mimeType,
      idBackBase64: idBack.base64,
      idBackMimeType: idBack.mimeType,
      selfieBase64: selfie.base64,
      selfieMimeType: selfie.mimeType,
    });
  }

  return (
    <div className="fx-modal-backdrop" onClick={() => !submitMutation.isPending && onClose()}>
      <div className="fx-modal fx-modal--wide" onClick={(e) => e.stopPropagation()}>
        <div className="fx-modal-header">
          <h3>KYC 认证</h3>
          <button
            type="button"
            className="fx-modal-close"
            onClick={onClose}
            disabled={submitMutation.isPending}
            aria-label="关闭"
          >
            ×
          </button>
        </div>

        <div className="fx-modal-body">
          {latestQuery.isLoading && <p className="fx-modal-empty">加载中…</p>}

          {!latestQuery.isLoading && latestQuery.isError && (
            <div className="fx-modal-error">
              加载 KYC 状态失败
              <button
                type="button"
                className="fx-btn-secondary fx-btn-xs"
                onClick={() => latestQuery.refetch()}
                style={{ marginLeft: 8 }}
              >
                重试
              </button>
            </div>
          )}

          {/*
            徽章显示优先级：
            currentKycLevel ≥ 1 → 已通过（不管 submission status，admin 可能直接 patch kyc_level）
            否则按 submission.status 显示 PENDING / REJECTED；都没有则不显示
          */}
          {!latestQuery.isLoading && latest && latest.currentKycLevel >= 1 && (
            <KycReadOnlySummary
              submission={latest}
              bannerKind="ok"
              bannerText="KYC 已通过：您的姓名 / 出生日期 / 国籍 已锁定，需重新 KYC 才能修改。"
            />
          )}

          {!latestQuery.isLoading && latest && latest.currentKycLevel < 1 && latest.status === "PENDING" && (
            <KycReadOnlySummary submission={latest} bannerKind="pending" bannerText="审核中：您的 KYC 申请已提交，请等待运营审核。" />
          )}

          {!latestQuery.isLoading && latest && latest.currentKycLevel < 1 && latest.status === "REJECTED" && (
            <div className="profile-form__verified-banner" style={{ background: "rgba(220, 38, 38, 0.1)", color: "#dc2626" }}>
              KYC 未通过：{latest.rejectReason ?? "请重新提交"}
            </div>
          )}

          {!latestQuery.isLoading && showForm && (
            <form id="kyc-submit-form" className="profile-form" onSubmit={handleSubmit}>
              <fieldset disabled={submitMutation.isPending}>
                <legend>身份信息</legend>
                <div className="profile-form__row">
                  <label>
                    证件类型
                    <select value={idType} onChange={(e) => setIdType(e.target.value as KycIdType)}>
                      {ID_TYPE_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    证件号
                    <input
                      value={idNumber}
                      onChange={(e) => setIdNumber(e.target.value)}
                      maxLength={64}
                      placeholder="如 110101199001011234"
                    />
                  </label>
                </div>
              </fieldset>

              <fieldset disabled={submitMutation.isPending}>
                <legend>证件影像</legend>
                <KycFileField label="证件正面" doc={idFront} onChange={(e) => handleFileChange(e, setIdFront)} />
                <KycFileField label="证件反面" doc={idBack} onChange={(e) => handleFileChange(e, setIdBack)} />
                <KycFileField label="手持证件自拍" doc={selfie} onChange={(e) => handleFileChange(e, setSelfie)} />
              </fieldset>

              {fileError && <div className="fx-modal-error">{fileError}</div>}
              {submitError && <div className="fx-modal-error">{submitError}</div>}

              <div className="profile-form__hint" style={{ fontSize: 12, opacity: 0.7 }}>
                提交后将进入审核流程，期间不可修改；审核完成前请不要刷新页面。单张图片最大 2MB。
              </div>
            </form>
          )}
        </div>

        <div className="fx-modal-footer">
          <button
            type="button"
            className="fx-btn-secondary"
            onClick={onClose}
            disabled={submitMutation.isPending}
          >
            关闭
          </button>
          {showForm && (
            <button
              type="submit"
              form="kyc-submit-form"
              className="fx-btn-primary"
              disabled={submitMutation.isPending || latestQuery.isLoading}
            >
              {submitMutation.isPending ? "提交中…" : "提交 KYC"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function KycReadOnlySummary({
  submission,
  bannerKind,
  bannerText,
}: {
  submission: KycSubmissionResponse;
  bannerKind: "pending" | "ok";
  bannerText: string;
}) {
  const bannerStyle =
    bannerKind === "ok"
      ? { background: "rgba(34, 197, 94, 0.1)", color: "#16a34a" }
      : { background: "rgba(59, 130, 246, 0.1)", color: "#2563eb" };
  return (
    <>
      <div className="profile-form__verified-banner" style={bannerStyle}>
        {bannerText}
      </div>
      <dl className="profile-summary__meta" style={{ marginTop: 12 }}>
        <div>
          <dt>提交编号</dt>
          <dd className="fx-mono">{submission.submissionId}</dd>
        </div>
        <div>
          <dt>证件类型</dt>
          <dd>{submission.idType ? idTypeLabel(submission.idType) : "—"}</dd>
        </div>
        <div>
          <dt>证件号</dt>
          <dd className="fx-mono">{submission.idNumber ? maskIdNumber(submission.idNumber) : "—"}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{submission.submittedAt ? new Date(submission.submittedAt).toLocaleString("zh-CN", { hour12: false }) : "—"}</dd>
        </div>
        {submission.reviewAt && (
          <div>
            <dt>审核时间</dt>
            <dd>{new Date(submission.reviewAt).toLocaleString("zh-CN", { hour12: false })}</dd>
          </div>
        )}
      </dl>
    </>
  );
}

function KycFileField({
  label,
  doc,
  onChange,
}: {
  label: string;
  doc: UploadedDocument | null;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <label>
      {label}
      <input type="file" accept={ACCEPTED_MIME} onChange={onChange} />
      {doc && (
        <img
          src={doc.previewUrl}
          alt={`${label} 预览`}
          style={{ width: 80, height: 80, objectFit: "cover", borderRadius: 6, marginTop: 6, border: "1px solid var(--fx-border, #333)" }}
        />
      )}
    </label>
  );
}

function idTypeLabel(t: KycIdType): string {
  return ID_TYPE_OPTIONS.find((o) => o.value === t)?.label ?? t;
}

function maskIdNumber(n: string): string {
  if (!n || n.length <= 4) return n;
  return "****" + n.slice(-4);
}
