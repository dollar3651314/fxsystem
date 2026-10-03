import { DeferredPanel } from "../terminal/DeferredPanel";

const tabs = ["持仓", "订单", "成交", "账本", "强平", "Swap"];

export function ActivityDock() {
  return (
    <section className="activity-dock" aria-label="交易活动">
      <div className="activity-tabs">
        {tabs.map((tab, index) => (
          <button className={index === 0 ? "active" : undefined} type="button" key={tab}>
            {tab}
          </button>
        ))}
      </div>
      <DeferredPanel title="持仓" />
    </section>
  );
}
