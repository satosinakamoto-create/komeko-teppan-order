/* ==========================================================================
   店頭サイネージ（番号呼び出し画面）のふるまい
   --------------------------------------------------------------------------
   この画面は「誰も操作しない画面」です。朝つけて閉店まで映しっぱなしにするので、
   次の 3 つを守る作りにしています。

     1. 絶対にリロードしない
        location.reload() を使うと一瞬白くチラつき、店内から丸見えです。
        必要な番号セルだけを足し引きして DOM を書き換えます。

     2. 通信が切れても勝手に復帰する
        SSE（サーバから押し出される通知）が本命ですが、社内プロキシや古い
        ブラウザで使えないことがあります。そのときは 10 秒ごとのポーリングに
        自動で切り替え、SSE が復活したらポーリングを止めます。

     3. 取りこぼしても必ず正しい状態に戻る
        サーバから受け取るのは「差分」ではなく「いまの全番号」です。
        1 回くらい通知を落としても、次の 1 回で正しい表示に追いつけます。

   ※ このファイルは表示専用です。番号や状態を決めているのは常にサーバ側で、
      ここでの計算結果が伝票に影響することはありません。
   ========================================================================== */
(function () {
  'use strict';

  /** 現在の番号一覧を取りに行く URL（ログイン不要・番号以外は返さない） */
  var API_URL = '/api/public/display';
  /** 「注文に動きがあった」という合図が流れてくる URL */
  var STREAM_URL = '/api/stream/display';
  /** SSE が使えないときのポーリング間隔（ミリ秒） */
  var POLL_MS = 10000;

  /* ------------------------------------------------------------------
     1. 時計
     ------------------------------------------------------------------
     秒は出しません。1 秒ごとに数字が動くと、遠目に見たとき番号より時計に
     目が行ってしまうためです。更新自体は 1 秒ごとに行って、分が変わった
     瞬間にきちんと切り替わるようにしています。
  */
  var clockEl = document.getElementById('clock');

  /** 1 桁の数を「05」のように 2 桁へそろえる */
  function pad2(value) {
    return value < 10 ? '0' + value : String(value);
  }

  function tickClock() {
    if (!clockEl) { return; }
    var now = new Date();
    clockEl.textContent = pad2(now.getHours()) + ':' + pad2(now.getMinutes());
  }

  tickClock();
  setInterval(tickClock, 1000);

  /* ------------------------------------------------------------------
     2. 番号グリッドの操作
     ------------------------------------------------------------------
     パネル 1 つぶんの情報をまとめて持っておきます。
       grid  … 番号セルを並べる箱（常に DOM に存在する）
       empty … 0 件のときだけ出す「—」
       ready … お呼び出し中パネルかどうか（セルの見た目が変わる）
  */
  var panels = {
    cooking: {
      grid: document.getElementById('cooking-grid'),
      empty: document.getElementById('cooking-empty'),
      ready: false
    },
    ready: {
      grid: document.getElementById('ready-grid'),
      empty: document.getElementById('ready-empty'),
      ready: true
    }
  };

  /** いま画面に出ている番号を配列（文字列）で取り出す */
  function numbersInDom(panel) {
    var result = [];
    if (!panel.grid) { return result; }
    Array.prototype.forEach.call(panel.grid.children, function (cell) {
      result.push(cell.textContent.trim());
    });
    return result;
  }

  /** 番号セルを 1 つ作る。新しく出てくるセルなので .reveal でふわりと出す */
  function createCell(panel, numberText) {
    var cell = document.createElement('div');
    // .reveal は app.css の「表示直後にふわりと持ち上げる」アニメーション。
    // 追加されたときにだけ付けるので、すでに出ている番号は動きません。
    //
    // 補足：お呼び出し中のセルは .number-cell--ready が同じ animation プロパティを
    // 使って光り続ける指定（callout）を持っており、app.css 上で .reveal より後に
    // 書かれているためそちらが優先されます。つまりフェードインが実際に見えるのは
    // 調理中のセルで、お呼び出し中のセルは「暖色の点滅」で登場を主張します。
    // どちらも狙いは「増えたことに気づかせる」ことなので、これで用は足ります。
    cell.className = panel.ready
      ? 'number-cell number-cell--ready reveal'
      : 'number-cell reveal';
    cell.textContent = numberText;
    return cell;
  }

  /**
   * パネルの中身を「あるべき番号の並び」に合わせる。
   *
   * 全部作り直して innerHTML を入れ替えるほうが短く書けますが、それをすると
   * 変化していない番号まで一瞬消えて再描画され、点滅して見えます。
   * そこで「消えた番号だけ消し、増えた番号だけ足す」差分更新にしています。
   *
   * @param panel   panels.cooking / panels.ready
   * @param numbers サーバから来た番号の配列
   */
  function render(panel, numbers) {
    if (!panel.grid) { return; }

    // サーバからは数値で届くので、DOM の文字列と比べられるよう文字列にそろえる
    var wanted = numbers.map(String);

    // いま画面にあるセルを「番号 → 要素」の対応表にしておく
    var existing = {};
    Array.prototype.forEach.call(panel.grid.children, function (cell) {
      existing[cell.textContent.trim()] = cell;
    });

    // (a) 呼ばれ終わった／状態が変わった番号を取り除く
    Object.keys(existing).forEach(function (key) {
      if (wanted.indexOf(key) === -1) {
        panel.grid.removeChild(existing[key]);
        delete existing[key];
      }
    });

    // (b) 残ったセルを正しい順番に並べ直しつつ、足りない番号を作って挿入する
    wanted.forEach(function (numberText, index) {
      var cell = existing[numberText];
      if (!cell) {
        cell = createCell(panel, numberText);
      }
      var atIndex = panel.grid.children[index];
      if (atIndex !== cell) {
        // insertBefore は「すでに DOM にある要素」を渡すと移動になる。
        // 第 2 引数が null のときは末尾へ追加される。
        panel.grid.insertBefore(cell, atIndex || null);
      }
    });

    // (c) 0 件なら「—」を出す
    if (panel.empty) {
      panel.empty.hidden = wanted.length > 0;
    }
  }

  /* ------------------------------------------------------------------
     3. チャイム（Web Audio API）
     ------------------------------------------------------------------
     mp3 などの音源ファイルは置かず、ブラウザに 2 音を合成させて鳴らします。
     店内 Wi-Fi にインターネットが無くても鳴りますし、ファイルの読み込み待ちも
     ありません。

     ブラウザには「ユーザーが一度も触っていないページは音を出せない」という
     自動再生制限があります。画面をタップすると AudioContext を resume して
     解禁し、解禁前は例外を出さずに黙って鳴らさない、という作りにしています。
  */
  var audioContext = null;

  /** AudioContext を必要になった時点で 1 つだけ作る */
  function getAudioContext() {
    if (audioContext === null) {
      var Ctor = window.AudioContext || window.webkitAudioContext;
      if (!Ctor) { return null; }   // 非対応ブラウザ。音なしで続行する
      audioContext = new Ctor();
    }
    return audioContext;
  }

  /** 単音を 1 つ鳴らす（sine 波をゆっくり減衰させる＝やわらかいチャイム音） */
  function playTone(ctx, frequency, startAt, duration) {
    var osc = ctx.createOscillator();   // 音の素（波形）
    var gain = ctx.createGain();        // 音量カーブ

    osc.type = 'sine';
    osc.frequency.value = frequency;

    // いきなり最大音量から始めると「プチッ」というノイズが出るので、
    // ごく短い時間で立ち上げ、そのあとゆっくり減衰させる。
    // exponentialRamp は 0 を扱えないため、0 ではなく 0.0001 を使うのが定石。
    gain.gain.setValueAtTime(0.0001, startAt);
    gain.gain.exponentialRampToValueAtTime(0.25, startAt + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start(startAt);
    osc.stop(startAt + duration + 0.05);
  }

  /** 「ピン・ポーン」の 2 音を鳴らす */
  function chime() {
    var ctx = getAudioContext();
    // state が 'running' 以外＝まだ誰も画面に触っていない。無音で続行する。
    if (!ctx || ctx.state !== 'running') { return; }

    var now = ctx.currentTime;
    playTone(ctx, 880.00, now, 0.35);         // ラ
    playTone(ctx, 1174.66, now + 0.22, 0.55); // レ（少し遅らせて重ねる）
  }

  /** 画面のどこかを触ったら音を解禁する */
  function unlockAudio() {
    var ctx = getAudioContext();
    if (ctx && ctx.state === 'suspended') {
      // resume() は Promise を返す。失敗しても画面表示には影響がないので握りつぶす。
      var resumed = ctx.resume();
      if (resumed && typeof resumed.catch === 'function') {
        resumed.catch(function () { /* 解禁できなくても無音で続ける */ });
      }
    }
  }

  // 一度解禁したあとも、端末がスリープすると再び suspended に戻ることがあります。
  // そのため解除せず登録しっぱなしにして、触られるたびに resume を試みます。
  document.addEventListener('pointerdown', unlockAudio);
  document.addEventListener('click', unlockAudio);
  document.addEventListener('keydown', unlockAudio);

  /* ------------------------------------------------------------------
     4. データの反映
     ------------------------------------------------------------------ */

  // 「前回のお呼び出し中の番号」。最初はサーバが描いた HTML から読み取ります。
  // こうしておくと、画面を開いた瞬間にすでに並んでいる番号ではチャイムが鳴りません
  // （鳴るべきなのは「新しく呼ばれた番号」だけ）。
  var previousReady = numbersInDom(panels.ready);

  /**
   * サーバから受け取った JSON を画面に反映する。
   * 期待する形： {"cooking":[101,102],"ready":[99]}
   */
  function apply(data) {
    if (!data) { return; }

    var cooking = data.cooking || [];
    var ready = data.ready || [];
    var readyText = ready.map(String);

    // 前回になかった番号＝新しく呼ばれた番号
    var newlyCalled = readyText.filter(function (numberText) {
      return previousReady.indexOf(numberText) === -1;
    });

    render(panels.cooking, cooking);
    render(panels.ready, ready);
    previousReady = readyText;

    if (newlyCalled.length > 0) {
      chime();
    }
  }

  // fetch が重なると、応答の到着順によっては古い内容で上書きしてしまいます。
  // そこで「取得中は新しく投げない」ことにし、その間に来た依頼は 1 回にまとめて
  // 取得後にやり直します（忙しい時間帯に通知が連続で届いても取りこぼさない）。
  var fetching = false;
  var refreshAgain = false;

  function refresh() {
    if (fetching) {
      refreshAgain = true;
      return;
    }
    fetching = true;

    fetch(API_URL, {
      headers: { 'Accept': 'application/json' },
      cache: 'no-store'   // 途中の機器にキャッシュされると番号が古いまま止まる
    })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (data) { apply(data); })
      .catch(function () { /* 一時的な通信断。次の通知かポーリングに任せる */ })
      // .finally が使えない環境も考えて、成功・失敗どちらでも通る .then で後始末する
      .then(function () {
        fetching = false;
        if (refreshAgain) {
          refreshAgain = false;
          refresh();
        }
      });
  }

  /* ------------------------------------------------------------------
     5. 更新のきっかけ（SSE ＋ ポーリングのフォールバック）
     ------------------------------------------------------------------
     二重更新を避けるため、動いているのは常にどちらか一方だけです。
       ・SSE がつながっている間 → ポーリングは止める
       ・SSE が切れた／使えない → ポーリングを動かす
  */
  var pollTimer = null;

  function startPolling() {
    if (pollTimer === null) {
      pollTimer = setInterval(refresh, POLL_MS);
    }
  }

  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  if (window.EventSource) {
    var source = new EventSource(STREAM_URL);

    // 接続できた合図。ポーリングを止めて、取りこぼしがないよう 1 回だけ取り直す。
    source.addEventListener('connected', function () {
      stopPolling();
      refresh();
    });

    // 注文が入った・状態が変わった。中身は使わず「取りに行け」の合図として扱う。
    source.addEventListener('order-changed', function () {
      refresh();
    });

    source.onopen = function () {
      stopPolling();
    };

    // 接続が切れると EventSource は自動で再接続を試みます。
    // その「つながっていない間」だけポーリングで穴を埋めます。
    source.onerror = function () {
      startPolling();
    };
  } else {
    // 古いブラウザなど SSE そのものが無い環境
    startPolling();
  }

  // ディスプレイの電源を入れ直した直後など、画面が見える状態に戻ったら
  // すぐ最新化する（裏に回っている間はブラウザがタイマーを間引くため）。
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') {
      refresh();
    }
  });

})();
