import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { Spin } from "antd";
import { AdminLoginPage } from "./features/auth/AdminLoginPage";
import { ChangePasswordPage } from "./features/auth/ChangePasswordPage";
import { AdminLayout } from "./features/layout/AdminLayout";

// 2026-05-20 perf: 路由级 React.lazy 把 admin 主 chunk 从 1.6MB 拆成 ~400KB 核心 + 各页独立按需加载，
// 用户首屏只下载 DashboardPage（最常落地的入口），其他 page chunk 在点击导航时再 fetch。
// AdminLoginPage / ChangePasswordPage / AdminLayout 保持同步导入：
// · login / change-password 是未登录首屏，必须立刻可见
// · AdminLayout 是 Outlet 容器，所有内嵌路由都依赖它，lazy 会触发双层 Suspense
const DashboardPage = lazy(() => import("./features/dashboard/DashboardPage").then((m) => ({ default: m.DashboardPage })));
const CustomerListPage = lazy(() => import("./features/customer/CustomerListPage").then((m) => ({ default: m.CustomerListPage })));
const CustomerDetailPage = lazy(() => import("./features/customer/CustomerDetailPage").then((m) => ({ default: m.CustomerDetailPage })));
const AdminUsersPage = lazy(() => import("./features/admin/AdminUsersPage").then((m) => ({ default: m.AdminUsersPage })));
const AdminRolesPage = lazy(() => import("./features/admin/AdminRolesPage").then((m) => ({ default: m.AdminRolesPage })));
const AdminMenusPage = lazy(() => import("./features/admin/AdminMenusPage").then((m) => ({ default: m.AdminMenusPage })));
const AdminPermissionsPage = lazy(() => import("./features/admin/AdminPermissionsPage").then((m) => ({ default: m.AdminPermissionsPage })));
const SymbolListPage = lazy(() => import("./features/symbol/SymbolListPage").then((m) => ({ default: m.SymbolListPage })));
const GroupMarkupListPage = lazy(() => import("./features/symbol-group-markup/GroupMarkupListPage").then((m) => ({ default: m.GroupMarkupListPage })));
const FeaturedTickerPage = lazy(() => import("./features/symbol-featured/FeaturedTickerPage").then((m) => ({ default: m.FeaturedTickerPage })));
const TradingOrderListPage = lazy(() => import("./features/trading/TradingOrderListPage").then((m) => ({ default: m.TradingOrderListPage })));
const TradingPositionListPage = lazy(() => import("./features/trading/TradingPositionListPage").then((m) => ({ default: m.TradingPositionListPage })));
const TradingExposureBoardPage = lazy(() => import("./features/trading/TradingExposureBoardPage").then((m) => ({ default: m.TradingExposureBoardPage })));
const TradingRiskSwitchPage = lazy(() => import("./features/trading/TradingRiskSwitchPage").then((m) => ({ default: m.TradingRiskSwitchPage })));
const TradingPendingOrderListPage = lazy(() => import("./features/trading/TradingPendingOrderListPage").then((m) => ({ default: m.TradingPendingOrderListPage })));
const TradingPriceAlertListPage = lazy(() => import("./features/trading/TradingPriceAlertListPage").then((m) => ({ default: m.TradingPriceAlertListPage })));
const TierConfigListPage = lazy(() => import("./features/tier/TierConfigListPage").then((m) => ({ default: m.TierConfigListPage })));
const MarginModeConfigPage = lazy(() => import("./features/platform-config/MarginModeConfigPage").then((m) => ({ default: m.MarginModeConfigPage })));
const RiskThresholdConfigPage = lazy(() => import("./features/platform-config/RiskThresholdConfigPage").then((m) => ({ default: m.RiskThresholdConfigPage })));
const FxPauseBehaviorConfigPage = lazy(() => import("./features/fx-pause/FxPauseBehaviorConfigPage").then((m) => ({ default: m.FxPauseBehaviorConfigPage })));
const FxRateMonitorPage = lazy(() => import("./features/market/FxRateMonitorPage").then((m) => ({ default: m.FxRateMonitorPage })));
const RiskActionListPage = lazy(() => import("./features/risk/RiskActionListPage").then((m) => ({ default: m.RiskActionListPage })));
const RiskConfigListPage = lazy(() => import("./features/risk/RiskConfigListPage").then((m) => ({ default: m.RiskConfigListPage })));
const RiskMarketConfigListPage = lazy(() => import("./features/risk/RiskMarketConfigListPage").then((m) => ({ default: m.RiskMarketConfigListPage })));
const PlatformRiskConfigPage = lazy(() => import("./features/risk/PlatformRiskConfigPage").then((m) => ({ default: m.PlatformRiskConfigPage })));
const UserRiskThresholdListPage = lazy(() => import("./features/risk/UserRiskThresholdListPage").then((m) => ({ default: m.UserRiskThresholdListPage })));
const DepositListPage = lazy(() => import("./features/deposit/DepositListPage").then((m) => ({ default: m.DepositListPage })));
const WalletProvisionDlqListPage = lazy(() => import("./features/wallet/WalletProvisionDlqListPage").then((m) => ({ default: m.WalletProvisionDlqListPage })));
const KycReviewListPage = lazy(() => import("./features/kyc/KycReviewListPage").then((m) => ({ default: m.KycReviewListPage })));
const WithdrawListPage = lazy(() => import("./features/withdraw/WithdrawListPage").then((m) => ({ default: m.WithdrawListPage })));
const WithdrawDetailPage = lazy(() => import("./features/withdraw/WithdrawDetailPage").then((m) => ({ default: m.WithdrawDetailPage })));
const NotificationListPage = lazy(() => import("./features/notification/NotificationListPage").then((m) => ({ default: m.NotificationListPage })));
const NotificationTemplateListPage = lazy(() => import("./features/notification/NotificationTemplateListPage").then((m) => ({ default: m.NotificationTemplateListPage })));
const AuditLogListPage = lazy(() => import("./features/audit/AuditLogListPage").then((m) => ({ default: m.AuditLogListPage })));
const ReconciliationListPage = lazy(() => import("./features/reconciliation/ReconciliationListPage").then((m) => ({ default: m.ReconciliationListPage })));
const SystemConfigPage = lazy(() => import("./features/system-config/SystemConfigPage").then((m) => ({ default: m.SystemConfigPage })));

