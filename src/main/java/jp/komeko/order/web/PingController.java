package jp.komeko.order.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生存確認だけを返す、いちばん軽い入口。
 *
 * <p><b>何のためにあるのか</b><br>
 * 無料のホスティング（Render など）は、しばらく誰も見に来ないとサーバを眠らせます。
 * 次に開いた人は、目覚めるまで 30〜40 秒待たされます。
 * ポートフォリオを見に来た採用担当は、まず待ってくれません。
 *
 * <p>そこで<b>外から定期的にここを叩いて</b>起こしておきます
 * （UptimeRobot や cron-job.org のような、外部の時計を使うサービス）。
 *
 * <p><b>アプリの中にタイマーを置いても意味がありません。</b>
 * 眠っているあいだは、そのタイマー自体が止まっているからです。
 * 自分で自分を起こすことはできません。だから外から叩きます。
 *
 * <p><b>なぜ専用の入口を作るのか</b><br>
 * トップページを叩いても起きますが、1 回ごとに DB を読んで HTML を組み立てます。
 * 10 分おきに叩けば 1 日 60 回。その全部が無駄な仕事です。
 * ここは文字列を 1 つ返すだけで、DB も画面も触りません。
 *
 * <p>{@code SecurityConfig} で認証不要にしてあります。
 * ログインが要ると、叩くたびにログイン画面が描画されてしまい、
 * 「軽い入口」の意味が無くなるためです。
 */
@RestController
public class PingController {

    /**
     * 生きていれば {@code ok} とだけ返す。
     *
     * <p>{@code text/plain} を明示しているのは、
     * 監視サービスが中身を「ok が含まれるか」で判定できるようにするためです。
     * HTTP 200 だけを見る設定でも構いませんが、
     * 中身まで見ておくと「起動はしたが中身が壊れている」状態にも気づけます。
     */
    @GetMapping(value = "/ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ping() {
        return "ok";
    }
}
