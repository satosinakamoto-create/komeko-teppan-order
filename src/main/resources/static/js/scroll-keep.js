/*
  画面のどこを見ていたかを覚えて、戻ってきたときに同じ位置へ戻す
  （2026-09-20、店主の指摘
   「掲載中、未登録、編集ボタン押すと一番上に移動するから位置を憶えておいてほしい」）。

  ★ なぜ一番上に戻るのか。
    3 つとも「押すとページが読み込み直される」ためです。
      掲載中 … POST してリダイレクト（PRG。同じ画面へ戻る）
      未登録 … レシピの画面へ移動 → 保存すると一覧へ戻る
      編集   … 商品の編集画面へ移動 → 保存すると一覧へ戻る
    新しいページは必ず先頭から表示されるので、100 品の下のほうで
    1 つ掲載を止めると、毎回いちばん上まで戻されて探し直しになります。

  ★ 仕掛け。
    画面を離れる直前（リンクを押す・フォームを送る）に、いまの縦位置を
    sessionStorage へ入れます。戻ってきて<b>同じ画面</b>だったら、その位置へ戻します。
    使ったら消します（同じ位置へ何度も引き戻さないため）。

  ★ sessionStorage を使う理由。
    タブを閉じれば消えます。翌日に開いて、昨日見ていた場所へ飛ばされると
    かえって驚くので、その場かぎりで十分です。

  ★ 古いものは使いません（10 分）。
    商品 → カテゴリ → …と回ってしばらく経ってから戻ったときに、
    前の位置へ引き戻されると「なぜここ？」になります。

  ★ JS が動かなくても困りません。いままでどおり先頭から表示されるだけです。
*/
(function () {
  'use strict';

  var KEY = 'komeko:scroll:';
  /* これより古い記録は使わない（ミリ秒） */
  var FRESH_MS = 10 * 60 * 1000;

  function keyOf() {
    return KEY + location.pathname;
  }

  function save() {
    var y = window.scrollY || document.documentElement.scrollTop || 0;
    if (y <= 0) {
      /* 先頭にいるなら覚える意味がない。古い記録も消しておく */
      try { sessionStorage.removeItem(keyOf()); } catch (e) { /* 使えない環境 */ }
      return;
    }
    try {
      sessionStorage.setItem(keyOf(), JSON.stringify({ y: y, at: Date.now() }));
    } catch (e) {
      /* プライベートモードなどで使えないことがある。覚えられないだけ */
    }
  }

  function restore() {
    var raw;
    try {
      raw = sessionStorage.getItem(keyOf());
    } catch (e) {
      return;
    }
    if (!raw) {
      return;
    }
    try { sessionStorage.removeItem(keyOf()); } catch (e) { /* 消せなくても続ける */ }

    var saved;
    try {
      saved = JSON.parse(raw);
    } catch (e) {
      return;
    }
    if (!saved || typeof saved.y !== 'number') {
      return;
    }
    if (Date.now() - (saved.at || 0) > FRESH_MS) {
      return;
    }
    /* ★ ページが短くなっていることがあります（品を消した・絞り込んだ）。
       入らない位置へ飛ばすと、ブラウザが勝手に丸めて中途半端な所で止まります。
       入る範囲に収めてから戻します。 */
    var max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    var y = Math.min(saved.y, max);

    /* ★ behavior: 'instant' を指定すること。
       app.css の html に scroll-behavior: smooth が入っているので、
       ただの scrollTo(0, y) だと<b>読み込むたびにページがスーッと滑り降ります</b>。
       戻したいのは「さっき見ていた場所」であって、動く演出ではありません。 */
    try {
      window.scrollTo({ top: y, left: 0, behavior: 'instant' });
    } catch (e) {
      /* instant を知らない古いブラウザ。滑って見えるが位置は合う */
      window.scrollTo(0, y);
    }
  }

  /* ★ 覚えるのは「この画面から離れるとき」だけ。
     pagehide だと bfcache で戻ったときにも走って上書きされるので、
     操作そのもの（リンク・送信）を捉えます。 */
  document.addEventListener('click', function (e) {
    var a = e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) {
      return;
    }
    var href = a.getAttribute('href') || '';
    /* 別タブ・ページ内リンク・ダウンロードは移動しないので覚えない */
    if (a.target === '_blank' || a.hasAttribute('download') || href.charAt(0) === '#') {
      return;
    }
    save();
  }, true);

  document.addEventListener('submit', save, true);

  /* 画像の読み込みで高さが変わることがあるので、load を待ってから戻す。
     DOMContentLoaded だとページが短い状態で計算して、途中で止まります。 */
  if (document.readyState === 'complete') {
    restore();
  } else {
    window.addEventListener('load', restore);
  }
})();
