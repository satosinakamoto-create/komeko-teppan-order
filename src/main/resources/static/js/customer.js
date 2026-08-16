/* ==========================================================================
   お客さん向け画面のふるまい
   --------------------------------------------------------------------------
   ・JavaScript が動かなくても注文できるように作っています（プログレッシブ
     エンハンスメント）。ここでやっているのは「使いやすくする」ことだけで、
     金額の計算も入力チェックも最終的な判断はすべてサーバ側で行います。
   ========================================================================== */
(function () {
  'use strict';

  /** 「1,234」形式に整える */
  function yen(value) {
    return '¥' + Number(value).toLocaleString('ja-JP');
  }

  /* ------------------------------------------------------------------
     1. 商品詳細ページ：個数ステッパーと合計金額のリアルタイム表示
     ------------------------------------------------------------------ */
  var addForm = document.getElementById('add-form');
  if (addForm) {
    var qtyInput = document.getElementById('quantity');
    var totalLabel = document.getElementById('add-total');
    var basePrice = parseInt(addForm.dataset.basePrice || '0', 10);

    function selectedExtra() {
      var sum = 0;
      addForm.querySelectorAll('input[type=radio]:checked, input[type=checkbox]:checked')
        .forEach(function (input) {
          sum += parseInt(input.dataset.price || '0', 10);
        });
      return sum;
    }

    function updateTotal() {
      var qty = Math.max(1, parseInt(qtyInput.value || '1', 10));
      totalLabel.textContent = yen((basePrice + selectedExtra()) * qty);
    }

    /* ＋ / − ボタン */
    addForm.querySelectorAll('[data-qty-step]').forEach(function (button) {
      button.addEventListener('click', function () {
        var step = parseInt(button.dataset.qtyStep, 10);
        var next = parseInt(qtyInput.value || '1', 10) + step;
        var min = parseInt(qtyInput.min || '1', 10);
        var max = parseInt(qtyInput.max || '20', 10);
        qtyInput.value = Math.min(max, Math.max(min, next));
        updateTotal();
      });
    });

    qtyInput.addEventListener('input', updateTotal);

    /* チェックボックスの上限（「3つまで」など）を超えたら選べなくする */
    addForm.addEventListener('change', function (event) {
      var target = event.target;
      if (target.type === 'checkbox' && target.dataset.group) {
        var group = target.dataset.group;
        var max = parseInt(target.dataset.max || '99', 10);
        var boxes = addForm.querySelectorAll('input[type=checkbox][data-group="' + group + '"]');
        var checked = 0;
        boxes.forEach(function (b) { if (b.checked) checked += 1; });
        boxes.forEach(function (b) {
          if (!b.checked) { b.disabled = checked >= max; }
        });
      }
      updateTotal();
    });

    updateTotal();
  }

  /* ------------------------------------------------------------------
     2. 伝票ページ：数秒ごとに状態を見に行き、変わったら画面を更新
     ------------------------------------------------------------------
     SSE ではなくポーリングにしているのは、お客さんのスマホが同時に
     何十台にもなり得るためです。接続を張りっぱなしにせず、
     短いリクエストを間隔をあけて投げるほうが、電波が不安定でも復帰が簡単です。
     ------------------------------------------------------------------ */
  var billBox = document.getElementById('bill-status');
  if (billBox) {
    /* まだ調理中の品があるときだけ短い間隔で見る（無駄な通信を減らす） */
    var pending = billBox.dataset.pending === 'true';
    var POLL_MS = pending ? 6000 : 20000;
    var signature = null;

    /* 「いまの状態」をひとつの文字列にまとめる。
       これが前回と変わったときだけ画面を読み直せばよい。 */
    function signatureOf(data) {
      if (!data || !data.open) { return 'closed'; }
      var parts = [data.totalAmount];
      (data.orders || []).forEach(function (o) {
        parts.push(o.orderNumber + ':' + o.status);
      });
      return parts.join('|');
    }

    function poll() {
      fetch('/api/public/bill', {
        headers: { 'Accept': 'application/json' },
        cache: 'no-store'
      })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          if (!data) { return; }
          var next = signatureOf(data);
          if (signature === null) { signature = next; return; }
          if (next !== signature) {
            window.location.reload();
          }
        })
        .catch(function () { /* 電波が切れただけかもしれないので黙って次回に賭ける */ });
    }

    setInterval(poll, POLL_MS);

    /* 画面を再表示したとき（他のアプリから戻ってきたとき）はすぐ確認する */
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') { poll(); }
    });
  }

})();
