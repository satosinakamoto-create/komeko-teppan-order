/* ==========================================================================
   ホール・会計ボードのふるまい
   --------------------------------------------------------------------------
   このファイルがやることは 2 つだけです。

     1. SSE（/api/stream/hall）で「注文が入った・状態が変わった」を受け取る
     2. 少し間を置いてから画面を読み直す

   画面の中身を JavaScript で書き換えるのではなく、まるごと読み直す方針です。
   金額・滞在時間・空席の判定はすべてサーバ側が持っているので、
   読み直すほうが「画面とサーバの食い違い」が起きず、コードもずっと短くなります。
   とくに金額は、ブラウザ側で計算し直すと請求額がズレる事故に直結します。

   厨房（kitchen.js）と違い、音は鳴らしません。
   ホールはお客さまのすぐそばで使う画面なので、静かなほうがよいためです。

   なお、このスクリプトは伝票一覧（hall/board.html）でだけ動きます。
   会計画面（hall/bill.html）では読み込んでいません。
   メモを入力している最中や、お会計ボタンを押す直前にページが読み直されると、
   入力が消えたりチェックの状態が戻ったりして、金銭事故のもとになるためです。
   ========================================================================== */
(function () {
  'use strict';

  /* この画面かどうかの目印。無ければ何もしない
     （うっかり他の画面で読み込まれても、勝手にリロードが始まらないように） */
  if (!document.querySelector('[data-hall-board]')) {
    return;
  }

  /* --- 時間の設定（ミリ秒） ------------------------------------------- */

  /** 通知を受けてから読み直すまでの待ち時間。
      1 組が続けて注文すると通知が連続で届くので、少し待ってまとめて 1 回にする */
  var DEBOUNCE_MS = 2000;

  /** SSE がつながらないときのフォールバック。
      通知が来なくても、この間隔で読み直せば表示が古いままにはならない */
  var FALLBACK_MS = 45000;

  /* ------------------------------------------------------------------
     読み直しの入口（ここに一本化する）
     ------------------------------------------------------------------
     タイマーをあちこちで作ると、通知・定期・エラー復帰が重なったときに
     1 秒に何度もリロードが走る「暴走」が起きます。
     そこで予約は必ずこの関数を通し、タイマーは常に 1 本だけにします。
     ------------------------------------------------------------------ */

  var reloadTimer = null;   /* setTimeout の ID。null なら未予約 */
  var reloadAt = 0;         /* 予約している時刻（Date.now() ベース） */

  function scheduleReload(delayMs) {
    var at = Date.now() + delayMs;

    /* すでに、より早い（または同時刻の）予約があるならそのまま使う。
       あとから来た遅い予約で上書きすると、いつまでも読み直されなくなる */
    if (reloadTimer !== null && reloadAt <= at) {
      return;
    }
    if (reloadTimer !== null) {
      window.clearTimeout(reloadTimer);
    }
    reloadAt = at;
    reloadTimer = window.setTimeout(function () {
      reloadTimer = null;
      window.location.reload();
    }, delayMs);
  }

  /** 予約を取り消す */
  function cancelReload() {
    if (reloadTimer !== null) {
      window.clearTimeout(reloadTimer);
      reloadTimer = null;
    }
  }

  /* まずフォールバックを 1 本だけ予約しておく。
     リロードすればこのスクリプトも読み直されるので、
     ここで setInterval を使う必要はありません（タイマーを増やさない） */
  scheduleReload(FALLBACK_MS);

  /* 「ご案内」などのボタンを押した（フォームを送信した）瞬間に予約を取り消す。
     送信中にリロードが割り込むと、せっかくの操作が途中で止まってしまうため。
     第 3 引数の true は「捕捉フェーズで拾う」指定で、
     ページ内のどのフォームの送信でも確実に呼ばれます。 */
  document.addEventListener('submit', cancelReload, true);

  /* ==================================================================
     モーダル（2026-09-12）
     ==================================================================
     ★ 開いているあいだはリロードを止めます。
       この画面は 45 秒ごと・注文が入るたびに読み直します。
       支払方法を選んでいる最中にページが張り直されると、
       チェックが戻って「押したつもりの内容と違う額で締まる」事故になります。
       会計画面（bill.html）でこのスクリプトを読み込んでいないのと同じ理由です。
     ================================================================== */

  function openModals() {
    return document.querySelectorAll('dialog.hallmodal[open]');
  }

  /** モーダルが開いているあいだは予約しない。閉じたときに掛け直す */
  var baseScheduleReload = scheduleReload;
  scheduleReload = function (delayMs) {
    if (openModals().length > 0) {
      return;
    }
    baseScheduleReload(delayMs);
  };

  /** ペイン（段）を切り替える。フォームは 1 つのまま、表示だけ入れ替える */
  function showPane(form, index) {
    var panes = form.querySelectorAll('[data-pane]');
    for (var i = 0; i < panes.length; i++) {
      panes[i].hidden = (panes[i].getAttribute('data-pane') !== String(index));
    }
    var box = form.closest('dialog');
    if (box) { box.scrollTop = 0; }
  }

  /** 1 枚目で選んだ人数を 2 枚目の見出しに持ち越す */
  function echoGuests(form) {
    var echo = form.querySelector('[data-guest-echo]');
    if (!echo) { return; }
    var other = form.querySelector('[data-guest-other]');
    var picked = form.querySelector('input[name="guestCount"]:checked');
    var n = (other && other.value) ? other.value : (picked ? picked.value : '');
    echo.textContent = n ? (n + ' 名さま') : '';
  }

  document.addEventListener('click', function (e) {
    var el = e.target.closest ? e.target.closest('[data-open-modal],[data-close-modal],[data-pane-next],[data-pane-prev],[data-guest-other-apply]') : null;
    if (!el) { return; }

    /* 開く */
    var openId = el.getAttribute('data-open-modal');
    if (openId) {
      var dlg = document.getElementById(openId);
      if (dlg && dlg.showModal) {
        cancelReload();
        var f = dlg.querySelector('[data-panes]');
        if (f) { showPane(f, 1); }
        dlg.showModal();
      }
      return;
    }

    /* 閉じる。閉じたらリロードの予約を掛け直す */
    if (el.hasAttribute('data-close-modal')) {
      var owner = el.closest('dialog');
      if (owner) { owner.close(); }
      baseScheduleReload(FALLBACK_MS);
      return;
    }

    /* 次へ／戻る */
    var next = el.getAttribute('data-pane-next');
    var prev = el.getAttribute('data-pane-prev');
    if (next || prev) {
      var form = el.closest('[data-panes]');
      if (!form) { return; }
      if (next) {
        /* 人数を選ばずに進ませない。サーバ側でも既定値は入るが、
           押した場所で気づけたほうが速い */
        var other = form.querySelector('[data-guest-other]');
        var picked = form.querySelector('input[name="guestCount"]:checked');
        if (form.querySelector('input[name="guestCount"]') && !picked && !(other && other.value)) {
          alert('何名さまかを選んでください。');
          return;
        }
        echoGuests(form);
      }
      showPane(form, next || prev);
      return;
    }

    /* 9 名以上の「決定」。チップの選択を外して、入力した数を使う */
    if (el.hasAttribute('data-guest-other-apply')) {
      var form2 = el.closest('[data-panes]');
      var input = form2.querySelector('[data-guest-other]');
      var v = parseInt(input.value, 10);
      /* ★ 押しても何も起きない、を無くす（2026-09-17・店主の指摘）。
         それまでは focus するだけで、画面には何の変化もありませんでした。
         店主は「決定が効かない」と受け取って別のボタンで進んでいます。
         入れていないのか、9 未満で弾かれたのかを言い分けます。 */
      if (!v || v < 9) {
        alert(input.value
          ? '9 名以上のときだけこちらを使います。8 名までは上のボタンから選んでください。'
          : '人数を入れてから「決定」を押してください。');
        input.focus();
        return;
      }
      var radios = form2.querySelectorAll('input[name="guestCount"]');
      for (var j = 0; j < radios.length; j++) { radios[j].checked = false; }
      echoGuests(form2);
    }
  });

  /* チップを選び直したら、9 名以上の入力は捨てる（両方送らない） */
  document.addEventListener('change', function (e) {
    if (e.target && e.target.name === 'guestCount') {
      var form = e.target.closest('[data-panes]');
      if (!form) { return; }
      var other = form.querySelector('[data-guest-other]');
      if (other) { other.value = ''; }
      echoGuests(form);
    }
  });

  /* Esc で閉じたときもリロードを掛け直す */
  document.addEventListener('close', function (e) {
    if (e.target && e.target.matches && e.target.matches('dialog.hallmodal')) {
      baseScheduleReload(FALLBACK_MS);
    }
  }, true);

  /* ------------------------------------------------------------------
     SSE の購読
     ------------------------------------------------------------------ */

  if (!window.EventSource) {
    /* 古いブラウザ。予約済みのフォールバックだけで運用する */
    return;
  }

  /* サーバは接続直後に "connected" を 1 回送ってきますが、
     ホールでは何もすることがない（音も鳴らさない）ので受け取りません。 */
  var source = new EventSource('/api/stream/hall');

  /* 注文の増減・状態変更。伝票の金額が変わる可能性があるので読み直す */
  source.addEventListener('order-changed', function () {
    scheduleReload(DEBOUNCE_MS);
  });

  /* 接続が切れたとき。EventSource は自動で再接続してくれるので、
     ここでは何もしません。復帰しなくても、最初に予約したフォールバックの
     読み直しでページごと張り直されます（予約を増やさない＝暴走させない）。 */
  source.onerror = function () {
    scheduleReload(FALLBACK_MS);
  };

})();