function PageFallback() {
  return (
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "calc(100vh - 56px)" }}>
      <Spin size="large" tip="加载中…" />
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/admin/login" element={<AdminLoginPage />} />
      <Route path="/admin/change-password" element={<ChangePasswordPage />} />
      <Route path="/admin" element={<AdminLayout />}>
        <Route
          index
          element={
            <Suspense fallback={<PageFallback />}>
              <DashboardPage />
            </Suspense>
          }
        />
        <Route path="customers" element={<Suspense fallback={<PageFallback />}><CustomerListPage /></Suspense>} />
        <Route path="customers/:userId" element={<Suspense fallback={<PageFallback />}><CustomerDetailPage /></Suspense>} />
        <Route path="users" element={<Suspense fallback={<PageFallback />}><AdminUsersPage /></Suspense>} />
        <Route path="roles" element={<Suspense fallback={<PageFallback />}><AdminRolesPage /></Suspense>} />
        <Route path="menus" element={<Suspense fallback={<PageFallback />}><AdminMenusPage /></Suspense>} />
        <Route path="permissions" element={<Suspense fallback={<PageFallback />}><AdminPermissionsPage /></Suspense>} />
        <Route path="symbols" element={<Suspense fallback={<PageFallback />}><SymbolListPage /></Suspense>} />
        <Route path="symbols/group-markup" element={<Suspense fallback={<PageFallback />}><GroupMarkupListPage /></Suspense>} />
        <Route path="symbols/featured" element={<Suspense fallback={<PageFallback />}><FeaturedTickerPage /></Suspense>} />
        <Route path="trading/orders" element={<Suspense fallback={<PageFallback />}><TradingOrderListPage /></Suspense>} />
        <Route path="trading/positions" element={<Suspense fallback={<PageFallback />}><TradingPositionListPage /></Suspense>} />
        <Route path="trading/exposures" element={<Suspense fallback={<PageFallback />}><TradingExposureBoardPage /></Suspense>} />
        <Route path="trading/risk-switches" element={<Suspense fallback={<PageFallback />}><TradingRiskSwitchPage /></Suspense>} />
        <Route path="trading/pending-orders" element={<Suspense fallback={<PageFallback />}><TradingPendingOrderListPage /></Suspense>} />
        <Route path="trading/price-alerts" element={<Suspense fallback={<PageFallback />}><TradingPriceAlertListPage /></Suspense>} />
        <Route path="trading/tier-configs" element={<Suspense fallback={<PageFallback />}><TierConfigListPage /></Suspense>} />
        <Route path="trading/margin-mode-config" element={<Suspense fallback={<PageFallback />}><MarginModeConfigPage /></Suspense>} />
        <Route path="trading/risk-thresholds" element={<Suspense fallback={<PageFallback />}><RiskThresholdConfigPage /></Suspense>} />
        <Route path="trading/fx-pause-behavior" element={<Suspense fallback={<PageFallback />}><FxPauseBehaviorConfigPage /></Suspense>} />
        <Route path="market/fx-rates" element={<Suspense fallback={<PageFallback />}><FxRateMonitorPage /></Suspense>} />
        <Route path="risk/actions" element={<Suspense fallback={<PageFallback />}><RiskActionListPage /></Suspense>} />
        <Route path="risk/configs" element={<Suspense fallback={<PageFallback />}><RiskConfigListPage /></Suspense>} />
        <Route path="risk/market-configs" element={<Suspense fallback={<PageFallback />}><RiskMarketConfigListPage /></Suspense>} />
        <Route path="risk/platform" element={<Suspense fallback={<PageFallback />}><PlatformRiskConfigPage /></Suspense>} />
        <Route path="risk/user-thresholds" element={<Suspense fallback={<PageFallback />}><UserRiskThresholdListPage /></Suspense>} />
        <Route path="deposits" element={<Suspense fallback={<PageFallback />}><DepositListPage /></Suspense>} />
        <Route path="wallet/provision-dlq" element={<Suspense fallback={<PageFallback />}><WalletProvisionDlqListPage /></Suspense>} />
        <Route path="kyc" element={<Suspense fallback={<PageFallback />}><KycReviewListPage /></Suspense>} />
        <Route path="withdraws" element={<Suspense fallback={<PageFallback />}><WithdrawListPage /></Suspense>} />
        <Route path="withdraws/:id" element={<Suspense fallback={<PageFallback />}><WithdrawDetailPage /></Suspense>} />
        <Route path="notifications" element={<Suspense fallback={<PageFallback />}><NotificationListPage /></Suspense>} />
        <Route path="notification-templates" element={<Suspense fallback={<PageFallback />}><NotificationTemplateListPage /></Suspense>} />
        <Route path="audit-logs" element={<Suspense fallback={<PageFallback />}><AuditLogListPage /></Suspense>} />
        <Route path="reconciliation/deposits" element={<Suspense fallback={<PageFallback />}><ReconciliationListPage /></Suspense>} />
        <Route path="system-config" element={<Suspense fallback={<PageFallback />}><SystemConfigPage /></Suspense>} />
      </Route>
      <Route path="*" element={<Navigate to="/admin" replace />} />
    </Routes>
  );
}
