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
     2. 注文状況ページ：5秒ごとに状態を見に行き、変わったら画面を更新
     ------------------------------------------------------------------ */
  var statusBox = document.getElementById('order-status');
  if (statusBox) {
    var token = statusBox.dataset.token;
    var current = statusBox.dataset.status;
    var POLL_MS = 5000;

    function poll() {
      fetch('/api/public/orders/' + encodeURIComponent(token), {
        headers: { 'Accept': 'application/json' },
        cache: 'no-store'
      })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          if (!data) { return; }

          /* 待ち状況の文字だけは毎回書き換える */
          var waitEl = document.getElementById('wait-label');
          if (waitEl) { waitEl.textContent = data.waitLabel; }
          var aheadEl = document.getElementById('ahead-count');
          if (aheadEl) { aheadEl.textContent = data.waitingOrders; }

          /* 状態が変わったらページ全体を読み直す（見た目を作り直すより確実） */
          if (data.status !== current) {
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
