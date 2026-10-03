type DeferredPanelProps = {
  title: string;
};

export function DeferredPanel({ title }: DeferredPanelProps) {
  return (
    <section className="deferred-panel" aria-label={title}>
      <div>
        <h3>{title}</h3>
        <p>用户侧实时推送尚未接入</p>
        <span>当前区域只展示结构占位，不代表账户、订单或持仓实时更新。</span>
      </div>
    </section>
  );
}
