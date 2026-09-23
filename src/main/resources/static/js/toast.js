/*
  「1 回きりの報告」を画面の上に浮かせて出す部品（2026-09-20、店主の指示
   「ポップアップは固定位置じゃなくて開いてるページトップ 40px からポッと出て来て
     3 秒したら上にゆっくり消えていく仕様に」「全てそれで統一させて欲しいから
     オブジェクト思考で作って置いて」）。

  ★ 何のために作ったか。
    それまで報告は本文のいちばん上に<b>場所を取って</b>出ていました。
    100 品の下のほうで掲載を止めると、何が起きたかを読むために
    毎回いちばん上まで戻る必要がありました。浮かせれば戻らなくて済みます。

  ── 使い方 ────────────────────────────────────────────────
    Toast.show('保存しました');              // 変更が成った（緑）
    Toast.show('選ばれていません', 'warn');   // 警告（赤）

    サーバから出すときは、HTML に data-toast を付けるだけです。
    読み込んだときにこの部品が拾って、浮かせる側へ移します。

      <div class="alert alert--success" data-toast="ok" role="status">…</div>

  ── 浮かせてよいもの・いけないもの ─────────────────────────
    ○ 浮かせる … 押した結果の報告。読み飛ばしても困らないもの
                  「掲載を止めました」「追加しました」
    ✕ 浮かせない … 中に<b>押すものがある</b>通知
                  form-draft.js の「下書きを復元しました［破棄］」は、
                  消えるとボタンごと消えて破棄できなくなります
    ✕ 浮かせない … エラー
                  複数行の箇条書きになることがあり、読み落とすと
                  なぜうまくいかなかったのか分からなくなります
                  （fragments/common.html に元からある方針）
    ✕ 浮かせない … 常設の案内（is-notice）
                  「送っていない品があります」は状態であって報告ではありません

  ★ JS が動かなくても困りません。
    data-toast が付いた札は、拾われなければ<b>今までどおり本文の上に出ます</b>。
    消えないだけで、読めなくなることはありません。
*/
window.Toast = (function () {
  'use strict';

  /* 上の帯の下に空ける距離。店主の指定「トップ 40px」 */
  var GAP_BELOW_BAR = 40;
  /* 出てから消え始めるまで */
  var STAY_MS = 3000;
  /* 消えるのにかける時間（CSS の toast-out と合わせること） */
  var FADE_MS = 500;

  var stack = null;

  /** 上の帯（sticky）の高さ。帯が無い画面では 0。 */
  function barHeight() {
    var bar = document.querySelector('.topbar');
    if (!bar) {
      return 0;
    }
    var cs = window.getComputedStyle(bar);
    /* sticky / fixed のときだけ避ける。流れの中にある帯は一緒にスクロールするので
       避ける必要がありません */
    if (cs.position !== 'sticky' && cs.position !== 'fixed') {
      return 0;
    }
    return Math.round(bar.getBoundingClientRect().height);
  }

  function ensureStack() {
    if (stack && document.body.contains(stack)) {
      stack.style.top = (barHeight() + GAP_BELOW_BAR) + 'px';
      return stack;
    }
    stack = document.createElement('div');
    stack.className = 'toast-stack';
    /* ★ 読み上げは「丁寧に」。assertive だと、入力中でも読んでいる途中に割り込みます */
    stack.setAttribute('aria-live', 'polite');
    stack.setAttribute('aria-atomic', 'false');
    stack.style.top = (barHeight() + GAP_BELOW_BAR) + 'px';
    document.body.appendChild(stack);
    return stack;
  }

  /** 浮かせる 1 枚を作って、消えるところまで面倒を見る。 */
  function mount(el) {
    ensureStack().appendChild(el);

    var done = false;
    function remove() {
      if (done) {
        return;
      }
      done = true;
      if (el.parentNode) {
        el.parentNode.removeChild(el);
      }
      /* 最後の 1 枚が消えたら入れ物も片付ける */
      if (stack && !stack.children.length && stack.parentNode) {
        stack.parentNode.removeChild(stack);
        stack = null;
      }
    }

    /* ★ タイマーだけに頼らないこと。
       animationend が来れば即座に、来なくても（動きを切っている端末・
       タブが裏にいて animation が走らない等）タイマーで必ず片付きます。 */
    el.addEventListener('animationend', function (e) {
      if (e.animationName === 'toast-out') {
        remove();
      }
    });
    window.setTimeout(remove, STAY_MS + FADE_MS + 400);
    return el;
  }

  return {
    /**
     * 浮かせて出す。
     * @param {string} text 出す文
     * @param {string} [kind] 'ok'（既定・緑＝変更が成った）か 'warn'（赤＝警告）
     */
    show: function (text, kind) {
      if (!text) {
        return null;
      }
      var el = document.createElement('div');
      /* 緑＝変更が成った／赤＝警告。青は 2026-09-20 に廃止しました */
      el.className = 'toast alert ' +
          (kind === 'warn' ? 'alert--error' : 'alert--success');
      el.setAttribute('role', 'status');
      var p = document.createElement('p');
      p.className = 'mb-0';
      p.textContent = text;
      el.appendChild(p);
      return mount(el);
    },

    /**
     * サーバが描いた札を浮かせる側へ移す。
     * 本文から取り出すので、<b>場所を取らなくなります</b>（表が押し下がらない）。
     */
    adopt: function (el) {
      if (!el) {
        return null;
      }
      el.classList.add('toast');
      el.removeAttribute('data-toast');
      return mount(el);
    }
  };
})();

/* 読み込んだときに、サーバが付けた印を拾う */
(function () {
  'use strict';
  function sweep() {
    var found = document.querySelectorAll('[data-toast]');
    for (var i = 0; i < found.length; i++) {
      window.Toast.adopt(found[i]);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', sweep);
  } else {
    sweep();
  }
})();
