<#
    ============================================================================
     ポートフォリオ用：スマホと厨房画面を並べて録画するための準備
    ============================================================================
     使い方（PowerShell でプロジェクトのフォルダを開いて）:

         .\tools\demo-screens.ps1              … 左にスマホ、右に厨房画面を並べる
         .\tools\demo-screens.ps1 -Right hall  … 右をホール（会計）画面にする
         .\tools\demo-screens.ps1 -NoPhone     … スマホを使わず PC の画面だけ並べる

     やること:
       1. スマホの画面を PC のウィンドウとして表示する（scrcpy）
       2. その隣に厨房ボードを開く
       3. 画面の左右に並べて、1本の録画に両方収まるようにする

     なぜ 1本にまとめるのか:
       スマホ単体の録画と厨房画面単体の録画を別々に置くと、
       見る人が頭の中で繋げないといけないので「連携している」ことが伝わりません。
       同じ画面の中で、左で注文した瞬間に右へ届く。これが見せたい絵です。

     前提:
       ・スマホで USB デバッグを許可し、USB で繋いでおくこと
         （設定 → デバイス情報 → ビルド番号を7回連打 → 開発者向けオプション）
       ・アプリを .\tools\run.ps1 -Demo で起動しておくこと
    ============================================================================
#>
param(
    # 右側に出す画面。kitchen（厨房ボード）/ hall（ホール・会計）/ admin（管理）
    [ValidateSet("kitchen", "hall", "admin")]
    [string]$Right = "kitchen",

    # スマホを繋がずに、PC の画面だけ並べたいとき
    [switch]$NoPhone,

    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "===== 撮影用の画面を並べます =====" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------------------
# 1. アプリが動いているか確かめる
# ---------------------------------------------------------------------------
$appUrl = "http://localhost:$Port"
try {
    $null = Invoke-WebRequest -Uri "$appUrl/" -UseBasicParsing -TimeoutSec 4
    Write-Host "[OK] アプリは動いています ($appUrl)" -ForegroundColor Green
} catch {
    Write-Host "[NG] アプリが応答しません。先に別の窓で起動してください:" -ForegroundColor Red
    Write-Host "       .\tools\run.ps1 -Demo" -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------------
# 2. 画面の大きさを調べて、左右の割り当てを決める
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.Windows.Forms
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$halfWidth = [int]($screen.Width / 2)

Write-Host ("     画面 {0}x{1} → 左右 {2}px ずつに分けます" -f $screen.Width, $screen.Height, $halfWidth)

# ---------------------------------------------------------------------------
# 3. スマホの画面を出す（scrcpy）
# ---------------------------------------------------------------------------
if (-not $NoPhone) {
    $adb = Join-Path $env:USERPROFILE "tools\platform-tools\adb.exe"
    $scrcpyPath = $null

    $cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
    if ($cmd) {
        $scrcpyPath = $cmd.Source
    } else {
        # winget で入れた直後は PATH が今のセッションに反映されていないことがある
        $pkgRoot = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
        $found = Get-ChildItem $pkgRoot -Filter "scrcpy.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $scrcpyPath = $found.FullName }
    }

    if (-not $scrcpyPath) {
        Write-Host "[NG] scrcpy が見つかりません。次で入ります:" -ForegroundColor Red
        Write-Host "       winget install Genymobile.scrcpy" -ForegroundColor Yellow
        return
    }

    # スマホが繋がっているか
    $devices = @()
    if (Test-Path $adb) {
        $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" }
    }
    if ($devices.Count -eq 0) {
        Write-Host "[NG] スマホが見つかりません。USB で繋いで、" -ForegroundColor Red
        Write-Host "     画面に出る「USB デバッグを許可しますか？」で 許可 を押してください。" -ForegroundColor Yellow
        Write-Host "     （スマホ無しで並べるだけなら -NoPhone を付けて実行）" -ForegroundColor DarkGray
        return
    }
    Write-Host "[OK] スマホを検出しました" -ForegroundColor Green

    # scrcpy の起動オプション
    #   --window-title  録画のときに見分けやすくする
    #   --window-*      左半分の中央に、縦長のまま置く
    #   --stay-awake    撮影中に画面が消えないように
    #   --turn-screen-off は使わない（実機が光っていないと嘘っぽく見えるため）
    $phoneWidth = [int]($screen.Height * 0.42)
    $phoneX = [int](($halfWidth - $phoneWidth) / 2)

    $scrcpyArgs = @(
        "--window-title=スマホ（お客さま）",
        "--window-x=$phoneX",
        "--window-y=0",
        "--window-height=$($screen.Height)",
        "--stay-awake"
    )
    Start-Process -FilePath $scrcpyPath -ArgumentList $scrcpyArgs
    Write-Host "     左側にスマホ画面を出しました" -ForegroundColor DarkGray
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------------------
# 4. 右側にスタッフ画面を開く
# ---------------------------------------------------------------------------
$rightUrl = "$appUrl/$Right"

$label = "厨房ボード"
if ($Right -eq "hall")  { $label = "ホール（会計）" }
if ($Right -eq "admin") { $label = "管理画面" }

# --window-position / --window-size は Chrome / Edge 共通のオプション
$browser = Get-Command chrome -ErrorAction SilentlyContinue
if (-not $browser) { $browser = Get-Command msedge -ErrorAction SilentlyContinue }

if ($browser) {
    $browserArgs = @(
        "--new-window",
        "--window-position=$halfWidth,0",
        "--window-size=$halfWidth,$($screen.Height)",
        $rightUrl
    )
    Start-Process -FilePath $browser.Source -ArgumentList $browserArgs
    Write-Host ("     右側に{0}を開きました" -f $label) -ForegroundColor DarkGray
} else {
    Start-Process $rightUrl
    Write-Host ("     {0}を開きました（位置は手で調整してください）" -f $label) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# 5. 撮影の段取りを表示する
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "===== 撮影の手順 =====" -ForegroundColor Cyan
Write-Host ""
Write-Host "  録画は Windows の [Win]+[Alt]+[R]（Xbox Game Bar）が手軽です。" -ForegroundColor Gray
Write-Host "  画面全体を撮って、あとで左右まとめて切り出します。" -ForegroundColor Gray
Write-Host ""
Write-Host "  1本目（15秒）注文が厨房へ飛ぶまで" -ForegroundColor Yellow
Write-Host "     スマホで卓のQRを読む → 人数を選ぶ → 商品を2つ選ぶ"
Write-Host "     → 注文リスト → この内容で注文する → 右の厨房ボードに出る"
Write-Host ""
Write-Host "  2本目（10秒）状態がリアルタイムで伝わる" -ForegroundColor Yellow
Write-Host "     右の厨房で「調理中」→「提供済」を押す"
Write-Host "     → 左のスマホの伝票が、触っていないのに変わる"
Write-Host ""
Write-Host "  3本目（10秒）会計の内訳" -ForegroundColor Yellow
Write-Host "     .\tools\demo-screens.ps1 -Right hall で開き直し"
Write-Host "     → 伝票を開く → 小計・テーブルチャージ・深夜料金の内訳を見せる"
Write-Host ""
Write-Host "  深夜料金を映したいときは、店舗設定で開始時刻をいまの時刻より前にします" -ForegroundColor DarkGray
Write-Host "  （管理画面 → 店舗設定 → 深夜料金の開始時刻）。撮り終わったら 23:00 に戻すこと。" -ForegroundColor DarkGray
Write-Host ""
