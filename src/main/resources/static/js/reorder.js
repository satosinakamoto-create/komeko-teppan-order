/*
  表の行を、つまんで動かして並べ替える（2026-09-19・店主の指示
  「並び順はドラック＆ドロップで入れ替えられる仕様にしたいかな、
    その方が直感的だし 1 つづつずらして行く必要ないし」）。

  ── なぜ HTML5 の draggable を使わないか ──────────────────────
  iOS Safari は<b>タッチでは dragstart を出しません</b>。
  この店は iPad を使うので、標準のドラッグだと iPad でだけ動かない、
  という一番たちの悪い壊れ方をします。
  ポインタイベント（pointerdown / pointermove / pointerup）なら
  マウス・タッチ・ペンを 1 本の処理で扱えます。

  ── キーボードでも動かせること ──────────────────────────────
  つまみは <button> です。Tab で選んで ↑ ↓ で 1 つずつ動かせます。
  ドラッグだけにすると、キーボードで操作する人が並べ替えできなくなります。

  ── 3 つの画面で使い回しています ────────────────────────────
  商品 ／ カテゴリ ／ 卓。必要な目印はどれも同じです。

    [data-reorder]               … この入れ物は並べ替えられる
    [data-item-id]               … 動かせる行（その入れ物の直下）
    [data-group-id]              … 同じ group どうしでしか動かせない
    [data-reorder-handle]        … つまみ
    form#reorder-form            … 送り先（id / before / after を詰める）

  ★ 入れ物と行のタグは問いません。商品は <tbody> / <tr>、
    カテゴリと卓は <ul> / <li> です。セレクタにタグ名を書かないこと。

  ── group をまたげない ──────────────────────────────────────
  商品の並び順はカテゴリごとに独立しているので、またぐ移動は
  「カテゴリを変える」ことになります。それは編集フォームの仕事です。
  カテゴリと卓は 1 本の並びなので、全行が同じ group です
  （テンプレートが "all" のような固定値を入れます）。

  ★ サーバ側でも弾いています。画面だけで守ると、
    URL を直接叩かれたときに素通りします。

  ── 保存は「ふつうのフォーム送信」──────────────────────────
  fetch ではなく form を組み立てて送ります。CSRF も PRG も、
  ほかの画面とまったく同じ扱いになります（CLAUDE.md の決まり）。
  送ったあとはサーバが並べ直した一覧を返すので、
  画面側で並びを持ち続ける必要がありません。
*/
(function () {
  'use strict';

  var tbody = document.querySelector('[data-reorder]');
  if (!tbody) return;

  // ここまで来たら JavaScript は動いている。つまみを見せる。
  // 表なら <table> に、それ以外なら入れ物そのものに印を付ける
  var host = tbody.closest('table') || tbody;
  host.classList.add('is-reorderable');

  // ── 送信 ────────────────────────────────────────────────
  // テンプレートに置いてある隠しフォームに値を詰めて送るだけ。
  // フォームを自分で作らないのは、CSRF トークンを Thymeleaf が
  // th:action で入れてくれるものをそのまま使うため。
  var form = document.getElementById('reorder-form');
  if (!form) return;

  // ★ 行き先は「どの商品の隣か」で送ります。
  //   before があればその直前、無ければ after の直後。
  //
  //   「相手が空なら先頭」という決め方はやめました（2026-09-19）。
  //   下端に落としたときは<b>その行の次が無いので送るものが無く</b>、
  //   空を送って先頭へ飛んでいました。
  //   それに絞り込んでいると、見えている最後の行のさらに下に
  //   隠れた同じカテゴリの行がいることがあり、「いちばん下」が決まりません。
  function save(itemId, beforeId, afterId) {
    form.elements.id.value = String(itemId);
    form.elements.before.value = beforeId == null ? '' : String(beforeId);
    form.elements.after.value = afterId == null ? '' : String(afterId);
    form.submit();
  }

  // いまの並びから「隣は誰か」を出す。
  // 上に行があればその行の直後、無ければ下の行の直前。
  function neighbourOf(rows, index) {
    if (index > 0) {
      return { before: null, after: rows[index - 1].dataset.itemId };
    }
    if (rows.length > 1) {
      return { before: rows[1].dataset.itemId, after: null };
    }
    return null;   // 1 行しかない。動かしようがない
  }

  // つまみから、その行（入れ物の直下にいる [data-item-id]）まで遡る
  function rowOf(handle) {
    var el = handle;
    while (el && el.parentElement !== tbody) el = el.parentElement;
    return el;
  }

  function rowsOf(groupId) {
    return Array.prototype.filter.call(
      tbody.querySelectorAll(':scope > [data-item-id]'),
      function (tr) { return tr.dataset.groupId === groupId; }
    );
  }



  // ── キーボード（↑ ↓）────────────────────────────────────
  tbody.addEventListener('keydown', function (e) {
    var handle = e.target.closest('[data-reorder-handle]');
    if (!handle) return;
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;

    var tr = rowOf(handle);
    var rows = rowsOf(tr.dataset.groupId);
    var at = rows.indexOf(tr);
    var to = e.key === 'ArrowUp' ? at - 1 : at + 1;
    if (at < 0 || to < 0 || to >= rows.length) return;   // 端。何もしない

    e.preventDefault();
    // 上へ：その行の直前。下へ：その行の直後。
    // どちらも実在する行を指すので、末尾でも先頭でも迷いません
    if (e.key === 'ArrowUp') {
      save(tr.dataset.itemId, rows[to].dataset.itemId, null);
    } else {
      save(tr.dataset.itemId, null, rows[to].dataset.itemId);
    }
  });

  // ── 動きの設定 ──────────────────────────────────────────
  // 「動きを減らす」設定の人には動かさない。
  // 並べ替えそのものは同じように使えて、滑る演出だけ止まります。
  var calm = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var SLIDE_MS = 160;   // よける行が滑る時間

  // 変形を外した「本来の位置」を測る。
  // getBoundingClientRect は transform 込みの値を返すので、
  // 掴んでいる行の位置を知るにはいったん外して測る必要があります。
  function layoutTop(el) {
    var keep = el.style.transform;
    el.style.transform = '';
    var top = el.getBoundingClientRect().top;
    el.style.transform = keep;
    return top;
  }

  // ── つまんで動かす ──────────────────────────────────────
  var dragging = null;

  tbody.addEventListener('pointerdown', function (e) {
    var handle = e.target.closest('[data-reorder-handle]');
    if (!handle) return;
    if (e.button != null && e.button !== 0) return;   // 右クリックでは始めない

    var tr = rowOf(handle);
    var rows = rowsOf(tr.dataset.groupId);
    if (rows.length < 2) return;                       // 1 行しかなければ動かしようがない

    e.preventDefault();
    handle.setPointerCapture(e.pointerId);

    var rect = tr.getBoundingClientRect();
    dragging = {
      tr: tr,
      handle: handle,
      rows: rows,
      pointerId: e.pointerId,
      // つまんだ位置と行の上端のズレ。これを保たないと行が指へ飛びつく
      offsetY: e.clientY - rect.top,
      // 変形を外した本来の位置。差し込み直すたびに測り直す
      baseTop: rect.top,
      moved: false
    };
    tr.classList.add('is-dragging');
    tbody.classList.add('is-dragging-rows');
  });

  tbody.addEventListener('pointermove', function (e) {
    if (!dragging || e.pointerId !== dragging.pointerId) return;
    e.preventDefault();
    dragging.moved = true;

    var d = dragging;

    // ① 指（マウス）の位置にいちばん近い行の境目を探す
    var y = e.clientY;
    var rows = d.rows;
    var target = null;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i] === d.tr) continue;
      var r = rows[i].getBoundingClientRect();
      var middle = r.top + r.height / 2;
      if (y < middle) { target = rows[i]; break; }
    }

    // ② 差し込む先が変わったときだけ、DOM を動かす
    var next = target || null;
    var willMove = next
      ? (next !== d.tr.nextElementSibling)
      : (rows[rows.length - 1] !== d.tr);

    if (willMove) {
      // ★ FLIP。動かす<b>前</b>に他の行の位置を控えておき、動かした<b>後</b>に
      //   「元の位置へ戻す変形」を当ててから 0 へ戻す。
      //   こうすると、行が瞬間移動せずに滑ってよけます。
      var before = [];
      if (!calm) {
        for (var k = 0; k < rows.length; k++) {
          if (rows[k] === d.tr) continue;
          before.push({ el: rows[k], top: rows[k].getBoundingClientRect().top });
        }
      }

      if (next) {
        tbody.insertBefore(d.tr, next);
      } else {
        var last = rows[rows.length - 1];
        tbody.insertBefore(d.tr, last.nextElementSibling);
      }

      if (!calm) {
        for (var m = 0; m < before.length; m++) {
          var item = before[m];
          var delta = item.top - item.el.getBoundingClientRect().top;
          if (!delta) continue;
          item.el.style.transition = 'none';
          item.el.style.transform = 'translateY(' + delta + 'px)';
        }
        // 次の描画で 0 へ戻す。ここで初めて滑って見える
        requestAnimationFrame(function () {
          for (var n = 0; n < before.length; n++) {
            var el = before[n].el;
            el.style.transition = 'transform ' + SLIDE_MS + 'ms ease';
            el.style.transform = '';
          }
        });
      }

      // 差し込み直したので、掴んでいる行の本来の位置を測り直す
      d.baseTop = layoutTop(d.tr);
    }

    // ③ 掴んでいる行は指に付いてくる
    d.tr.style.transform = 'translateY(' + (e.clientY - d.offsetY - d.baseTop) + 'px)';
  });

  // 滑る演出のために当てた指定を全部はがす
  function clearMotion(rows) {
    for (var i = 0; i < rows.length; i++) {
      rows[i].style.transition = '';
      rows[i].style.transform = '';
    }
  }

  function endDrag(e) {
    if (!dragging || (e && e.pointerId !== dragging.pointerId)) return;
    var d = dragging;
    dragging = null;

    tbody.classList.remove('is-dragging-rows');
    try { d.handle.releasePointerCapture(d.pointerId); } catch (ignore) {}

    if (!d.moved) {
      d.tr.classList.remove('is-dragging');
      clearMotion(d.rows);
      return;   // 押しただけ。並びは変わっていない
    }

    // ★ 指を離したら、掴んでいた行を本来の位置へ<b>滑らせて</b>置く。
    //   ここで transform を 0 にせずに送ると、送信中のあいだ
    //   行が指の位置に浮いたまま止まって見えます。
    d.tr.style.transition = calm ? 'none' : 'transform ' + SLIDE_MS + 'ms ease';
    d.tr.style.transform = '';
    d.tr.classList.add('is-landing');
    d.tr.classList.remove('is-dragging');

    // いまの並びから「隣は誰か」を決める
    var rows = rowsOf(d.tr.dataset.groupId);
    var side = neighbourOf(rows, rows.indexOf(d.tr));
    if (!side) return;

    // 置きにいく動きを見せてから送る。待つのは 1 回だけで、
    // 送信そのものは止めない（遅れて見えるのは 0.16 秒）
    if (calm) {
      save(d.tr.dataset.itemId, side.before, side.after);
    } else {
      window.setTimeout(function () {
        save(d.tr.dataset.itemId, side.before, side.after);
      }, SLIDE_MS);
    }
  }

  tbody.addEventListener('pointerup', endDrag);
  tbody.addEventListener('pointercancel', endDrag);
})();
