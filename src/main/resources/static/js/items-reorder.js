/*
  商品一覧の並び順を、つまんで動かす（2026-09-19・店主の指示
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

  ── カテゴリはまたげない ────────────────────────────────────
  並び順の値はカテゴリごとに独立しています。またぐ移動は「カテゴリを変える」
  ことなので、それは編集フォームの仕事です。
  ここでは同じ data-category-id の行の中だけで動かします。
  （サーバ側の MenuService#placeItemBefore でも弾いています。
    画面だけで守ると、URL を直接叩かれたときに素通りします）

  ── 保存は「ふつうのフォーム送信」──────────────────────────
  fetch ではなく form を組み立てて送ります。CSRF も PRG も、
  ほかの画面とまったく同じ扱いになります（CLAUDE.md の決まり）。
  送ったあとはサーバが並べ直した一覧を返すので、
  画面側で並びを持ち続ける必要がありません。
*/
(function () {
  'use strict';

  var tbody = document.querySelector('tbody[data-reorder]');
  if (!tbody) return;

  // ここまで来たら JavaScript は動いている。つまみを見せる
  var table = tbody.closest('table');
  if (table) table.classList.add('is-reorderable');

  // ── 送信 ────────────────────────────────────────────────
  // テンプレートに置いてある隠しフォームに値を詰めて送るだけ。
  // フォームを自分で作らないのは、CSRF トークンを Thymeleaf が
  // th:action で入れてくれるものをそのまま使うため。
  // before が空なら「いちばん上へ」。
  var form = document.getElementById('reorder-form');
  if (!form) return;

  function save(itemId, beforeId) {
    form.elements.id.value = String(itemId);
    form.elements.before.value = beforeId == null ? '' : String(beforeId);
    form.submit();
  }

  function rowsOf(categoryId) {
    return Array.prototype.filter.call(
      tbody.querySelectorAll('tr[data-item-id]'),
      function (tr) { return tr.dataset.categoryId === categoryId; }
    );
  }

  // 動かした先の「直前に入れる相手」を返す。末尾なら null ではなく
  // 「最後の行の次」を表すため、呼び出し側で場合分けする
  function beforeIdFor(rows, index) {
    return index < rows.length ? rows[index].dataset.itemId : null;
  }

  // ── キーボード（↑ ↓）────────────────────────────────────
  tbody.addEventListener('keydown', function (e) {
    var handle = e.target.closest('[data-reorder-handle]');
    if (!handle) return;
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;

    var tr = handle.closest('tr');
    var rows = rowsOf(tr.dataset.categoryId);
    var at = rows.indexOf(tr);
    var to = e.key === 'ArrowUp' ? at - 1 : at + 1;
    if (at < 0 || to < 0 || to >= rows.length) return;   // 端。何もしない

    e.preventDefault();
    // 上へ：その行の直前。下へ：その次の行の直前（＝末尾なら null）
    var beforeId = e.key === 'ArrowUp'
      ? rows[to].dataset.itemId
      : beforeIdFor(rows, to + 1);
    save(tr.dataset.itemId, beforeId);
  });

  // ── つまんで動かす ──────────────────────────────────────
  var dragging = null;   // { tr, rows, placeholder, startY, offsetY }

  tbody.addEventListener('pointerdown', function (e) {
    var handle = e.target.closest('[data-reorder-handle]');
    if (!handle) return;
    if (e.button != null && e.button !== 0) return;   // 右クリックでは始めない

    var tr = handle.closest('tr');
    var rows = rowsOf(tr.dataset.categoryId);
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
      height: rect.height,
      moved: false
    };
    tr.classList.add('is-dragging');
    tbody.classList.add('is-dragging-rows');
  });

  tbody.addEventListener('pointermove', function (e) {
    if (!dragging || e.pointerId !== dragging.pointerId) return;
    e.preventDefault();
    dragging.moved = true;

    // 指（マウス）の位置にいちばん近い行の境目を探す
    var y = e.clientY;
    var rows = dragging.rows;
    var target = null;
    for (var i = 0; i < rows.length; i++) {
      if (rows[i] === dragging.tr) continue;
      var r = rows[i].getBoundingClientRect();
      var middle = r.top + r.height / 2;
      if (y < middle) { target = rows[i]; break; }
    }

    // 掴んでいる行を、その場に差し込んで見せる（保存はまだしない）
    if (target) {
      if (target !== dragging.tr.nextElementSibling) {
        tbody.insertBefore(dragging.tr, target);
      }
    } else {
      var last = rows[rows.length - 1];
      if (last !== dragging.tr) {
        tbody.insertBefore(dragging.tr, last.nextElementSibling);
      }
    }
  });

  function endDrag(e) {
    if (!dragging || (e && e.pointerId !== dragging.pointerId)) return;
    var d = dragging;
    dragging = null;

    d.tr.classList.remove('is-dragging');
    tbody.classList.remove('is-dragging-rows');
    try { d.handle.releasePointerCapture(d.pointerId); } catch (ignore) {}

    if (!d.moved) return;   // 押しただけ。並びは変わっていない

    // いまの並びから「直前に入れる相手」を決める
    var rows = rowsOf(d.tr.dataset.categoryId);
    var at = rows.indexOf(d.tr);
    var beforeId = beforeIdFor(rows, at + 1);
    save(d.tr.dataset.itemId, beforeId);
  }

  tbody.addEventListener('pointerup', endDrag);
  tbody.addEventListener('pointercancel', endDrag);
})();
