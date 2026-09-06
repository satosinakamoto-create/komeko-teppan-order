# ============================================================================
#  Render の公開デモを眠らせないための ping（このマシンから叩く版）
# ----------------------------------------------------------------------------
#  Render の無料枠は 15 分の無通信でスリープし、次の起動に 158〜168 秒かかる。
#  採用担当は待たないので、公開時間帯だけ外から定期的に叩いて起こしておく。
#
#  ■ なぜ「このマシンから」なのか
#
#  叩き役には 2 つの要件があり、両方を満たすサービスが見つからなかった。
#
#    ① 10 分おきに確実に発火すること
#    ② Render の休眠サービスを起こせること（＋ 168 秒待てること）
#
#  - cron-job.org は ① だけ。Render が x-render-routing: hibernate-wake-error を
#    返して起こしてくれない（送信元 Hetzner）。タイムアウト上限も 30 秒。
#  - GitHub Actions の schedule は ② だけ。実績は 1 日 3 回で、期待の 60 回に遠い
#    （公式に「高負荷時は遅延し、キューのジョブは破棄されることがある」と明記）。
#  - **このマシン（住宅 IP）は ① と ② の両方を満たす。** 実測で起床成功しており、
#    タイムアウトも自由に決められる。
#
#  弱点は「PC が起動してネットに繋がっている時しか効かない」こと。
#  そのため GitHub Actions 側も保険として残してある（1 日 3 回でも無いよりよい）。
#  経緯の全文は docs\デプロイ.md を見ること。
#
#  ■ 登録と解除
#
#      # 登録（8:00〜17:55 の 10 分おき）
#      .\tools\keep-render-awake.ps1 -Register
#
#      # 解除
#      .\tools\keep-render-awake.ps1 -Unregister
#
#      # 手で 1 回だけ叩く（動作確認）
#      .\tools\keep-render-awake.ps1
#
#  ■ 直すときの注意
#
#  - タイムアウトは 240 秒。起動実測が 168 秒なので 30 秒では絶対に足りない。
#  - 24 時間叩いてはいけない。Render の無料インスタンス時間はアカウント全体で
#    月 750 時間程度で、2 サービスを 24 時間起こすと破綻する。
#    加えて 5:00（営業日の切り替え）を跨いで生き続けると、起動時に入れた
#    デモの伝票が前営業日のものになり厨房ボードが空になる（docs\デプロイ.md）。
#  - ログは同じフォルダの keep-render-awake.log に残る。
#    「効いているか」はここを見るのが確実。
# ============================================================================

[CmdletBinding()]
param(
    [switch]$Register,
    [switch]$Unregister
)

$ErrorActionPreference = 'Stop'

# 叩き先。/ping は "ok" とだけ返す軽い入口
$Targets = @(
    @{ Name = 'komeko '; Url = 'https://komeko-teppan-order.onrender.com/ping' },
    @{ Name = 'mahjong'; Url = 'https://toumei-mahjong.onrender.com/ping' }
)

$TaskName = 'komeko keep-render-awake'
$LogPath  = Join-Path $PSScriptRoot 'keep-render-awake.log'
$ScriptPath = $MyInvocation.MyCommand.Path

function Write-Log([string]$Message) {
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $LogPath -Value $line -Encoding utf8

    # ここは Write-Output にしてはいけない。
    # PowerShell の関数は「拾われなかった出力すべて」を戻り値にするので、
    # Invoke-Ping の戻り値がログ行と $allOk の配列になってしまう。
    # 配列は -not で常に $false 扱いになるため、**失敗しても exit 0 になる**。
    Write-Host $line
}

function Invoke-Ping {
    # 戻り値: 全部成功したら $true
    $allOk = $true
    foreach ($t in $Targets) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            # -UseBasicParsing は IE エンジンに依存しないため、
            # ログオンしていないセッションで走らせても落ちない
            $res = Invoke-WebRequest -Uri $t.Url -TimeoutSec 240 -UseBasicParsing
            $sw.Stop()
            Write-Log ('{0}  http={1}  {2:N1}s' -f $t.Name, [int]$res.StatusCode, $sw.Elapsed.TotalSeconds)
        }
        catch {
            $sw.Stop()
            $allOk = $false
            # 起床拒否かどうかを後から判別できるようにヘッダの手がかりも残す。
            # x-render-routing: hibernate-wake-error なら Render が起こしてくれていない
            $detail = $_.Exception.Message
            $resp = $_.Exception.Response
            if ($null -ne $resp) {
                try {
                    $routing = $resp.Headers['x-render-routing']
                    if ($routing) { $detail = "$detail  [x-render-routing: $routing]" }
                }
                catch { }
            }
            Write-Log ('{0}  FAILED  {1:N1}s  {2}' -f $t.Name, $sw.Elapsed.TotalSeconds, $detail)
        }
    }

    # ログが際限なく伸びないよう、直近 1000 行だけ残す
    if (Test-Path $LogPath) {
        $lines = @(Get-Content -Path $LogPath -Encoding utf8)
        if ($lines.Count -gt 1000) {
            $lines[-1000..-1] | Set-Content -Path $LogPath -Encoding utf8
        }
    }

    return $allOk
}

if ($Register) {
    # 毎日 8:00 に始まり、9 時間 55 分のあいだ 10 分おきに実行する（＝ 8:00〜17:50）。
    #
    # **/SC MINUTE を使ってはいけない。** /MO 10 /ST 08:00 /DU ... と書くと
    # 「One Time Only, Minute」という 1 回きりのタスクになり、Next Run Time が N/A のまま
    # 翌日以降ずっと動かない。登録直後は成功したように見えるので気づきにくい。
    # 毎日繰り返すには /SC DAILY と、繰り返し間隔 /RI（分）の組み合わせが要る。
    #
    # /K は期間の終わりで実行中のタスクを止める指定。/F は既存の同名タスクを上書き。
    $action = 'powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $ScriptPath
    schtasks /Create /TN $TaskName /TR $action /SC DAILY /ST 08:00 /RI 10 /DU 0009:55 /K /F
    if ($LASTEXITCODE -eq 0) {
        # schtasks の既定は「バッテリー稼働では起動しない／バッテリーに切り替わったら止める」。
        # ノート PC だと電源を抜いた瞬間に黙って止まり、しかもエラーも出ないので気づけない。
        # 登録し直すたびに既定へ戻るため、ここで毎回外しておく。
        # あわせて 1 回の実行に上限（10 分）を付ける。240 秒 × 2 サービスでも収まる。
        $s = (Get-ScheduledTask -TaskName $TaskName).Settings
        $s.DisallowStartIfOnBatteries = $false
        $s.StopIfGoingOnBatteries     = $false
        $s.ExecutionTimeLimit         = 'PT10M'
        Set-ScheduledTask -TaskName $TaskName -Settings $s | Out-Null

        Write-Output ''
        Write-Output "登録しました: $TaskName"
        Write-Output '  8:00〜17:50 の 10 分おきに実行されます。'
        Write-Output '  状態の確認: schtasks /Query /TN "komeko keep-render-awake" /V /FO LIST'
        Write-Output "  ログ:       $LogPath"
    }
    return
}

if ($Unregister) {
    schtasks /Delete /TN $TaskName /F
    if ($LASTEXITCODE -eq 0) { Write-Output "解除しました: $TaskName" }
    return
}

# 引数なしで呼ばれたとき（＝タスクからの通常実行、または手動の動作確認）
$ok = Invoke-Ping
if (-not $ok) { exit 1 }
exit 0
