/* ==========================================================================
   会計を締める前の確認ダイアログ
   --------------------------------------------------------------------------
   2026-09-12 まで hall/bill.html に直書きしていました。
   盤面（hall/board.html）のお会計モーダルにも同じ操作が載った時点で、
   文言と金額の出し方が 2 か所に分かれる形になったので、ここへ切り出しました。
   <b>読み上げる金額が画面によって違うのは、現場でいちばん信用を失う壊れ方</b>です。

   ★ hall.js とは別のファイルにしてあります。
     hall.js は盤面でしか読み込みません（会計中にページを読み直すと
     入力やチェックが戻り、金銭事故になるため）。この確認だけは
     伝票ページでも要るので、リロードの仕掛けとは分けています。

   ★ 金額は「深夜料金あり／なし」の両方をボタンに持たせ、
     押した瞬間のチェック状態で選びます。描いた時点の金額を固定で出すと、
     チェックを外してから押したときにダイアログと実際の請求額が食い違います。

   ★ 計算そのものはしていません。2 つの金額はどちらも
     TableSession が出した値をそのまま受け取っているだけです
     （計算は必ず 1 箇所に集約する）。
   ========================================================================== */
function komekoConfirmClose(button) {
  var checkbox = button.form.querySelector('input[name="applyLateNight"]');
  var applied = checkbox ? checkbox.checked : false;
  var total = applied ? button.dataset.totalWith : button.dataset.totalWithout;
  var suffix = applied ? '（深夜料金 込み）' : '（深夜料金 なし）';

  /* 支払い方法はサーバ側でも必須にしてあるが、送ってから
     「選んでください」と返されるより、押した場所で気づけたほうが速い。 */
  var paid = button.form.querySelector('input[name="paymentMethod"]:checked');
  if (!paid) {
    alert('お支払い方法（現金／カード）を選んでください。');
    return false;
  }
  var paidLabel = paid.value === 'CARD' ? 'カード' : '現金';

  return confirm(button.dataset.table + ' のお会計を締めます。\n'
    + 'ご請求額 ¥' + total + ' ' + suffix + '\n'
    + 'お支払い ' + paidLabel + '\n\nよろしいですか？');
}
