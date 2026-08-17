package jp.komeko.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自動バックアップの設定（application.yml の {@code app.backup:} 以下）。
 *
 * <p>「何時に取るか」「何世代残すか」は店舗ごと・環境ごとに違うので、
 * コードに埋め込まず設定ファイルに出しています。
 *
 * @param enabled            バックアップ機能そのものの ON/OFF
 * @param dir                バックアップの保存先ディレクトリ
 * @param cron               定時実行のスケジュール（Spring の cron 書式：秒 分 時 日 月 曜日）
 * @param keep               残す世代数。これを超えた古いものから自動削除される
 * @param backupOnShutdown   アプリ終了時にもバックアップを取るか。
 *                           閉店後に PC を落とす運用では、実はこれが一番確実なタイミング
 * @param backupOnStartup    起動時に「追いつきバックアップ」を取るか。
 *                           定時実行はアプリが動いている間しか走らない
 *                           （4:30 に PC が落ちていれば何も起きず、逃した回の再実行も無い）。
 *                           そこで起動のたびに「最後のバックアップからずいぶん空いていないか」を
 *                           確かめ、空いていれば 1 本取る。前夜に電源を直接切られても、
 *                           翌日の起動時に必ず追いつける
 * @param startupMaxAgeHours 起動時バックアップの判定しきい値（時間）。
 *                           最後のバックアップからこれ以上空いていたら起動時に取る
 * @param minIntervalMinutes 前回成功からこの分数以内なら自動バックアップを見送る。
 *                           開発中の自動再起動（DevTools）のたびに連発しないためのガード。
 *                           手動実行はこのガードの対象外
 * @param extraCopyDir       2次コピー先（USB ドライブや OneDrive のパス）。空なら無効。
 *                           同じ PC のディスクが壊れたときの保険なので、
 *                           できれば別の物理ドライブかクラウド同期フォルダを指定する
 */
@ConfigurationProperties(prefix = "app.backup")
public record BackupProperties(
        boolean enabled,
        String dir,
        String cron,
        int keep,
        boolean backupOnShutdown,
        boolean backupOnStartup,
        int startupMaxAgeHours,
        int minIntervalMinutes,
        String extraCopyDir
) {
    /** 2次コピーが設定されているか。 */
    public boolean hasExtraCopy() {
        return extraCopyDir != null && !extraCopyDir.isBlank();
    }
}
