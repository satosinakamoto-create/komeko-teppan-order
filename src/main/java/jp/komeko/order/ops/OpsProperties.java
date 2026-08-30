package jp.komeko.order.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 運用の見張りの設定（application.yml の {@code app.ops:} 以下）。
 *
 * <p><b>なぜ「失敗したら通知」ではないのか</b><br>
 * 2026-08-21 から 4 日間、スリープ防止の cron が止まっていたのに誰も気づきませんでした。
 * 通知の設定は正しく入っていて、宛先も合っていたのに、メールは 1 通も届いていません
 * （必ず失敗する検証用ジョブまで作って確かめました）。
 * 同じ時期に、自動バックアップも 4 日間 1 本も走っていませんでした。
 *
 * <p>ここから学べるのは<b>「異常を検知して知らせる」仕組みは、
 * 異常のときに一緒に壊れる</b>ということです。アプリが落ちていれば
 * アプリからの通知も飛びません。バックアップの失敗を握り潰していれば
 * 失敗したことすら分かりません。
 *
 * <p>だからこの仕組みは逆向きに作ります。<b>無事なときだけ外部へ「生きています」を送り、
 * 送られてこなくなったら外部の側が騒ぐ</b>（デッドマンスイッチ）。
 * こうすると、アプリが死んでも・PC が落ちても・回線が切れても、
 * 「連絡が来ない」という同じ 1 つの症状として現れます。
 *
 * @param heartbeat 生存信号の設定
 */
@ConfigurationProperties(prefix = "app.ops")
public record OpsProperties(Heartbeat heartbeat) {

    public OpsProperties {
        if (heartbeat == null) {
            heartbeat = new Heartbeat(null, 0, "");
        }
    }

    /**
     * 生存信号（ハートビート）の設定。
     *
     * @param url             無事なときに叩く URL。空なら機能そのものを止める。
     *                        healthchecks.io などの「来なかったら知らせる」サービスの URL を入れる
     * @param backupMaxAgeHours バックアップがこの時間より古かったら「異常」と見なす
     * @param cron            点検を走らせる時刻（秒 分 時 日 月 曜日）
     */
    public record Heartbeat(String url, int backupMaxAgeHours, String cron) {

        public Heartbeat {
            if (backupMaxAgeHours <= 0) {
                // 定時バックアップは毎日 4:30。丸一日以上空いたら異常とみなす。
                // 開発で PC を落としている日もあるので、半端に短くすると
                // 「いつも赤い」状態になり、本当の異常に気づけなくなる。
                backupMaxAgeHours = 26;
            }
            if (cron == null || cron.isBlank()) {
                // 定時バックアップ（4:30）の 30 分後。取れたかどうかを見てから送る。
                cron = "0 0 5 * * *";
            }
        }

        /** 生存信号を送る設定になっているか。 */
        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }
}
