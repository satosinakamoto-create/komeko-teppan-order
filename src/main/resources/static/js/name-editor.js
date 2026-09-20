/*
  カテゴリ編集画面の「名前を変える」を畳む・開く（2026-09-20）。

  ★ JS が動かないときに操作不能にしないこと。
    HTML は欄を「出した」状態で書き、この JS が畳みます。
    読み込みに失敗しても、欄が出たままになるだけで名前は直せます。
    （datenav.js に同じ方針が明文化してあります）

  ★ 検証エラーで戻ってきたときは開いたまま。
    サーバが data-name-editor="open" を付けてくるので、そのときは畳みません。
*/
(function () {
  'use strict';

  var form = document.querySelector('[data-name-editor]');
  var toggle = document.querySelector('[data-name-editor-toggle]');
  if (!form || !toggle) {
    return;
  }

  // サーバが open と言っていれば触らない（直している最中なので閉じたら困る）
  var open = form.getAttribute('data-name-editor') === 'open';
  if (!open) {
    form.setAttribute('data-name-editor', 'closed');
  }
  toggle.setAttribute('aria-expanded', open ? 'true' : 'false');

  toggle.addEventListener('click', function () {
    open = !open;
    form.setAttribute('data-name-editor', open ? 'open' : 'closed');
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) {
      var input = form.querySelector('input[type="text"]');
      if (input) {
        input.focus();
        input.select();
      }
    }
  });
})();
