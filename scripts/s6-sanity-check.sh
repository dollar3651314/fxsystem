#!/usr/bin/env bash
# Sprint 3 S6 sanity：手动平仓 → 5s 后 t_trade 落盘 → 整体最终一致
# 用法：bash scripts/s6-sanity-check.sh <POSITION_ID>
set -euo pipefail

POSITION_ID="${1:?usage: s6-sanity-check.sh <POSITION_ID>}"

echo "=== 1. 平仓前 t_position / t_trade 状态 ==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, user_id, symbol, status, close_reason FROM falconx_trading.t_position WHERE id = ${POSITION_ID};
  SELECT id, position_id, trade_type FROM falconx_trading.t_trade WHERE position_id = ${POSITION_ID};
" 2>&1 | grep -v 'Warning\|mysql:'

USER_ID=$(docker exec falconx-mysql mysql -uroot -proot -BN -e "SELECT user_id FROM falconx_trading.t_position WHERE id = ${POSITION_ID};" 2>/dev/null)

echo
echo "=== 2. 调平仓 API ==="
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"userId\": ${USER_ID}, \"positionId\": ${POSITION_ID}}" \
  "http://localhost:18080/api/v1/trading/positions/close" | jq . || true

echo
echo "=== 3. 主事务即时检查（应已 commit）==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, status, close_reason, close_price, realized_pnl FROM falconx_trading.t_position WHERE id = ${POSITION_ID};
  SELECT COUNT(*) AS outbox_post_process_count FROM falconx_trading.t_outbox
    WHERE event_type = 'trading.position.close.post-process' AND event_id LIKE '%${POSITION_ID}%';
" 2>&1 | grep -v 'Warning\|mysql:'

echo
echo "=== 4. 等 5s 让 outbox 消费完成 ==="
sleep 5

echo
echo "=== 5. 5s 后 t_trade / 级联 SL/TP / outbox 状态 ==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, position_id, trade_type, realized_pnl FROM falconx_trading.t_trade WHERE position_id = ${POSITION_ID};
  SELECT id, position_id, status FROM falconx_trading.t_pending_order_trigger WHERE position_id = ${POSITION_ID};
  SELECT id, status, sent_at FROM falconx_trading.t_outbox
    WHERE event_type = 'trading.position.close.post-process' AND event_id LIKE '%${POSITION_ID}%';
" 2>&1 | grep -v 'Warning\|mysql:'

echo
echo "=== 6. 期望 ==="
echo "  - t_trade 应有一条 trade_type=CLOSE 的记录"
echo "  - 关联 SL/TP 挂单 status=CANCELED"
echo "  - outbox post-process 行 status=SENT，sent_at 非 null"
