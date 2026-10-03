import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  AutoComplete,
  Badge,
  Button,
  Card,
  Col,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import {
  AppstoreOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { RequiresPermission } from "../../components/RequiresPermission";
import { symbolApi } from "../symbol/symbolApi";
import { groupMarkupApi } from "./groupMarkupApi";
import type {
  GroupMarkupGroupedItem,
  GroupMarkupItem,
} from "./types";

const { Title, Text } = Typography;

interface UpsertFormValues {
  groupCode: string;
  platformSymbol: string;
  bidExtra: number;
  askExtra: number;
  enabled: boolean;
  reason: string;
}

interface DeleteFormValues {
  reason: string;
}

const PERMISSION_UPDATE = "symbol:group-markup:update";
const PROTECTED_DEFAULT_GROUP = "default";

function toNumber(v: number | string | null | undefined): number {
  if (v === null || v === undefined) return 0;
  return typeof v === "number" ? v : Number(v);
}

function isMarkupNonZero(item: GroupMarkupItem): boolean {
  return toNumber(item.bidExtra) !== 0 || toNumber(item.askExtra) !== 0;
}

function formatExtra(v: number | string): string {
  const n = toNumber(v);
  if (n === 0) return "0";
  return n > 0 ? `+${n}` : `${n}`;
}

function formatTime(ts: string): string {
  if (!ts) return "—";
  try {
    return new Date(ts).toLocaleString("zh-CN", { hour12: false });
  } catch {
    return ts;
  }
}

/**
 * STAGE-12-GROUP-MARKUP R10：用户组加点配置页（聚合视图重设计）。
 *
 * <p>顶层卡片网格按组聚合，点击卡片打开 Drawer 查看/编辑该组的 symbol 配置。
 * 解决之前 1571 条全平铺的认知超载问题。
 */
export function GroupMarkupListPage() {
  const [groups, setGroups] = useState<GroupMarkupGroupedItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [groupFilter, setGroupFilter] = useState("");
  const [showZeroGroups, setShowZeroGroups] = useState(false);

  // 修复 #2 #3: 拉平台 symbol 池 + visibility 组列表，新建/编辑 Modal 用作 Select options
  const [symbolPool, setSymbolPool] = useState<string[]>([]);
  const [groupPool, setGroupPool] = useState<string[]>([]);
  const [poolLoading, setPoolLoading] = useState(false);

  const [activeGroup, setActiveGroup] = useState<GroupMarkupGroupedItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerShowZero, setDrawerShowZero] = useState(false);
  const [drawerSearchSymbol, setDrawerSearchSymbol] = useState("");

  const [editOpen, setEditOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<GroupMarkupItem | null>(null);
  const [editMode, setEditMode] = useState<"create" | "edit">("create");
  const [editLockedGroup, setEditLockedGroup] = useState<string | null>(null);
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editForm] = Form.useForm<UpsertFormValues>();

  const [deleteTarget, setDeleteTarget] = useState<GroupMarkupItem | null>(null);
  const [deleteSubmitting, setDeleteSubmitting] = useState(false);
  const [deleteForm] = Form.useForm<DeleteFormValues>();

  const load = () => {
    setLoading(true);
    groupMarkupApi
      .listGrouped()
      .then((data) => setGroups(data.groups ?? []))
      .catch((err) => message.error(`加载失败 ${err?.code ?? ""}：${err?.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    void loadPools();
  }, []);

  // 修复 #2 #3: 一次性拉所有平台 symbol（用作新建/编辑下拉）+ visibility 已有组列表
  const loadPools = async () => {
    setPoolLoading(true);
    try {
      // 分页拉所有 quote-mappings（size=100 上限，简单串行）
      const allSymbols: string[] = [];
      const first = await symbolApi.listQuoteMappings({ page: 0, size: 100, enabled: 1 });
      first.items.forEach((m) => allSymbols.push(m.platformSymbol));
      const totalPages = Math.ceil(first.total / 100);
      if (totalPages > 1) {
        const rest = await Promise.all(
          Array.from({ length: totalPages - 1 }, (_, i) =>
            symbolApi.listQuoteMappings({ page: i + 1, size: 100, enabled: 1 }),
          ),
        );
        rest.forEach((r) => r.items.forEach((m) => allSymbols.push(m.platformSymbol)));
      }
      setSymbolPool(allSymbols.sort());

      // group-visibility/grouped 拉所有组
      const groupedResp = await symbolApi.listGroupVisibilityGrouped({ page: 0, size: 100 });
      const codes = groupedResp.items.map((g) => g.groupCode);
      // 包含 default 兜底 + 去重
      setGroupPool(Array.from(new Set([PROTECTED_DEFAULT_GROUP, ...codes])).sort());
    } catch (err: any) {
      message.warning(`下拉选项加载失败：${err?.message ?? ""}`);
    } finally {
      setPoolLoading(false);
    }
  };

  // 修复 #1: groups 变化时同步刷新 activeGroup（编辑/删除/新建后 Drawer 立即看到新值）
  useEffect(() => {
    if (!activeGroup) return;
    const next = groups.find((g) => g.groupCode === activeGroup.groupCode);
    // 引用变化才触发 re-render，避免 infinite loop
    if (next && next !== activeGroup) {
      setActiveGroup(next);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups]);

  // 卡片网格筛选
  const filteredGroups = useMemo(() => {
    return groups
      .filter((g) => {
        if (groupFilter && !g.groupCode.toLowerCase().includes(groupFilter.toLowerCase())) {
          return false;
        }
        if (!showZeroGroups) {
          const hasNonZero = g.items?.some(isMarkupNonZero);
          if (!hasNonZero && g.groupCode !== PROTECTED_DEFAULT_GROUP) return false;
        }
        return true;
      })
      .sort((a, b) => {
        // default 永远第一，其他按非零 markup 数倒序
        if (a.groupCode === PROTECTED_DEFAULT_GROUP) return -1;
        if (b.groupCode === PROTECTED_DEFAULT_GROUP) return 1;
        const aNonZero = a.items?.filter(isMarkupNonZero).length ?? 0;
        const bNonZero = b.items?.filter(isMarkupNonZero).length ?? 0;
        return bNonZero - aNonZero;
      });
  }, [groups, groupFilter, showZeroGroups]);

  // 全局统计
  const globalStats = useMemo(() => {
    const allItems = groups.flatMap((g) => g.items ?? []);
    const nonZero = allItems.filter(isMarkupNonZero);
    const enabled = nonZero.filter((i) => i.enabled);
    return {
      groupCount: groups.length,
      activeMarkupCount: nonZero.length,
      enabledMarkupCount: enabled.length,
    };
  }, [groups]);

  // Drawer 内表格数据筛选
  const drawerItems = useMemo(() => {
    if (!activeGroup) return [];
    return (activeGroup.items ?? [])
      .filter((item) => {
        if (!drawerShowZero && !isMarkupNonZero(item)) return false;
        if (drawerSearchSymbol
            && !item.platformSymbol.toLowerCase().includes(drawerSearchSymbol.toLowerCase())) {
          return false;
        }
        return true;
      })
      .sort((a, b) => {
        // 非零 markup 在前，启用在前
        const aHas = isMarkupNonZero(a) ? 1 : 0;
        const bHas = isMarkupNonZero(b) ? 1 : 0;
        if (aHas !== bHas) return bHas - aHas;
        if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
        return a.platformSymbol.localeCompare(b.platformSymbol);
      });
  }, [activeGroup, drawerShowZero, drawerSearchSymbol]);

  const openCardDrawer = (g: GroupMarkupGroupedItem) => {
    setActiveGroup(g);
    setDrawerShowZero(false);
    setDrawerSearchSymbol("");
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setActiveGroup(null);
  };

  const openCreate = (lockGroup?: string) => {
    setEditMode("create");
    setEditTarget(null);
    setEditLockedGroup(lockGroup ?? null);
    editForm.resetFields();
    editForm.setFieldsValue({
      enabled: true,
      bidExtra: 0,
      askExtra: 0,
      groupCode: lockGroup ?? "",
    });
    setEditOpen(true);
  };

  const openEdit = (item: GroupMarkupItem) => {
    setEditMode("edit");
    setEditTarget(item);
    setEditLockedGroup(null);
    editForm.setFieldsValue({
      groupCode: item.groupCode,
      platformSymbol: item.platformSymbol,
      bidExtra: toNumber(item.bidExtra),
      askExtra: toNumber(item.askExtra),
      enabled: item.enabled,
      reason: "",
    });
    setEditOpen(true);
  };

  const closeEdit = () => {
    setEditOpen(false);
    setEditMode("create");
    setEditTarget(null);
    setEditLockedGroup(null);
    editForm.resetFields();
  };

  const submitEdit = async () => {
    const v = await editForm.validateFields();
    setEditSubmitting(true);
    try {
      if (editMode === "create") {
        const created = await groupMarkupApi.create({
          groupCode: v.groupCode.trim(),
          platformSymbol: v.platformSymbol.trim().toUpperCase(),
          bidExtra: v.bidExtra,
          askExtra: v.askExtra,
          enabled: v.enabled,
          reason: v.reason.trim(),
        });
        message.success(`已新建：${created.groupCode} / ${created.platformSymbol}`);
      } else if (editTarget) {
        await groupMarkupApi.update(editTarget.groupCode, editTarget.platformSymbol, {
          bidExtra: v.bidExtra,
          askExtra: v.askExtra,
          enabled: v.enabled,
          reason: v.reason.trim(),
        });
        message.success("已更新（30s 内 trading-core 内存快照刷新）");
      }
      closeEdit();
      load();
      // activeGroup 通过 useEffect 监听 groups 自动刷新（修复 #1 编辑后 Drawer 不更新）
    } catch (err: any) {
      message.error(`提交失败 ${err?.code ?? ""}：${err?.message ?? ""}`);
    } finally {
      setEditSubmitting(false);
    }
  };

  const openDelete = (item: GroupMarkupItem) => {
    setDeleteTarget(item);
    deleteForm.resetFields();
  };

  const submitDelete = async () => {
    if (!deleteTarget) return;
    const v = await deleteForm.validateFields();
    setDeleteSubmitting(true);
    try {
      await groupMarkupApi.delete(deleteTarget.groupCode, deleteTarget.platformSymbol, {
        reason: v.reason.trim(),
      });
      message.success("已删除");
      setDeleteTarget(null);
      load();
    } catch (err: any) {
      message.error(`删除失败 ${err?.code ?? ""}：${err?.message ?? ""}`);
    } finally {
      setDeleteSubmitting(false);
    }
  };

  // Drawer 表格列定义
  const drawerColumns: TableProps<GroupMarkupItem>["columns"] = [
    {
      title: "平台 Symbol",
      dataIndex: "platformSymbol",
      key: "platformSymbol",
      width: 180,
      render: (s: string, item: GroupMarkupItem) => (
        <Space size={6}>
          <Text strong>{s}</Text>
          {isMarkupNonZero(item) && <ThunderboltOutlined style={{ color: "#fa8c16" }} />}
        </Space>
      ),
    },
    {
      title: "Bid 加点",
      dataIndex: "bidExtra",
      key: "bidExtra",
      width: 130,
      align: "right",
      render: (v: string | number) => {
        const n = toNumber(v);
        const color = n > 0 ? "var(--fx-pnl-pos)" : n < 0 ? "var(--fx-pnl-neg)" : "var(--fx-subtle)";
        return (
          <Text style={{ color, fontFamily: "JetBrains Mono, Menlo, monospace" }}>
            {formatExtra(v)}
          </Text>
        );
      },
    },
    {
      title: "Ask 加点",
      dataIndex: "askExtra",
      key: "askExtra",
      width: 130,
      align: "right",
      render: (v: string | number) => {
        const n = toNumber(v);
        const color = n > 0 ? "var(--fx-pnl-pos)" : n < 0 ? "var(--fx-pnl-neg)" : "var(--fx-subtle)";
        return (
          <Text style={{ color, fontFamily: "JetBrains Mono, Menlo, monospace" }}>
            {formatExtra(v)}
          </Text>
        );
      },
    },
    {
      title: "状态",
      dataIndex: "enabled",
      key: "enabled",
      width: 90,
      render: (v: boolean) => (v ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    {
      title: "更新时间",
      dataIndex: "updatedAt",
      key: "updatedAt",
      width: 180,
      render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(v)}</Text>,
    },
    {
      title: "操作",
      key: "actions",
      width: 160,
      align: "right",
      fixed: "right",
      render: (_, item) => (
        <Space size="small">
          <RequiresPermission code={PERMISSION_UPDATE}>
            <Tooltip title="编辑">
              <Button size="small" type="text" icon={<EditOutlined />} onClick={() => openEdit(item)} />
            </Tooltip>
          </RequiresPermission>
          <RequiresPermission code={PERMISSION_UPDATE}>
            <Tooltip title="删除">
              <Button
                size="small"
                type="text"
                danger
                icon={<DeleteOutlined />}
                onClick={() => openDelete(item)}
                disabled={item.groupCode === PROTECTED_DEFAULT_GROUP}
              />
            </Tooltip>
          </RequiresPermission>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24, minHeight: "100%" }}>
      {/* Header */}
      <div style={{ marginBottom: 16, display: "flex", alignItems: "flex-end", justifyContent: "space-between" }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>
            <AppstoreOutlined style={{ marginRight: 8 }} />
            用户组加点配置
          </Title>
          <Text type="secondary" style={{ fontSize: 12, marginTop: 4, display: "inline-block" }}>
            按用户组管理 bid/ask 双向加点（可正可负） · 影响 WS 推送 / REST 报价 / 撮合 / PnL / 强平 / 挂单触发
          </Text>
        </div>
        <Space>
          <RequiresPermission code={PERMISSION_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate()}>
              新建加点
            </Button>
          </RequiresPermission>
        </Space>
      </div>

      {/* 全局统计 + 提示 */}
      <Card
        size="small"
        style={{ marginBottom: 16, background: "rgba(24,144,255,0.05)", borderColor: "rgba(24,144,255,0.2)" }}
      >
        <Row gutter={24} align="middle">
          <Col span={5}>
            <Statistic title="活跃用户组" value={globalStats.groupCount} suffix="个" />
          </Col>
          <Col span={5}>
            <Statistic
              title="非零 markup 配置"
              value={globalStats.activeMarkupCount}
              suffix="条"
              valueStyle={{ color: globalStats.activeMarkupCount > 0 ? "#fa8c16" : undefined }}
            />
          </Col>
          <Col span={5}>
            <Statistic title="启用中" value={globalStats.enabledMarkupCount} suffix="条" valueStyle={{ color: "var(--fx-pnl-pos)" }} />
          </Col>
          <Col span={9}>
            <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.5 }}>
              ⚐ K 线 / ClickHouse / Kafka 严格保持基准价不加点。新建/编辑/删除后 30s 内 trading-core 内存快照刷新。
            </Text>
          </Col>
        </Row>
      </Card>

      {/* 筛选条 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Input
            placeholder="筛选用户组"
            prefix={<SearchOutlined />}
            value={groupFilter}
            onChange={(e) => setGroupFilter(e.target.value)}
            allowClear
            style={{ width: 220 }}
          />
          <Space>
            <Text type="secondary" style={{ fontSize: 12 }}>显示无 markup 组</Text>
            <Switch size="small" checked={showZeroGroups} onChange={setShowZeroGroups} />
          </Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
      </Card>

      {/* 卡片网格 */}
      <Spin spinning={loading}>
        {filteredGroups.length === 0 ? (
          <Empty description={loading ? "加载中…" : "暂无符合筛选条件的用户组"} />
        ) : (
          <Row gutter={[16, 16]}>
            {filteredGroups.map((g) => {
              const nonZero = g.items?.filter(isMarkupNonZero) ?? [];
              const enabled = nonZero.filter((i) => i.enabled).length;
              const disabled = nonZero.length - enabled;
              const total = g.configuredCount;
              const isDefault = g.groupCode === PROTECTED_DEFAULT_GROUP;
              const latestUpdate = (g.items ?? [])
                .map((i) => i.updatedAt)
                .filter(Boolean)
                .sort()
                .pop();

              return (
                <Col key={g.groupCode} xs={24} sm={12} md={8} lg={8} xl={6} xxl={6}>
                  <Card
                    hoverable
                    onClick={() => openCardDrawer(g)}
                    style={{
                      borderColor: isDefault
                        ? "var(--fx-border)"
                        : nonZero.length > 0
                          ? "rgba(250,140,22,0.4)"
                          : "var(--fx-hairline)",
                      // 修复 #4 卡片高度统一: 固定 minHeight 让所有卡片视觉一致
                      height: 280,
                      display: "flex",
                      flexDirection: "column",
                    }}
                    bodyStyle={{
                      padding: 16,
                      display: "flex",
                      flexDirection: "column",
                      flex: 1,
                      minHeight: 0,
                    }}
                  >
                    {/* Card head: 组名 + 标记 */}
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
                      <Space size={8}>
                        <Tag
                          color={isDefault ? "blue" : nonZero.length > 0 ? "orange" : "default"}
                          style={{ margin: 0, fontWeight: 600, fontSize: 14, padding: "2px 10px" }}
                        >
                          {g.groupCode}
                        </Tag>
                        {isDefault && (
                          <Tooltip title="默认基准组（不可删除）">
                            <Tag style={{ margin: 0 }} color="default">基准</Tag>
                          </Tooltip>
                        )}
                      </Space>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {formatTime(latestUpdate ?? "")}
                      </Text>
                    </div>

                    {/* 数字摘要 */}
                    <Row gutter={8} style={{ marginBottom: 12 }}>
                      <Col span={8}>
                        <Statistic
                          title={<Text type="secondary" style={{ fontSize: 11 }}>配置 symbol</Text>}
                          value={total}
                          valueStyle={{ fontSize: 18 }}
                        />
                      </Col>
                      <Col span={8}>
                        <Statistic
                          title={<Text type="secondary" style={{ fontSize: 11 }}>非零 markup</Text>}
                          value={nonZero.length}
                          valueStyle={{
                            fontSize: 18,
                            color: nonZero.length > 0 ? "#fa8c16" : undefined,
                          }}
                          prefix={
                            nonZero.length > 0
                              ? <ThunderboltOutlined style={{ fontSize: 14 }} />
                              : null
                          }
                        />
                      </Col>
                      <Col span={8}>
                        <Statistic
                          title={<Text type="secondary" style={{ fontSize: 11 }}>启用 / 停用</Text>}
                          value={`${enabled} / ${disabled}`}
                          valueStyle={{ fontSize: 18 }}
                        />
                      </Col>
                    </Row>

                    {/* Top 3 非零 markup 预览 — 固定 92px 高度（3 行 × ~28px + padding），少补占位多省略 */}
                    <div
                      style={{
                        background: "var(--fx-surface-3)",
                        padding: 8,
                        borderRadius: 4,
                        marginBottom: 8,
                        height: 92,
                        overflow: "hidden",
                        flex: 1,
                      }}
                    >
                      {nonZero.length > 0 ? (
                        <>
                          {nonZero.slice(0, 3).map((it) => (
                            <div
                              key={it.platformSymbol}
                              style={{
                                display: "flex",
                                justifyContent: "space-between",
                                padding: "2px 0",
                                fontSize: 12,
                                fontFamily: "JetBrains Mono, Menlo, monospace",
                              }}
                            >
                              <Text style={{ opacity: 0.85 }}>{it.platformSymbol}</Text>
                              <Space size={6}>
                                <Text style={{ color: toNumber(it.bidExtra) > 0 ? "var(--fx-pnl-pos)" : toNumber(it.bidExtra) < 0 ? "var(--fx-pnl-neg)" : undefined }}>
                                  bid {formatExtra(it.bidExtra)}
                                </Text>
                                <Text style={{ color: toNumber(it.askExtra) > 0 ? "var(--fx-pnl-pos)" : toNumber(it.askExtra) < 0 ? "var(--fx-pnl-neg)" : undefined }}>
                                  ask {formatExtra(it.askExtra)}
                                </Text>
                                {!it.enabled && <Tag style={{ margin: 0 }} color="default">停</Tag>}
                              </Space>
                            </div>
                          ))}
                          {nonZero.length > 3 && (
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              +{nonZero.length - 3} 更多…
                            </Text>
                          )}
                        </>
                      ) : (
                        <div
                          style={{
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center",
                            height: "100%",
                          }}
                        >
                          <Text type="secondary" style={{ fontSize: 12, fontStyle: "italic" }}>
                            {isDefault ? "全平台 symbol 基准组（0/0）" : "无非零 markup 配置"}
                          </Text>
                        </div>
                      )}
                    </div>

                    {/* Footer 操作提示 */}
                    <div style={{ textAlign: "right" }}>
                      <Button type="link" size="small" style={{ padding: 0 }}>
                        {nonZero.length > 0 ? `展开 ${nonZero.length} 条 →` : `查看 ${total} 条 →`}
                      </Button>
                    </div>
                  </Card>
                </Col>
              );
            })}
          </Row>
        )}
      </Spin>

      {/* 详情 Drawer */}
      <Drawer
        title={
          activeGroup ? (
            <Space size={8}>
              <Tag color={activeGroup.groupCode === PROTECTED_DEFAULT_GROUP ? "blue" : "orange"}
                   style={{ margin: 0, fontSize: 14, padding: "2px 10px" }}>
                {activeGroup.groupCode}
              </Tag>
              <Text>用户组加点详情</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {activeGroup.configuredCount} 条配置 ·
                {" "}{activeGroup.items?.filter(isMarkupNonZero).length ?? 0} 条非零 markup
              </Text>
            </Space>
          ) : ""
        }
        placement="right"
        width={920}
        open={drawerOpen}
        onClose={closeDrawer}
        extra={
          <RequiresPermission code={PERMISSION_UPDATE}>
            <Button
              icon={<PlusOutlined />}
              onClick={() => openCreate(activeGroup?.groupCode)}
              type="primary"
              ghost
            >
              组内新增 symbol
            </Button>
          </RequiresPermission>
        }
      >
        <Space wrap style={{ marginBottom: 16 }}>
          <Input
            placeholder="搜索 symbol（如 BTCUSDT）"
            prefix={<SearchOutlined />}
            value={drawerSearchSymbol}
            onChange={(e) => setDrawerSearchSymbol(e.target.value)}
            allowClear
            style={{ width: 240 }}
          />
          <Space size={4}>
            <Text type="secondary" style={{ fontSize: 12 }}>显示 0 加点</Text>
            <Switch size="small" checked={drawerShowZero} onChange={setDrawerShowZero} />
          </Space>
          <Badge
            count={drawerItems.length}
            showZero
            color={drawerItems.length > 0 ? "#fa8c16" : undefined}
            style={{ marginLeft: 8 }}
          />
        </Space>
        <Table
          rowKey={(r) => `${r.groupCode}::${r.platformSymbol}`}
          dataSource={drawerItems}
          columns={drawerColumns}
          size="small"
          pagination={{ defaultPageSize: 50, showSizeChanger: true }}
          scroll={{ x: "max-content" }}
        />
      </Drawer>

      {/* 新建 / 编辑 Modal */}
      <Modal
        title={editMode === "create" ? "新建用户组加点（高风险）" : "编辑用户组加点（高风险）"}
        open={editOpen}
        onCancel={closeEdit}
        onOk={submitEdit}
        confirmLoading={editSubmitting}
        destroyOnClose
        width={560}
      >
        <Alert
          message="高风险操作"
          description="影响该组用户的报价 / 撮合 / PnL / 强平 / 挂单触发口径。改动后 30s 内 trading-core 内存快照刷新生效。"
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
        />
        <Form form={editForm} layout="vertical">
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                label={
                  <Space size={4}>
                    用户组 code
                    <Tooltip title="选自已配置 group_visibility 的组，避免该组用户看不到该 symbol（详见 §6.14.0 解耦矩阵）。也可手输新组 code（首字符确保 visibility 配置同步跟上）。">
                      <Text type="secondary" style={{ cursor: "help", fontSize: 12 }}>(?)</Text>
                    </Tooltip>
                  </Space>
                }
                name="groupCode"
                rules={[{ required: true, message: "必填" }, { max: 64, message: "≤ 64 字符" }]}
              >
                <AutoComplete
                  disabled={editMode === "edit" || !!editLockedGroup}
                  placeholder={poolLoading ? "加载中…" : "选择或输入用户组"}
                  options={groupPool.map((g) => ({
                    value: g,
                    label: g === PROTECTED_DEFAULT_GROUP ? `${g} (基准组)` : g,
                  }))}
                  filterOption={(input, option) =>
                    (option?.value ?? "").toString().toLowerCase().includes(input.toLowerCase())
                  }
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label={
                  <Space size={4}>
                    平台 Symbol
                    <Tooltip title="必须来自 t_symbol_quote_mapping 启用 symbol 池，不可任意填写（防止保存时触发 90613 错误）。">
                      <Text type="secondary" style={{ cursor: "help", fontSize: 12 }}>(?)</Text>
                    </Tooltip>
                  </Space>
                }
                name="platformSymbol"
                rules={[{ required: true, message: "必填" }, { max: 32, message: "≤ 32 字符" }]}
              >
                <Select
                  disabled={editMode === "edit"}
                  placeholder={poolLoading ? "加载中…" : "搜索选择 symbol"}
                  showSearch
                  options={symbolPool.map((s) => ({ value: s, label: s }))}
                  filterOption={(input, option) =>
                    (option?.value ?? "").toString().toLowerCase().includes(input.toLowerCase())
                  }
                  virtual
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                label="Bid 加点（可正可负）"
                name="bidExtra"
                rules={[
                  { required: true, message: "必填" },
                  {
                    validator: (_, v) =>
                      v > -1_000_000 && v < 1_000_000
                        ? Promise.resolve()
                        : Promise.reject(new Error("必须在 (-1000000, 1000000)")),
                  },
                ]}
                tooltip="买入路径价格基准 + 此值。正值=加价，负值=让利"
              >
                <InputNumber style={{ width: "100%" }} step={0.0001} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="Ask 加点（可正可负）"
                name="askExtra"
                rules={[
                  { required: true, message: "必填" },
                  {
                    validator: (_, v) =>
                      v > -1_000_000 && v < 1_000_000
                        ? Promise.resolve()
                        : Promise.reject(new Error("必须在 (-1000000, 1000000)")),
                  },
                ]}
                tooltip="卖出路径价格基准 + 此值。正值=加价，负值=让利"
              >
                <InputNumber style={{ width: "100%" }} step={0.0001} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item
            label="操作原因（≥ 10 字符，进审计）"
            name="reason"
            rules={[
              { required: true, message: "必填" },
              { min: 10, message: "≥ 10 字符" },
              { max: 500, message: "≤ 500 字符" },
            ]}
          >
            <Input.TextArea
              rows={3}
              placeholder="例如：VIP 组提升 BTC 双边 0.5 点优惠（运营 ticket #1234）"
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 删除二次确认 */}
      <Modal
        title="删除用户组加点（高风险）"
        open={deleteTarget !== null}
        onCancel={() => setDeleteTarget(null)}
        onOk={submitDelete}
        confirmLoading={deleteSubmitting}
        okType="danger"
        destroyOnClose
        width={500}
      >
        {deleteTarget && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message={`即将删除 ${deleteTarget.groupCode} / ${deleteTarget.platformSymbol} 加点`}
            description="存量持仓 / 挂单已冻结历史加点，删除不影响清算口径；新单将回退至 0 加点。"
          />
        )}
        <Form form={deleteForm} layout="vertical">
          <Form.Item
            label="操作原因（≥ 10 字符）"
            name="reason"
            rules={[
              { required: true, message: "必填" },
              { min: 10, message: "≥ 10 字符" },
              { max: 500, message: "≤ 500 字符" },
            ]}
          >
            <Input.TextArea rows={3} placeholder="例如：清理停用组的废弃加点（运营 ticket #5678）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
