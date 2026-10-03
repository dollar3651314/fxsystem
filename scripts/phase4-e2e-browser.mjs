#!/usr/bin/env node
/**
 * STAGE-7-WITHDRAW Phase 4 commit 3 R7 浏览器 E2E。
 *
 * 用 chromium-headless-shell 走完整 5 个场景，每场景至少截图 1 张归档
 * docs/test/screenshots/stage7-phase4/。
 *
 * 资源约束：MCP playwright 不可用（要 sudo 装 chrome），降级到 Node 直接驱动 chromium-headless-shell。
 *
 * Usage: NODE_TLS_REJECT_UNAUTHORIZED=0 node scripts/phase4-e2e-browser.mjs
 */
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const SCREENSHOT_DIR = resolve(import.meta.dirname, "..", "docs/test/screenshots/stage7-phase4");
const FRONTEND = "http://localhost:5300";
const USERNAME = "superadmin";
const PASSWORD = "FalconXAdmin@2026";
const PENDING_ID = "900100001"; // approve target
const REJECT_ID = "900100002";  // reject target
const DELAYED_ID = "900100003"; // emergency-cancel target

await mkdir(SCREENSHOT_DIR, { recursive: true });

const browser = await chromium.launch({
    executablePath: "/home/ives/.cache/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-linux64/chrome-headless-shell",
    headless: true,
});
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on("pageerror", (e) => console.error("PAGE ERROR:", e.message));
page.on("console", (m) => {
    if (m.type() === "error") console.error("CONSOLE ERROR:", m.text());
});

async function shot(name) {
    const path = resolve(SCREENSHOT_DIR, name);
    await page.screenshot({ path, fullPage: false });
    console.log(`📸 ${name}`);
}

async function login() {
    console.log("\n▶ 登录 superadmin");
    await page.goto(`${FRONTEND}/admin/login`);
    await page.waitForLoadState("networkidle");
    await shot("01-login.png");
    await page.fill('input[type="text"], input[id*="username"], input[name="username"]', USERNAME);
    await page.fill('input[type="password"]', PASSWORD);
    await page.click('button[type="submit"], button:has-text("登录")');
    await page.waitForURL("**/admin", { timeout: 10000 });
    await page.waitForLoadState("networkidle");
    console.log("  ✓ 登录成功 →", page.url());
}

async function visitList() {
    console.log("\n▶ TC-WD-FE-E2E-001 列表页");
    await page.goto(`${FRONTEND}/admin/withdraws`);
    await page.waitForLoadState("networkidle");
    await page.waitForSelector("text=出金审核", { timeout: 10000 });
    // 等待 Table 行加载
    await page.waitForSelector(`text=${PENDING_ID}`, { timeout: 10000 });
    await shot("02-list-overview.png");
    // 验证待办分组卡片
    const pendingCard = await page.locator("text=PENDING 待审核").isVisible();
    const delayedCard = await page.locator("text=APPROVED_DELAYED 延迟期").isVisible();
    console.log(`  ✓ PENDING 卡片可见=${pendingCard} / APPROVED_DELAYED 卡片可见=${delayedCard}`);
}

async function visitDetail(id, suffix) {
    console.log(`\n▶ 详情页 ${id} (${suffix})`);
    await page.goto(`${FRONTEND}/admin/withdraws/${id}`);
    await page.waitForLoadState("networkidle");
    await page.waitForSelector(`text=#${id}`, { timeout: 10000 });
    await shot(`03-detail-${suffix}.png`);
}

async function approveFlow() {
    console.log("\n▶ TC-WD-FE-E2E-003 通过出金 modal");
    await visitDetail(PENDING_ID, "pending");
    await page.click('button:has-text("通过出金")');
    await page.waitForSelector(".ant-modal-content", { timeout: 5000 });
    await shot("04-approve-modal.png");
    // 填写 reviewNote
    await page.fill('.ant-modal textarea', "E2E 自动审核备注");
    await shot("05-approve-modal-filled.png");
    // 确认
    await page.click('.ant-modal button:has-text("确认通过")');
    // 等成功 toast
    await page.waitForSelector("text=已通过", { timeout: 10000 });
    await page.waitForLoadState("networkidle");
    await shot("06-approve-result.png");
    console.log("  ✓ approve 成功");
}

async function rejectFlow() {
    console.log("\n▶ TC-WD-FE-E2E-004 拒绝出金 modal");
    await visitDetail(REJECT_ID, "reject");
    await page.click('button:has-text("拒绝出金")');
    await page.waitForSelector(".ant-modal-content", { timeout: 5000 });
    await shot("07-reject-modal.png");
    await page.fill('.ant-modal textarea', "E2E 测试拒绝原因：不符合风控要求");
    await shot("08-reject-modal-filled.png");
    await page.click('.ant-modal button:has-text("确认拒绝")');
    await page.waitForSelector("text=已拒绝", { timeout: 10000 });
    await page.waitForLoadState("networkidle");
    await shot("09-reject-result.png");
    console.log("  ✓ reject 成功");
}

async function emergencyCancelFlow() {
    console.log("\n▶ TC-WD-FE-E2E-005 紧急取消 modal");
    await visitDetail(DELAYED_ID, "approved-delayed");
    await page.click('button:has-text("紧急取消")');
    await page.waitForSelector(".ant-modal-content", { timeout: 5000 });
    await shot("10-emergency-cancel-modal.png");
    await page.fill('.ant-modal textarea', "E2E 测试紧急取消：风控发现可疑活动");
    // requireConfirmCheckbox 必须勾选
    await page.check('.ant-modal input[type="checkbox"]');
    await shot("11-emergency-cancel-modal-filled.png");
    await page.click('.ant-modal button:has-text("立即紧急取消")');
    await page.waitForSelector("text=已紧急取消", { timeout: 10000 });
    await page.waitForLoadState("networkidle");
    await shot("12-emergency-cancel-result.png");
    console.log("  ✓ emergency-cancel 成功");
}

try {
    await login();
    await visitList();
    await approveFlow();
    await rejectFlow();
    await emergencyCancelFlow();
    console.log("\n✅ 所有 5 场景 E2E 通过");
} catch (e) {
    console.error("\n❌ E2E 失败:", e.message);
    await shot("99-failure.png");
    process.exitCode = 1;
} finally {
    await browser.close();
}
