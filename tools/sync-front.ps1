# ============================================================================
#  画面まわりを、アプリを止めずに反映させる
# ----------------------------------------------------------------------------
#  CSS・JS・テンプレートを直したのに画面が変わらない、という時に使う。
#
#  なぜ要るのか
#    アプリが配信しているのは src\ ではなく target\classes\ にある写しです。
#    spring-boot:run は起動時にコピーを作るだけなので、src\ を直しても
#    走っているアプリには届きません。ここを写せば届きます。
#
#    ・テンプレート … spring.thymeleaf.cache=false（dev）なので、写した瞬間に効く
#    ・CSS / JS     … ファイル名にハッシュが混ざる作り（application.yml の
#                     spring.web.resources.chain）。中身が変われば URL ごと変わるので、
#                     写せば次の読み込みから新しいものが届く
#
#  ★ アプリを止めないこと。
#    止めて起動し直すとログインのセッションが切れます（Tomcat のセッションは
#    メモリにあり、強制終了だと書き出されないため）。
#    2026-09-12 に、この理由で 1 日に 15 回ログアウトさせてしまいました。
#
#  ★ Java を直したときはここでは足りません。
#    .java はコンパイルが要るので、アプリを起動し直してください。
# ============================================================================

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$src = Join-Path $projectRoot "src\main\resources"
$dst = Join-Path $projectRoot "target\classes"

if (-not (Test-Path $dst)) {
    Write-Host "[NG] target\classes がありません。先にアプリを起動してください。" -ForegroundColor Red
    exit 1
}

# ★ これは dev（.\tools\run.ps1 で起動した状態）専用です。
#
#   写した瞬間に効くのは、devtools が spring.web.resources.chain.cache=false を
#   入れているからです。jar で起動している側（Render のデモ・実店舗）には
#   devtools が入らないので chain.cache が既定の true のままになり、
#   古いハッシュと古い中身を握り続けます。
#   そちらで走らせても 404 にすらならず「直したのに変わらない」という
#   気づきにくい壊れ方をするので、ここで止めます。
$runningJar = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
              Where-Object { $_.CommandLine -match "komeko-order.*\.jar" }
if ($runningJar) {
    Write-Host "[NG] jar で起動しているアプリが見つかりました（PID $($runningJar.ProcessId)）。" -ForegroundColor Red
    Write-Host "  このスクリプトは dev 専用です。jar 側には効きません（黙って古いままになります）。"
    Write-Host "  jar を作り直して入れ替えてください: .\tools\run.ps1 -Package"
    exit 1
}

# static（CSS・JS・画像）と templates（HTML）の 2 つだけを写す。
# application.yml などの設定ファイルは写さない。
# 設定は起動時にしか読まれないので、写しても効かないうえに
# 「効いたつもり」の取り違えのもとになる。
$targets = @("static", "templates")

foreach ($name in $targets) {
    $from = Join-Path $src $name
    $to = Join-Path $dst $name
    if (-not (Test-Path $from)) { continue }
    Copy-Item -Path (Join-Path $from "*") -Destination $to -Recurse -Force
    $count = (Get-ChildItem $from -Recurse -File).Count
    Write-Host ("[OK] {0} : {1} ファイル" -f $name, $count) -ForegroundColor Green
}

Write-Host ""
Write-Host "写しました。ブラウザで Ctrl + Shift + R を押してください。" -ForegroundColor Cyan
Write-Host "（アプリは止めていないので、ログインしたままです）"
