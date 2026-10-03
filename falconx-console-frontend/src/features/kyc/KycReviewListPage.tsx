import { useEffect, useState } from "react";
import { Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import type { TableProps } from "antd";
import {
  kycApi,
  type AdminKycDetailResponse,
  type AdminKycItem,
  type AdminKycListQuery,
} from "./kycApi";
import { UserCell } from "../../components/UserCell";

const { Title } = Typography;

const STATUS_COLOR: Record<string, string> = {
  PENDING: "warning",
  APPROVED: "success",
  REJECTED: "error",
};

const ID_TYPE_LABEL: Record<string, string> = {
  ID_CARD: "身份证",
  PASSPORT: "护照",
  DRIVER_LICENSE: "驾照",
};

const DOC_TYPE_LABEL: Record<string, string> = {
  ID_FRONT: "证件正面",
  ID_BACK: "证件反面",
  HOLDING_SELFIE: "手持自拍",
};

export function KycReviewListPage() {
  const [items, setItems] = useState<AdminKycItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<AdminKycListQuery>({ page: 1, size: 20 });
  const [detailTarget, setDetailTarget] = useState<AdminKycItem | null>(null);

  const load = (q: AdminKycListQuery) => {
    setLoading(true);
    kycApi.list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.pageSize);
      })
      .catch((err) => message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load(query);
  }, [query]);

  const columns: TableProps<AdminKycItem>["columns"] = [
    { title: "申请 ID", dataIndex: "submissionId", key: "submissionId", width: 180, render: (v) => <span style={{ fontFamily: "monospace" }}>{v}</span> },
    { title: "用户", dataIndex: "userId", key: "userId", width: 200,
      render: (_, r) => <UserCell userId={r.userId} uid={r.userUid} email={r.userEmail} fullName={r.userFullName} /> },
    { title: "等级", dataIndex: "level", key: "level", width: 70, align: "center" },
    { title: "状态", dataIndex: "status", key: "status", width: 110,
      render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag> },
    { title: "证件类型", dataIndex: "idType", key: "idType", width: 110, render: (v: string) => ID_TYPE_LABEL[v] ?? v },
    { title: "证件号", dataIndex: "idNumber", key: "idNumber", width: 200, ellipsis: true },
    { title: "提交时间", dataIndex: "submittedAt", key: "submittedAt", width: 180 },
    { title: "审核时间", dataIndex: "reviewAt", key: "reviewAt", width: 180, render: (v) => v ?? "—" },
    { title: "拒绝原因", dataIndex: "rejectReason", key: "rejectReason", ellipsis: true, render: (v) => v ?? "—" },
    { title: "操作", key: "actions", width: 100, fixed: "right",
      render: (_, record) => <Button size="small" type="primary" onClick={() => setDetailTarget(record)}>查看</Button>,
    },
  ];

  return (
    <div>
      <Title level={3}>KYC 审核</Title>
      <Card style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={(v: AdminKycListQuery) => setQuery({ ...query, ...v, page: 1 })}>
          <Form.Item name="userId" label="User ID">
            <InputNumber placeholder="可选" style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 160 }} placeholder="全部"
              options={[
                { value: "PENDING", label: "PENDING" },
                { value: "APPROVED", label: "APPROVED" },
                { value: "REJECTED", label: "REJECTED" },
              ]} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => setQuery({ page: 1, size: 20 })}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Table
        rowKey="submissionId"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={{
          current: page, pageSize: size, total,
          showSizeChanger: true, pageSizeOptions: [20, 50, 100],
          onChange: (p, s) => setQuery({ ...query, page: p, size: s }),
        }}
        scroll={{ x: "max-content" }}
      />
      <KycReviewModal
        target={detailTarget}
        onClose={() => setDetailTarget(null)}
        onChanged={() => load(query)}
      />
    </div>
  );
}

interface ReviewProps {
  target: AdminKycItem | null;
  onClose: () => void;
  onChanged: () => void;
}

function KycReviewModal({ target, onClose, onChanged }: ReviewProps) {
  const [detail, setDetail] = useState<AdminKycDetailResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [rejectReason, setRejectReason] = useState("");

  useEffect(() => {
    if (!target) {
      setDetail(null);
      setRejectReason("");
      return;
    }
    setLoading(true);
    kycApi.detail(target.submissionId)
      .then(setDetail)
      .catch((err) => message.error(`加载详情失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  }, [target]);

  const submission = detail?.submission ?? target;

  const handleApprove = () => {
    if (!target) return;
    setSubmitting(true);
    kycApi.approve(target.submissionId)
      .then(() => {
        message.success("已通过 KYC");
        onChanged();
        onClose();
      })
      .catch((err) => message.error(`通过失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  };

  const handleReject = () => {
    if (!target || !rejectReason.trim()) {
      message.warning("必须填写拒绝原因");
      return;
    }
    setSubmitting(true);
    kycApi.reject(target.submissionId, rejectReason.trim())
      .then(() => {
        message.success("已拒绝");
        onChanged();
        onClose();
      })
      .catch((err) => message.error(`拒绝失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setSubmitting(false));
  };

  return (
    <Modal
      open={target != null}
      title={target ? `KYC 审核 #${target.submissionId}` : ""}
      onCancel={onClose}
      width={900}
      destroyOnClose
      footer={target?.status === "PENDING" ? (
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button danger loading={submitting} onClick={handleReject}>拒绝</Button>
          <Button type="primary" loading={submitting} onClick={handleApprove}>通过</Button>
        </Space>
      ) : (
        <Button onClick={onClose}>关闭</Button>
      )}
    >
      {loading && <div>加载中…</div>}
      {!loading && submission && (
        <div>
          <div style={{ marginBottom: 16, lineHeight: 1.8 }}>
            <div><b>User ID：</b><span style={{ fontFamily: "monospace" }}>{submission.userId}</span></div>
            <div><b>证件类型：</b>{ID_TYPE_LABEL[submission.idType] ?? submission.idType}</div>
            <div><b>证件号：</b>{submission.idNumber}</div>
            <div><b>提交时间：</b>{submission.submittedAt}</div>
            <div><b>状态：</b><Tag color={STATUS_COLOR[submission.status]}>{submission.status}</Tag></div>
            {submission.rejectReason && <div><b>拒绝原因：</b>{submission.rejectReason}</div>}
          </div>
          {detail && (
            <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 12 }}>
              {detail.documents.map((doc) => (
                <div key={doc.id} style={{ border: "1px solid var(--fx-console-border)", borderRadius: 6, padding: 8 }}>
                  <div style={{ fontWeight: 700, marginBottom: 6 }}>{DOC_TYPE_LABEL[doc.docType] ?? doc.docType}</div>
                  <img
                    src={`data:${doc.mimeType};base64,${doc.dataBase64}`}
                    alt={doc.docType}
                    style={{ width: "100%", maxHeight: 320, objectFit: "contain", display: "block" }}
                  />
                  <div style={{ fontSize: 11, marginTop: 4, color: "var(--fx-console-text-muted)", wordBreak: "break-all" }}>
                    sha256: {doc.sha256.substring(0, 24)}…
                  </div>
                </div>
              ))}
            </div>
          )}
          {target?.status === "PENDING" && (
            <div style={{ marginTop: 16 }}>
              <Input.TextArea
                rows={3}
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                placeholder="若拒绝，请填写原因（必填）"
              />
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
