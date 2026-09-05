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
     1. 商品詳細：個数ステッパーと合計金額のリアルタイム表示
     ------------------------------------------------------------------
     ★ 関数にして window に出しているのは、モーダル（1-b）が
       中身を差し替えたあとに、もう一度かけ直す必要があるため。
       読み込み時に 1 回だけ動く書き方だと、
       モーダルで開いた品では個数も合計も必須の判定も効かない。
     ------------------------------------------------------------------ */
  window.komekoBindItemForm = function () {
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

    /* ------------------------------------------------------------------
       選び終わるまで、注文のボタンを押せなくする
       ------------------------------------------------------------------
       「お味を 4 種類お選びください」のような必須の組は、
       足りないまま送るとサーバが弾いて商品ページに戻ってきます。
       そのとき<b>選んだ内容は消えます</b>。3 つ選んで押した人が、
       また 1 つ目から選び直すことになる。

       押せてしまうから起きる事故なので、そもそも押せなくする。
       あと何種類要るかもボタンに出す（「あと1種類」）。
       押せない理由が書いていないボタンは、ただの故障に見える。

       ★ サーバ側の検査は消さないこと。
         ここは使い勝手の話で、送られてくる内容を信じてよい理由にはならない。
         画面を通さずに POST することはいくらでもできる。
       ------------------------------------------------------------------ */
    var submitButton = addForm.querySelector('button[type=submit]');
    var submitLabel = submitButton ? submitButton.firstChild : null;

    /* 必須の組ごとに「あと何個か」を数える */
    function shortfall() {
      var short = 0;
      addForm.querySelectorAll('[data-min]').forEach(function (section) {
        var need = parseInt(section.dataset.min || '0', 10);
        if (need <= 0) { return; }
        var chosen = section.querySelectorAll('input:checked').length;
        if (chosen < need) { short += need - chosen; }
      });
      return short;
    }

    function updateSubmit() {
      if (!submitButton) { return; }
      var left = shortfall();
      submitButton.disabled = left > 0;
      if (submitLabel) {
        submitLabel.textContent = left > 0 ? ('あと' + left + '種類 ') : '注文に追加 ';
      }
    }

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
      updateSubmit();
    });

    updateSubmit();
    updateTotal();
  }
  };
  window.komekoBindItemForm();

  /* ------------------------------------------------------------------
     1-b. メニューで品を押したら、その場でモーダルに出す
     ------------------------------------------------------------------
     設計「暗05 トッピングを選ぶ」。画面を離れずに選ばせる。

     中身は商品ページ（/items/{id}）をそのまま取ってきて入れている。
     モーダル用に別の HTML を書くと、オプションの出し方も個数の増減も
     2 か所に分かれて、必ずどちらかが古くなる。

     ★ リンクは本物の <a href> のままにしてある。
       JS はクリックを横取りするだけなので、
       読み込みに失敗したときも、JS が動かないときも、
       ふつうに商品ページへ移動する。行き止まりにならない。
     ------------------------------------------------------------------ */
  var modal = document.getElementById('item-modal');
  var modalBody = document.getElementById('item-modal-body');
  if (modal && modalBody && typeof modal.showModal === 'function') {

    function openItem(url) {
      fetch(url, { headers: { 'X-Requested-With': 'fetch' } })
        .then(function (res) { return res.ok ? res.text() : Promise.reject(res.status); })
        .then(function (html) {
          /* 返ってきた HTML から <main> の中身だけ取り出す。
             DOMParser を使うのは、innerHTML に丸ごと入れると
             <script> や <head> まで持ち込んでしまうため。 */
          var doc = new DOMParser().parseFromString(html, 'text/html');
          var main = doc.querySelector('main');
          if (!main) { throw new Error('main が無い'); }

          modalBody.innerHTML = main.innerHTML;
          addCloseButton();
          modal.showModal();
          /* 商品ページ用のふるまい（個数・合計・必須の判定）を、
             入れ直した中身に対してもう一度かける */
          if (window.komekoBindItemForm) { window.komekoBindItemForm(); }
        })
        .catch(function () { window.location.href = url; });
    }

    /* 「やめる」を足す。設計では 注文に追加 の左に並ぶ */
    function addCloseButton() {
      var bar = modalBody.querySelector('.cart-bar__inner');
      if (!bar || bar.querySelector('[data-close-modal]')) { return; }
      bar.classList.add('sheet-modal__foot');
      var cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.className = 'btn btn--outline btn--lg';
      cancel.textContent = 'やめる';
      cancel.setAttribute('data-close-modal', '');
      bar.insertBefore(cancel, bar.firstChild);
    }

    document.addEventListener('click', function (event) {
      var close = event.target.closest('[data-close-modal]');
      if (close) { modal.close(); return; }

      var link = event.target.closest('a[href*="/items/"]');
      if (!link || link.target === '_blank') { return; }
      /* 修飾キー付きのクリックは、新しいタブで開きたいという意思表示 */
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) { return; }
      event.preventDefault();
      openItem(link.getAttribute('href'));
    });

    /* 背面を押したら閉じる。dialog 自身の当たり判定は中身の外側にも及ぶので、
       押された位置が中身の外なら閉じる、という判定にする */
    modal.addEventListener('click', function (event) {
      if (event.target === modal) { modal.close(); }
    });
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

  /* ------------------------------------------------------------------
     4. 「9名以上」の決定ボタンは、人数が入るまで押せない
     ------------------------------------------------------------------
     人数を入れる前から黒い（主役の）ボタンが置いてあると、
     「まずこれを押すもの」に見えて、空のまま押されます。
     実際「間違って押してしまう」と言われました。

     空で押しても onclick とサーバ側で止まるので壊れはしません。
     けれど、止められる操作をわざわざ用意しておく理由もない。
     押せないものは押せなく見せる、が本筋です。

     ★ 色を変えるだけでなく disabled にもします。
       色だけだと「押せるが目立たないボタン」に見えます。
       逆に disabled だけだと、入力しても見た目が変わらず
       「入れたのに反応がない」と感じます。両方で1組。

     ★ このボタンは name / value を持っていません（人数は input が運ぶ）。
       名前付きの送信ボタンを disabled にすると値が送られなくなりますが、
       ここはその心配がないので安全に無効化できます。
       ------------------------------------------------------------------ */
  var otherInput = document.getElementById('guestCountOther');
  var otherSubmit = document.getElementById('guestCountOtherSubmit');
  if (otherInput && otherSubmit) {
    var blocked = otherSubmit.dataset.blocked === '1';   // 営業時間外は最初から押せない

    /* 1〜8名のうち、席の定員と同じものは最初から黒く（主役に）してあります。
       押しやすい候補を先に見せるためですが、9名以上を入力しはじめると
       「黒いボタンが 2 つある」状態になり、どちらが効くのか分からなくなります。
       入力があるあいだは既定の強調を外して、黒を 1 つに保ちます。 */
    var presets = Array.prototype.slice.call(
      document.querySelectorAll('button[name="guestCount"]'));
    var highlighted = presets.filter(function (b) {
      return b.classList.contains('btn--primary');
    });

    var syncOtherSubmit = function () {
      var value = otherInput.value.trim();
      var typing = value !== '';
      var ok = !blocked && typing && Number(value) > 0;

      otherSubmit.disabled = !ok;
      otherSubmit.classList.toggle('btn--primary', ok);

      /* 入力中は既定の強調を消し、消したら元に戻す */
      highlighted.forEach(function (b) {
        b.classList.toggle('btn--primary', !typing);
      });
    };

    otherInput.addEventListener('input', syncOtherSubmit);
    /* 戻るボタンで戻ったとき、ブラウザが入力値だけ復元することがある。
       そのときも色と状態を合わせ直す */
    window.addEventListener('pageshow', syncOtherSubmit);
    syncOtherSubmit();
  }

  /* ------------------------------------------------------------------
     5. 送信中であることを、押した瞬間に見せる
     ------------------------------------------------------------------
     公開デモは 1 リクエストにおよそ 1 秒かかる（無料枠の CPU）。
     手元では 60ms なので、開発中はまったく気づけない差です。

     ブラウザは次のページが届くまで何も描き替えないので、
     押しても画面が変わらない 1 秒は「待ち時間」ではなく
     「壊れている」に見えます。実際そう言われたのが、
     いちばん最初の「人数を決める」画面でした。

     速くはできません（サーバ側の固定費なので）。
     できるのは「受け付けた」と即座に伝えることです。

     ★ 二重送信も同時に防ぎます。
       反応が無いと人はもう一度押します。人数決定を 2 回送ると
       伝票が開き直り、注文確定を 2 回送れば同じ品が 2 つ入る。
       遅さは、それ自体が事故の原因になります。
     ------------------------------------------------------------------ */
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) { return; }

    /* 押されたボタン。無ければフォーム内の最初の送信ボタン */
    var button = event.submitter
      || form.querySelector('button[type=submit], input[type=submit]');

    /* すでに送信中なら、2 回目以降は何もせずに止める */
    if (form.dataset.sending === '1') {
      event.preventDefault();
      return;
    }
    form.dataset.sending = '1';

    document.body.classList.add('is-sending');

    if (button) {
      /* 文字を差し替える前に、元の幅を固定しておく。
         そうしないとボタンが縮んで、画面がガタッと動く */
      var rect = button.getBoundingClientRect();
      if (rect.width > 0) { button.style.minWidth = Math.ceil(rect.width) + 'px'; }
      button.classList.add('is-sending');
      if (button.dataset.sendingLabel) {
        button.textContent = button.dataset.sendingLabel;
      }
    }

    /* ★ disabled にはしない。
       name/value を持つ送信ボタン（人数の 1名〜8名がそうです）を
       disabled にすると、その値がサーバへ送られません。
       「押した瞬間に無効化する」は、ここでは壊す実装になります。
       見た目と二重送信の抑止は、上のクラスと dataset で足ります。 */
  }, true);

  /* 戻るボタンで戻ってきたとき、送信中の見た目が残らないようにする
     （ブラウザは前の DOM をそのまま復元することがある） */
  window.addEventListener('pageshow', function () {
    document.body.classList.remove('is-sending');
    document.querySelectorAll('form[data-sending="1"]').forEach(function (form) {
      form.removeAttribute('data-sending');
    });
    document.querySelectorAll('.is-sending').forEach(function (el) {
      el.classList.remove('is-sending');
    });
  });

})();
