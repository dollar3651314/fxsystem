import { Descriptions, Divider, Drawer, Space, Tag, Typography } from "antd";
import type { ReactNode } from "react";

const { Title, Text } = Typography;

export interface DetailField {
  label: string;
  value: ReactNode;
  span?: number;
}

export interface DetailGroup {
  num: string;
  title: string;
  fields: DetailField[];
}

interface Props {
  open: boolean;
  title: string;
  subtitle?: string;
  tag?: { text: string; color?: string };
  groups: DetailGroup[];
  onClose: () => void;
}

/** Admin 通用订单/持仓/挂单详情 Drawer —— console-editorial 风，
 *  Drawer 右侧出，每组 §NN cyan chip + AntD Descriptions 网格。 */
export function AdminOrderDetailDrawer({
  open,
  title,
  subtitle,
  tag,
  groups,
  onClose,
}: Props) {
  return (
    <Drawer
      title={
        <Space direction="vertical" size={2} style={{ width: "100%" }}>
          <Space size={8} align="center">
            <Text
              style={{
                fontFamily: "var(--fx-mono-stack)",
                fontSize: 10,
                fontWeight: 600,
                letterSpacing: "0.22em",
                textTransform: "uppercase",
                color: "var(--fx-cyan)",
              }}
            >
              DETAIL · INSPECT
            </Text>
            {tag && <Tag color={tag.color}>{tag.text}</Tag>}
          </Space>
          <Title level={4} style={{ margin: 0, color: "var(--fx-text)" }}>
            {title}
          </Title>
          {subtitle && (
            <Text
              type="secondary"
              style={{
                fontFamily: "var(--fx-mono-stack)",
                fontSize: 12,
                letterSpacing: "0.02em",
              }}
            >
              {subtitle}
            </Text>
          )}
        </Space>
      }
      placement="right"
      width={560}
      open={open}
      onClose={onClose}
      destroyOnHidden
      styles={{
        body: { padding: "20px 24px" },
      }}
    >
      {groups.map((g, idx) => (
        <div key={g.num}>
          {idx > 0 && <Divider style={{ margin: "20px 0 16px" }} />}
          <Space size={8} align="center" style={{ marginBottom: 12 }}>
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                padding: "2px 7px",
                border: "1px solid var(--fx-border-strong)",
                borderRadius: 3,
                background: "var(--fx-accent-soft)",
                color: "var(--fx-cyan)",
                fontFamily: "var(--fx-mono-stack)",
                fontSize: 10,
                fontWeight: 600,
                letterSpacing: "0.12em",
              }}
            >
              §{g.num}
            </span>
            <Text
              style={{
                fontFamily: "var(--fx-mono-stack)",
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: "0.16em",
                textTransform: "uppercase",
                color: "var(--fx-muted)",
              }}
            >
              {g.title}
            </Text>
          </Space>
          <Descriptions
            column={2}
            size="small"
            bordered
            labelStyle={{
              width: 110,
              fontFamily: "var(--fx-mono-stack)",
              fontSize: 11,
              fontWeight: 500,
              color: "var(--fx-muted)",
              backgroundColor: "var(--fx-surface-3)",
            }}
            contentStyle={{
              fontFamily: "var(--fx-mono-stack)",
              fontVariantNumeric: "tabular-nums",
              fontSize: 13,
              color: "var(--fx-text)",
              backgroundColor: "var(--fx-surface-1)",
            }}
          >
            {g.fields.map((f, i) => (
              <Descriptions.Item
                key={`${g.num}-${i}`}
                label={f.label}
                span={f.span ?? 1}
              >
                {f.value === null || f.value === undefined || f.value === "" ? (
                  <span style={{ color: "var(--fx-subtle)" }}>—</span>
                ) : (
                  f.value
                )}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </div>
      ))}
    </Drawer>
  );
}
