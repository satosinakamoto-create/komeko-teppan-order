<#
    ============================================================================
     GitHub 連携の初期設定 — 一度だけ実行するスクリプト
    ============================================================================
     これを実行すると「ライブラリの自動更新」が動き出します。

     事前に必要なもの（未インストールなら先にこの2つ）:
       winget install --id GitHub.cli     ← インストール後 PowerShell を開き直す
       gh auth login                       ← ブラウザで GitHub にログイン

     実行:
       .\tools\setup-github.ps1

     やること:
       1. GitHub に非公開(private)リポジトリを作って push
       2. 脆弱性アラートを有効化（危ないライブラリを使っていたら通知が来る）
       3. 自動マージを許可
       4. main ブランチに「テストが通らないとマージできない」保護を設定
     → 以後、Dependabot の更新 PR はテスト通過後に自動で取り込まれる
    ============================================================================
#>
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

Write-Host ""
Write-Host "===== GitHub 連携セットアップ =====" -ForegroundColor DarkYellow
Write-Host ""

# ---------------------------------------------------------------------------
# 0. 前提チェック
# ---------------------------------------------------------------------------
$ghCmd = Get-Command gh -ErrorAction SilentlyContinue
if ($null -eq $ghCmd) {
    Write-Host "[NG] GitHub CLI (gh) が見つかりません。" -ForegroundColor Red
    Write-Host "  1) winget install --id GitHub.cli"
    Write-Host "  2) PowerShell を開き直す"
    Write-Host "  3) gh auth login"
    Write-Host "  4) このスクリプトをもう一度実行"
    exit 1
}

# gh auth status は未ログインだと 0 以外で終了する
& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[NG] GitHub にログインしていません。" -ForegroundColor Red
    Write-Host "  gh auth login  を実行してから、もう一度このスクリプトを実行してください。"
    exit 1
}
Write-Host "[OK] GitHub CLI ログイン済み" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 1. リポジトリ作成 + push（すでに remote があれば push だけ）
# ---------------------------------------------------------------------------
$hasRemote = (git remote) -contains "origin"
if (-not $hasRemote) {
    Write-Host "[..] 非公開リポジトリを作成して push します" -ForegroundColor Yellow
    # --private: まず非公開で作る。ポートフォリオとして公開したくなったら
    #            あとから  gh repo edit --visibility public  で切り替えられる
    gh repo create komeko-teppan-order --private --source . --remote origin --push
    if ($LASTEXITCODE -ne 0) { Write-Host "[NG] リポジトリ作成に失敗しました" -ForegroundColor Red; exit 1 }
}
else {
    Write-Host "[..] 既存の origin へ push します" -ForegroundColor Yellow
    git push -u origin main
    if ($LASTEXITCODE -ne 0) { Write-Host "[NG] push に失敗しました" -ForegroundColor Red; exit 1 }
}
Write-Host "[OK] push 完了" -ForegroundColor Green

$full = gh repo view --json nameWithOwner -q .nameWithOwner
Write-Host "     リポジトリ: https://github.com/$full"

# ---------------------------------------------------------------------------
# 2. 脆弱性アラート ON（使用ライブラリに脆弱性が見つかったら通知が来る）
# ---------------------------------------------------------------------------
gh api -X PUT "repos/$full/vulnerability-alerts" | Out-Null
Write-Host "[OK] 脆弱性アラートを有効化" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 3. 自動マージを許可（テスト通過後に GitHub がマージできるようにする）
# ---------------------------------------------------------------------------
gh api -X PATCH "repos/$full" -F allow_auto_merge=true | Out-Null
Write-Host "[OK] 自動マージを許可" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 4. main ブランチ保護:「CI のテストが通るまでマージ禁止」
#    これが無いと自動マージがテストを待たずに即マージしてしまう。
#    enforce_admins=false なので、あなた自身の直接 push は今まで通りできる。
# ---------------------------------------------------------------------------
$protection = @'
{
  "required_status_checks": { "strict": false, "contexts": ["test"] },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}
'@
$protection | gh api -X PUT "repos/$full/branches/main/protection" --input - | Out-Null
Write-Host "[OK] ブランチ保護（テスト必須）を設定" -ForegroundColor Green

Write-Host ""
Write-Host "===== 完了。これからの流れ =====" -ForegroundColor DarkYellow
Write-Host "  ・脆弱性が見つかる → GitHub からメール通知"
Write-Host "  ・ライブラリ更新   → 週1で PR が立ち、テスト通過後に自動マージ"
Write-Host "  ・メジャー更新のみ → PR が残るので内容を見てから手動マージ"
Write-Host "  ・店の PC への反映 → 月1くらいで  .\tools\run.ps1 -Update"
Write-Host ""
Write-Host "  公開に切り替えるとき: gh repo edit --visibility public --accept-visibility-change-consequences"
Write-Host ""
