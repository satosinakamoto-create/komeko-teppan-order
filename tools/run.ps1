<#
    ============================================================================
     米粉と鉄板 モバイルオーダー — 起動スクリプト
    ============================================================================
     使い方（PowerShell でプロジェクトのフォルダを開いて）:

         .\tools\run.ps1                 … 開発モードで起動
         .\tools\run.ps1 -Package        … 実行可能 jar を作る
         .\tools\run.ps1 -Test           … テストを流す
         .\tools\run.ps1 -Port 8081      … ポートを変えて起動

     Maven が入っていない場合は .tools\ 配下に自動でダウンロードして使います。
    ============================================================================
#>
param(
    [switch]$Package,
    [switch]$Test,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

# Java は UTF-8 でログを出すが、Windows のコンソールは既定が CP932。
# そのままだとログの日本語が文字化けして、エラーの原因が読めなくなる。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 古い環境では失敗することがあるが、動作には影響しないので無視する
}

# プロジェクトのルート（このスクリプトの 1 つ上）へ移動する
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

Write-Host ""
Write-Host "===== 米粉と鉄板 モバイルオーダー =====" -ForegroundColor DarkYellow
Write-Host ""

# ---------------------------------------------------------------------------
# 1. JDK の確認
# ---------------------------------------------------------------------------
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javaCmd) {
    Write-Host "[NG] Java が見つかりません。" -ForegroundColor Red
    Write-Host ""
    Write-Host "  JDK 21 をインストールしてください:"
    Write-Host "    https://adoptium.net/temurin/releases/?version=21"
    Write-Host "  インストール後、PowerShell を開き直してから再実行してください。"
    exit 1
}

# java -version はバージョン情報を「標準エラー出力」に出す（昔からの仕様）。
#
# ここで PowerShell の `java -version 2>&1` を使ってはいけない。
# Windows PowerShell 5.1 は、ネイティブ exe の標準エラー出力を 2>&1 で受けると
# 1 行ずつ ErrorRecord に包んでしまい、$ErrorActionPreference = "Stop" のもとでは
# 「正常終了したのに例外で止まる」という挙動になる。
# リダイレクトを cmd 側にやらせれば、PowerShell にはただの文字列として届く。
$versionText = (cmd /c "java -version 2>&1" | Out-String)
$major = 0
if ($versionText -match 'version "(\d+)') {
    $major = [int]$Matches[1]
}

if ($major -lt 21) {
    Write-Host "[NG] Java $major が見つかりましたが、このアプリには JDK 21 以上が必要です。" -ForegroundColor Red
    Write-Host "  https://adoptium.net/temurin/releases/?version=21"
    exit 1
}
Write-Host "[OK] Java $major" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 2. Maven の確認（無ければダウンロードする）
# ---------------------------------------------------------------------------
$mavenVersion = "3.9.9"
$toolsDir     = Join-Path $projectRoot ".tools"
$mavenHome    = Join-Path $toolsDir "apache-maven-$mavenVersion"
$mavenExe     = $null

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -ne $mvnCmd) {
    $mavenExe = $mvnCmd.Source
    Write-Host "[OK] Maven (インストール済み)" -ForegroundColor Green
}
elseif (Test-Path (Join-Path $mavenHome "bin\mvn.cmd")) {
    $mavenExe = Join-Path $mavenHome "bin\mvn.cmd"
    Write-Host "[OK] Maven $mavenVersion (.tools 配下)" -ForegroundColor Green
}
else {
    Write-Host "[..] Maven が見つからないためダウンロードします ($mavenVersion)" -ForegroundColor Yellow

    if (-not (Test-Path $toolsDir)) {
        New-Item -ItemType Directory -Path $toolsDir | Out-Null
    }
    $zipPath = Join-Path $toolsDir "apache-maven-$mavenVersion-bin.zip"
    $url = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"

    try {
        # TLS 1.2 を明示（古い PowerShell だと既定で無効なことがある）
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
        Expand-Archive -Path $zipPath -DestinationPath $toolsDir -Force
        Remove-Item $zipPath -Force
    }
    catch {
        Write-Host "[NG] Maven のダウンロードに失敗しました。" -ForegroundColor Red
        Write-Host "  手動でインストールするか、IntelliJ IDEA / VS Code から起動してください。"
        Write-Host "  詳細: $($_.Exception.Message)"
        exit 1
    }

    $mavenExe = Join-Path $mavenHome "bin\mvn.cmd"
    Write-Host "[OK] Maven $mavenVersion を .tools に用意しました" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 3. 実行
# ---------------------------------------------------------------------------
Write-Host ""

if ($Test) {
    Write-Host "テストを実行します..." -ForegroundColor Cyan
    & $mavenExe -q test
    exit $LASTEXITCODE
}

if ($Package) {
    Write-Host "実行可能 jar をビルドします..." -ForegroundColor Cyan
    & $mavenExe -q clean package
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[OK] target\komeko-order-0.1.0.jar ができました" -ForegroundColor Green
        Write-Host "  起動: java -jar target\komeko-order-0.1.0.jar"
    }
    exit $LASTEXITCODE
}

Write-Host "アプリを起動します。停止するには Ctrl+C を押してください。" -ForegroundColor Cyan
Write-Host ""
Write-Host "  お客さん用 : http://localhost:$Port/"        -ForegroundColor DarkYellow
Write-Host "  厨房       : http://localhost:$Port/kitchen" -ForegroundColor DarkYellow
Write-Host "  呼び出し   : http://localhost:$Port/display" -ForegroundColor DarkYellow
Write-Host "  管理       : http://localhost:$Port/admin"   -ForegroundColor DarkYellow
Write-Host ""

& $mavenExe spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
