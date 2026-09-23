/*
  重ねて出す一覧（カテゴリから検索・並べ替え）の高さを、画面の下端まで広げる
  （2026-09-20、店主の指摘
   「カテゴリから検索は下にスクロールしなくても良い場合はしないぐらい
     max ま下まで表示して欲しい」）。

  ★ なぜ JS が要るか。
    CSS だけでは「その欄が画面の上から何 px の位置にいるか」を知れません。
    max-height: 60vh のように決め打ちすると、画面の上のほうで開いたときは
    まだ下に余っているのに内側スクロールが出て、下のほうで開いたときは
    画面からはみ出します。開いた瞬間に実測して入れるのがいちばん確かです。

  ★ JS が動かないときに使えなくしないこと（datenav.js と同じ方針）。
    CSS 側に max-height: 60vh を残してあります。読み込みに失敗しても
    「少し短いけれど開いて選べる」状態になります。

  ★ 開いているあいだだけ測り直します。
    画面の回転・ウィンドウの大きさ変え・ページのスクロールで位置が変わるので、
    そのつど入れ直します。閉じているものは触りません（毎回全部測ると重い）。
*/
(function () {
  'use strict';

  /* 一覧の下に残す余白。画面の縁にぴったり付けると切れて見える */
  var GAP = 16;
  /* これより狭くなるなら、もう内側スクロールに任せる（潰れた一覧は読めない） */
  var MIN = 160;

  var pickers = document.querySelectorAll('details.catpick, details.sortpick');
  if (!pickers.length) {
    return;
  }

  function listOf(details) {
    return details.querySelector('.catpick__list, .sortpick__list');
  }

  function fit(details) {
    var list = listOf(details);
    if (!list || !details.open) {
      return;
    }
    /* いったん上限を外して、中身が本来どれだけ要るかを測る */
    list.style.maxHeight = '';
    var needed = list.scrollHeight;

    /* 一覧の上端から画面の下端までが、使える高さ */
    var top = list.getBoundingClientRect().top;
    var room = window.innerHeight - top - GAP;

    if (room < MIN) {
      /* 下に場所が無い。上限は付けず CSS の既定（60vh）に任せる */
      return;
    }
    /* ★ 中身のほうが小さいときは上限を付けません。
       付けると、項目が 3 つしかないのに箱だけ画面の下まで伸びます。 */
    if (needed <= room) {
      return;
    }
    list.style.maxHeight = Math.floor(room) + 'px';
  }

  function fitOpenOnes() {
    for (var i = 0; i < pickers.length; i++) {
      if (pickers[i].open) {
        fit(pickers[i]);
      }
    }
  }

  for (var i = 0; i < pickers.length; i++) {
    (function (details) {
      details.addEventListener('toggle', function () {
        if (details.open) {
          fit(details);
        } else {
          var list = listOf(details);
          if (list) {
            list.style.maxHeight = '';
          }
        }
      });
    })(pickers[i]);
  }

  window.addEventListener('resize', fitOpenOnes);
  /* スクロールは数が多いので、描画に合わせて 1 回だけ走らせる */
  var waiting = false;
  window.addEventListener('scroll', function () {
    if (waiting) {
      return;
    }
    waiting = true;
    window.requestAnimationFrame(function () {
      waiting = false;
      fitOpenOnes();
    });
  }, { passive: true });
})();
