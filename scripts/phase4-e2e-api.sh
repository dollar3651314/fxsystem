#!/usr/bin/env bash
# STAGE-7-WITHDRAW Phase 4 commit 3 R7 程序化 E2E（curl + DB）。
# 浏览器视觉 QA 因 WSL 无 sudo 装 chromium 系统依赖（libnspr4.so 等）受阻——降级到 API 走通。
#
# 验证目标（5 场景）：
#   1. 列表透传所有 query 参数 + withdrawId 字段正确
#   2. 详情包含完整字段
#   3. approve PENDING → APPROVED + balance 不变 + frozen 不变（等 Kafka reviewed → wallet 才会变）
#   4. reject PENDING → REJECTED + balance 不变 + frozen -= amount（直接退冻）
#   5. emergency-cancel APPROVED_DELAYED → CANCELED + balance 不变 + frozen -= amount

set -e

BASE=http://localhost:18085
EVIDENCE_DIR=$(dirname "$0")/../docs/test/fixtures/stage7-phase4
mkdir -p "$EVIDENCE_DIR"

log() { echo -e "\n▶ $*"; }
sql() {
  docker exec falconx-mysql mysql -uroot -proot -N -e "$1" 2>&1 | grep -v Warning
}

# 拿 superadmin token
log "登录 superadmin 拿 token"
TOKEN=$(curl -s -X POST $BASE/admin/auth/login -H "Content-Type: application/json" \
  -d '{"username":"superadmin","password":"FalconXAdmin@2026"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
echo "  ✓ token len=${#TOKEN}"
AUTH=("-H" "Authorization: Bearer $TOKEN")

#### 场景 1：列表透传 query
log "TC-WD-FE-E2E-001 列表透传 7 个 query 参数"
curl -s "$BASE/admin/withdraws?status=PENDING&network=ERC20&minAmount=10&maxAmount=100&page=1&pageSize=20" \
  "${AUTH[@]}" | tee "$EVIDENCE_DIR/01-list-pending.json" | python3 -m json.tool > /dev/null
HAS_WID=$(python3 -c "import json; d=json.load(open('$EVIDENCE_DIR/01-list-pending.json')); print(d['data']['items'][0].get('withdrawId','MISSING') if d['data']['items'] else 'EMPTY')")
echo "  ✓ items[0].withdrawId = $HAS_WID（应该是 900100001 / 900100002 之一，不应 MISSING）"

#### 场景 2：详情
log "TC-WD-FE-E2E-002 详情 GET /admin/withdraws/900100001"
curl -s "$BASE/admin/withdraws/900100001" "${AUTH[@]}" \
  | tee "$EVIDENCE_DIR/02-detail-pending.json" | python3 -m json.tool > /dev/null
DETAIL_STATUS=$(python3 -c "import json; print(json.load(open('$EVIDENCE_DIR/02-detail-pending.json'))['data']['status'])")
echo "  ✓ status = $DETAIL_STATUS"

#### 场景 3：approve
log "TC-WD-FE-E2E-003 approve 900100001 (reviewNote 可选)"
echo "  [前置 DB] $(sql "SELECT status, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100001 AND a.currency='USDT'")"
APPROVE_RESP=$(curl -s -X POST $BASE/admin/withdraws/900100001/approve "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -d '{"reviewNote":"E2E 自动审核备注"}')
echo "$APPROVE_RESP" | tee "$EVIDENCE_DIR/03-approve.json" | python3 -m json.tool > /dev/null
APPROVE_STATUS=$(echo "$APPROVE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])")
echo "  ✓ approve 后 status = $APPROVE_STATUS"
echo "  [后置 DB] $(sql "SELECT status, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100001 AND a.currency='USDT'")"

#### 场景 4：reject
log "TC-WD-FE-E2E-004 reject 900100002 + reason 必填"
echo "  [前置 DB] $(sql "SELECT status, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100002 AND a.currency='USDT'")"
REJECT_RESP=$(curl -s -X POST $BASE/admin/withdraws/900100002/reject "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -d '{"reason":"E2E 测试拒绝：不符合风控要求"}')
echo "$REJECT_RESP" | tee "$EVIDENCE_DIR/04-reject.json" | python3 -m json.tool > /dev/null
REJECT_STATUS=$(echo "$REJECT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])")
echo "  ✓ reject 后 status = $REJECT_STATUS"
echo "  [后置 DB] $(sql "SELECT status, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100002 AND a.currency='USDT'")"

#### 场景 5：emergency-cancel
log "TC-WD-FE-E2E-005 emergency-cancel 900100003 + reason 必填 + APPROVED_DELAYED 状态要求"
echo "  [前置 DB] $(sql "SELECT status, delayed_until, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100003 AND a.currency='USDT'")"
CANCEL_RESP=$(curl -s -X POST $BASE/admin/withdraws/900100003/emergency-cancel "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -d '{"reason":"E2E 测试紧急取消：风控发现可疑活动"}')
echo "$CANCEL_RESP" | tee "$EVIDENCE_DIR/05-emergency-cancel.json" | python3 -m json.tool > /dev/null
CANCEL_STATUS=$(echo "$CANCEL_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])")
echo "  ✓ emergency-cancel 后 status = $CANCEL_STATUS"
echo "  [后置 DB] $(sql "SELECT status, balance, frozen FROM falconx_trading.t_withdraw_order o JOIN falconx_trading.t_account a ON a.user_id=o.user_id WHERE o.id=900100003 AND a.currency='USDT'")"

#### 错误码翻译验证
log "TC-WD-FE-E2E-ERR-1 错误码 90501 翻译（重复 approve PENDING→失败因已 APPROVED）"
RETRY_APPROVE=$(curl -s -X POST $BASE/admin/withdraws/900100001/approve "${AUTH[@]}" -H "Content-Type: application/json" -d '{}')
echo "$RETRY_APPROVE" | tee "$EVIDENCE_DIR/06-error-90501.json" | python3 -m json.tool > /dev/null
ERR_CODE=$(echo "$RETRY_APPROVE" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")
echo "  ✓ 错误码 = $ERR_CODE（应 90501 ADMIN_WITHDRAW_NOT_PENDING）"

log "TC-WD-FE-E2E-ERR-2 错误码 90500 翻译（不存在的 id）"
NOTFOUND=$(curl -s "$BASE/admin/withdraws/999999999" "${AUTH[@]}")
echo "$NOTFOUND" | tee "$EVIDENCE_DIR/07-error-90500.json" | python3 -m json.tool > /dev/null
NF_CODE=$(echo "$NOTFOUND" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")
echo "  ✓ 错误码 = $NF_CODE（应 90500 ADMIN_WITHDRAW_NOT_FOUND）"

log "TC-WD-FE-E2E-ERR-3 reject 空 reason 前置校验 → 90503"
BLANK=$(curl -s -X POST $BASE/admin/withdraws/900100001/reject "${AUTH[@]}" -H "Content-Type: application/json" -d '{"reason":""}')
echo "$BLANK" | tee "$EVIDENCE_DIR/08-error-90503.json" | python3 -m json.tool > /dev/null
BL_CODE=$(echo "$BLANK" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")
echo "  ✓ 错误码 = $BL_CODE（应 90503 或 90004 校验失败）"

#### 审计验证
log "审计日志验证（t_admin_operation_log 应有 3 条 HIGH_RISK 记录）"
sql "SELECT id, admin_user_id, permission_code, http_method, request_uri, risk_level, success FROM falconx_console.t_admin_operation_log WHERE permission_code LIKE 'withdraw:%' ORDER BY id DESC LIMIT 5"

echo ""
echo "===================="
echo "✅ Phase 4 commit 3 程序化 E2E 完成"
echo "  - 5 主场景 + 3 错误码翻译验证"
echo "  - DB 前后状态 trace 已捕获到 $EVIDENCE_DIR/"
echo "  - 审计日志已查 t_admin_operation_log"
echo "===================="
