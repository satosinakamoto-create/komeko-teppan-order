/* =============================================================================
   フォームの下書き保存（2026-09-14）

   なぜあるか：
     初期設定でサイドバーを上から辿ると、カテゴリより先に商品フォームへ着く。
     品名・価格を打って写真まで選んだあとに「先にカテゴリを作ってください」の
     リンクを踏むと、打った内容が全部消えていた。その保険。

   作り：
     ・form[data-draft] が付いたフォームだけが対象（いまは商品の新規だけ。
       編集はサーバ保存済みの値が正なので、古い下書きで上書きしない）
     ・localStorage に自動保存。サーバには何も送らない
       （下書きはこの端末の私物。他のスタッフに見える必要が無い）
     ・復元は「品名（[name=name]）が空のとき」だけ。
       サーバの入力エラーで戻された画面は品名が入っているので触らない
     ・送信したら消す。消さないと、次の新規に前の品の下書きが化けて出る
     ・写真（type=file）は復元できない（ブラウザがスクリプトからの
       書き込みを禁じている）。復元の知らせにその旨を書く
     ・_csrf と hidden は保存しない。トークンは毎回変わるし、
       hidden はフォームの都合の値で人の入力ではない

   外部ライブラリなし（店内 Wi-Fi にネットが無くても動く方針）。
   ========================================================================== */
(function () {
  'use strict';

  var form = document.querySelector('form[data-draft]');
  if (!form || !window.localStorage) return;

  var KEY = 'draft:' + form.dataset.draft;
  var MAX_AGE = 48 * 60 * 60 * 1000;   // 48 時間で期限切れ

  /* 保存の対象にする項目か。
     file …… 復元できないので保存も無意味
     hidden … _csrf・内部 id など、人の入力ではない
     name 無し … 送信されない項目 */
  function savable(el) {
    if (!el.name || el.name === '_csrf') return false;
    var type = (el.type || '').toLowerCase();
    if (type === 'file' || type === 'hidden' || type === 'password' || type === 'submit' || type === 'button') return false;
    return true;
  }

  function fields() {
    return Array.prototype.filter.call(form.elements, savable);
  }

  function collect() {
    var v = {};
    fields().forEach(function (el) {
      if (el.type === 'checkbox') {
        v[el.name] = el.checked;
      } else if (el.type === 'radio') {
        if (el.checked) v[el.name] = el.value;
      } else {
        v[el.name] = el.value;
      }
    });
    return v;
  }

  var timer = null;
  function save() {
    clearTimeout(timer);
    timer = setTimeout(function () {
      try {
        localStorage.setItem(KEY, JSON.stringify({ t: Date.now(), v: collect() }));
      } catch (e) { /* 容量切れ等。下書きは保険なので黙って諦める */ }
    }, 300);
  }

  function clearDraft() {
    try { localStorage.removeItem(KEY); } catch (e) { /* 同上 */ }
  }

  function restore(saved) {
    var opened = [];
    fields().forEach(function (el) {
      if (!(el.name in saved)) return;
      if (el.type === 'checkbox') {
        el.checked = !!saved[el.name];
      } else if (el.type === 'radio') {
        el.checked = (el.value === saved[el.name]);
      } else {
        el.value = saved[el.name];
      }
      /* 詳しい設定（details）の中に復元した値があるなら、開いて見せる。
         閉じたまま復元すると「消えた」と誤解される */
      var box = el.closest('details');
      if (box && saved[el.name] && opened.indexOf(box) < 0) {
        box.open = true;
        opened.push(box);
      }
    });
  }

  /* 復元の知らせ。既存の .alert を使い、新しい見た目は作らない */
  function announce() {
    var bar = document.createElement('div');
    bar.className = 'alert alert--info';
    bar.setAttribute('role', 'status');
    var p = document.createElement('p');
    p.className = 'mb-0';
    p.textContent = '書きかけの下書きを復元しました（写真は選び直してください）。 ';
    var discard = document.createElement('button');
    discard.type = 'button';
    discard.className = 'btn btn--sm btn--ghost';
    discard.textContent = '下書きを破棄';
    discard.addEventListener('click', function () {
      clearDraft();
      location.reload();
    });
    p.appendChild(discard);
    bar.appendChild(p);
    form.parentNode.insertBefore(bar, form);
  }

  /* ---- 起動 ---- */
  var raw = null;
  try { raw = localStorage.getItem(KEY); } catch (e) { /* private mode 等 */ }
  if (raw) {
    try {
      var saved = JSON.parse(raw);
      if (!saved.t || Date.now() - saved.t > MAX_AGE) {
        clearDraft();   // 期限切れ。何週間も前の下書きが化けて出ないように
      } else {
        /* 品名が空のときだけ復元する。
           サーバの入力エラーで戻された画面は品名が入っているので上書きしない */
        var sentinel = form.querySelector('[name=name]');
        if (sentinel && sentinel.value === '') {
          restore(saved.v || {});
          announce();
        }
      }
    } catch (e) {
      clearDraft();     // 壊れた JSON は捨てる
    }
  }

  form.addEventListener('input', save);
  form.addEventListener('change', save);
  form.addEventListener('submit', function () {
    /* 送信と同時に消す。サーバ側の入力エラーで戻っても、
       その画面には打った値がそのまま入っているので下書きは要らない */
    clearTimeout(timer);
    clearDraft();
  });
})();
