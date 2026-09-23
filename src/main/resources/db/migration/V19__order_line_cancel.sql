-- ============================================================================
--  V19 : 品ごとの取り消し（お会計の確認画面の ✕）
-- ============================================================================
--  店主の指摘がそのまま理由。
--    「取り消すボタンが卓ごとだから、一個の商品だけ破棄の場合とかだとムリじゃん」
--
--  カートを一度に確定すると 4 品でも 1 件の orders になるので、注文ごと
--  取り消すと関係のない 3 品まで請求から落ちていた。状態を明細（order_line）
--  にも持たせて、1 行だけ請求から外せるようにする。
--
--  ★ 行は消さない（DELETE しない）
--    canceled に印を付けるだけ。注文履歴と提供時間の集計は事実の記録で、
--    行ごと消すと「厨房は作ったのに伝票に無い」を後から説明できなくなる。
--    金額は Order.recalculate() が canceled を読み飛ばして出し直し、
--    そのあと TableSession.recalculate() がご請求額まで下げる。
--
--  ★ DEFAULT を必ず書く（V12・V15 と同じ注意）
--    canceled = FALSE。いま伝票に載っている品はどれも取り消されていない。
--    stock_returned = FALSE。取り消していない行に「在庫を戻した」と
--    書いてあるほうが誤解を生む。取り消すときに改めて選ぶ。
--
--  ★ stock_returned を残す理由
--    「まだ作っていない（戻す）」と「作った・出した（廃棄）」では材料の
--    行方が違う。どちらを選んだか残しておかないと、あとから理論原価と
--    実際原価が合わない理由を追えなくなる。
--
--  ★ canceled_reason の長さは orders.canceled_reason と揃えて 100
--    片方だけ長いと、同じ理由を書いたのに注文では保存できて明細では
--    落ちる、という説明のつかない差ができる。
-- ----------------------------------------------------------------------------
ALTER TABLE order_line ADD COLUMN canceled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE order_line ADD COLUMN canceled_at TIMESTAMP;
ALTER TABLE order_line ADD COLUMN canceled_reason VARCHAR(100);
ALTER TABLE order_line ADD COLUMN stock_returned BOOLEAN DEFAULT FALSE NOT NULL;
