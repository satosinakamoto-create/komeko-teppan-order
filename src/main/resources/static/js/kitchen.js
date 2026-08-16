/* ==========================================================================
   厨房ボードのふるまい
   --------------------------------------------------------------------------
   このファイルがやることは 3 つだけです。

     1. SSE（/api/stream/kitchen）で「注文が入った・状態が変わった」を受け取る
     2. 少し間を置いてから画面を読み直す
     3. 新しい注文のときだけ「ピッ」と鳴らす

   画面の中身を JavaScript で書き換えるのではなく、まるごと読み直す方針です。
   伝票の並び・経過時間・押せるボタンはすべてサーバ側が持っているので、
   読み直すほうが「画面とサーバの食い違い」が起きず、コードもずっと短くなります。

   経過時間（.ticket__time）をブラウザ側で 1 分ずつ足していく作りにはしていません。
   タブが裏に回ると setInterval が間引かれ、サーバの値とどんどんズレるためです。
   代わりに 60 秒ごとの読み直しで、常にサーバが計算した値を表示します。
   ========================================================================== */
(function () {
  'use strict';

  /* このスクリプトは厨房ボード専用。ボードが無いページでは何もしない
     （うっかり他の画面で読み込まれても、勝手にリロードが始まらないように） */
  if (!document.querySelector('.board')) {
    return;
  }

  /* --- 時間の設定（ミリ秒） ------------------------------------------- */

  /** 通知を受けてから読み直すまでの待ち時間。連続注文で暴れないようにする */
  var DEBOUNCE_MS = 1500;

  /** 通知が来なくても、経過時間を新しくするために読み直す間隔 */
  var PERIODIC_MS = 60000;

  /** SSE がつながらないときのフォールバック間隔 */
  var ERROR_FALLBACK_MS = 30000;

  /* ------------------------------------------------------------------
     1. 読み直しの入口（ここに一本化する）
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

  /* 経過時間の表示を新しく保つための定期読み直し。
     リロードすればこのスクリプトも読み直されるので、
     ここで setInterval を使う必要はありません（タイマーを増やさない） */
  scheduleReload(PERIODIC_MS);

  /* ボタンを押した（フォームを送信した）瞬間に予約を取り消す。
     送信中にリロードが割り込むと、せっかくの操作が途中で止まってしまうため。
     第 3 引数の true は「捕捉フェーズで拾う」指定で、
     ページ内のどのフォームの送信でも確実に呼ばれます。 */
  document.addEventListener('submit', cancelReload, true);

  /* ------------------------------------------------------------------
     2. 通知音（Web Audio API）
     ------------------------------------------------------------------
     音源ファイルは使わず、その場で波形を作って鳴らします。
     店内 Wi-Fi にインターネットが無くても、キャッシュが切れていても
     確実に鳴らすためです（＝外部ファイルへの依存をなくす）。
     ------------------------------------------------------------------ */

  var audioContext = null;

  /** AudioContext を用意する（対応していないブラウザでは null） */
  function audioCtx() {
    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) {
      return null;   /* 音は諦めて、ボードの動作だけ続ける */
    }
    if (audioContext === null) {
      audioContext = new Ctor();
    }
    return audioContext;
  }

  /**
   * 止められている音を再開させる。
   *
   * ブラウザには「ユーザーが一度も操作していないページからは音を出さない」という
   * 自動再生制限があり、作りたての AudioContext は suspended（停止）状態で始まります。
   * 解除できるのはユーザー操作の中だけなので、最初のタップ・キー入力で呼びます。
   * resume() は Promise を返し、操作前だと失敗するので握りつぶしておきます。
   */
  function unlockAudio() {
    var ctx = audioCtx();
    if (!ctx || ctx.state !== 'suspended' || !ctx.resume) {
      return null;
    }
    var resumed = ctx.resume();
    if (resumed && resumed.catch) {
      resumed.catch(function () { /* まだ鳴らせない。次の操作に賭ける */ });
    }
    return resumed;
  }

  /** 単音を 1 つ鳴らす。startOffset 秒後から duration 秒ぶん */
  function tone(ctx, frequency, startOffset, duration) {
    var startAt = ctx.currentTime + startOffset;

    var oscillator = ctx.createOscillator();   /* 音の高さを作る発振器 */
    var gain = ctx.createGain();               /* 音量を作る */

    oscillator.type = 'sine';                  /* やわらかい正弦波。厨房でも耳障りになりにくい */
    oscillator.frequency.setValueAtTime(frequency, startAt);

    /* 音量をいきなり 0 → 最大にすると「プツッ」というノイズが出るので、
       ごく短い時間でなめらかに上げ下げします */
    gain.gain.setValueAtTime(0.0001, startAt);
    gain.gain.exponentialRampToValueAtTime(0.25, startAt + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration);

    oscillator.connect(gain);
    gain.connect(ctx.destination);

    oscillator.start(startAt);
    oscillator.stop(startAt + duration + 0.02);
  }

  /** 「ピッ・ピッ」と 2 音鳴らす */
  function playTones(ctx) {
    tone(ctx, 880, 0, 0.12);     /* ラの音 */
    tone(ctx, 1320, 0.16, 0.16); /* 少し高い音。2 音にすると環境音に埋もれにくい */
  }

  /**
   * 新規注文の合図。
   *
   * resume() は非同期なので、「解除できたら鳴らす」という順番で書きます。
   * 呼んだ直後に state を見にいくと、まだ suspended のままで鳴らし損ねます。
   */
  function beep() {
    var ctx = audioCtx();
    if (!ctx) {
      return;
    }
    if (ctx.state === 'running') {
      playTones(ctx);
      return;
    }
    var resumed = unlockAudio();
    if (resumed && resumed.then) {
      resumed.then(function () { playTones(ctx); })
             .catch(function () { /* 操作前なので鳴らせない */ });
    }
  }

  /* 最初のユーザー操作で音を「解禁」する。
     { once: true } を付けると、その種類のイベントは 1 回で自動的に外れます。

     なお、この画面は 60 秒ごとに読み直されるため、そのたびに解禁はやり直しです
     （自動再生制限はページ単位で判定されるため）。
     厨房ではボタンを押しながら使うので実用上は問題になりませんが、
     開いたまま一度も触っていない状態では音が鳴らないことがあります。 */
  ['pointerdown', 'keydown', 'touchstart'].forEach(function (type) {
    window.addEventListener(type, unlockAudio, { once: true, passive: true });
  });

  /* ------------------------------------------------------------------
     3. SSE の購読
     ------------------------------------------------------------------ */

  if (!window.EventSource) {
    /* 古いブラウザ。定期読み直しだけで運用する */
    return;
  }

  var source = new EventSource('/api/stream/kitchen');
  var errorCount = 0;

  /* サーバが接続直後に送ってくる合図。ここまで来れば購読は成功 */
  source.addEventListener('connected', function () {
    errorCount = 0;
  });

  /* 注文の増減・状態変更。厨房に関係する変化はすべてこの名前で届く */
  source.addEventListener('order-changed', function (event) {
    errorCount = 0;

    var payload = null;
    try {
      payload = JSON.parse(event.data);
    } catch (e) {
      payload = null;   /* 想定外の中身でも読み直しだけは行う */
    }

    /* 新規注文のときだけ鳴らす。状態を進めた自分の操作でいちいち鳴ると邪魔なので */
    if (payload && payload.type === 'created') {
      beep();
    }

    scheduleReload(DEBOUNCE_MS);
  });

  /* 接続が切れたとき。EventSource は自動で再接続してくれるので、
     ここでは何もせず様子を見る。それでも復帰しないときの保険として
     30 秒後の読み直しを予約しておく（読み直せば接続も張り直される） */
  source.onerror = function () {
    errorCount += 1;
    if (errorCount >= 2) {
      scheduleReload(ERROR_FALLBACK_MS);
    }
  };

})();
