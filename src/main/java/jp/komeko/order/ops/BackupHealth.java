package jp.komeko.order.ops;

import java.time.LocalDateTime;
import java.util.List;

/**
 * バックアップが健全かどうかの点検結果。
 *
 * <p><b>「取れているか」ではなく「戻せる状態か」を見ます。</b>
 * バックアップのコードが例外を出さずに終わったことは、何の保証にもなりません。
 * 実際に 2026-08-25・08-27〜08-29 の 4 日間、例外も出ないまま
 * 1 本もバックアップが作られていませんでした。
 *
 * <p>だからこの点検は、バックアップ処理の<b>報告</b>ではなく<b>結果</b>を見ます。
 * フォルダを実際に覗いて、新しい世代があるか、中身が空でないか、
 * 2 次コピー先にも届いているかを確かめます。
 *
 * @param healthy   すべての点検に通ったか
 * @param problems  見つかった問題（healthy なら空）
 * @param latestGeneration 見つかった最新の世代名（例: 20260830-181155）。無ければ null
 * @param latestAt  最新世代の日時。無ければ null
 */
public record BackupHealth(
        boolean healthy,
        List<String> problems,
        String latestGeneration,
        LocalDateTime latestAt
) {

    /** 問題なし。 */
    public static BackupHealth ok(String generation, LocalDateTime at) {
        return new BackupHealth(true, List.of(), generation, at);
    }

    /** 問題あり。 */
    public static BackupHealth failed(List<String> problems, String generation, LocalDateTime at) {
        return new BackupHealth(false, List.copyOf(problems), generation, at);
    }

    /** ログや画面に出す 1 行の要約。 */
    public String summary() {
        if (healthy) {
            return "バックアップは正常です（最新: " + latestGeneration + "）";
        }
        return "バックアップに問題があります: " + String.join(" / ", problems);
    }
}
