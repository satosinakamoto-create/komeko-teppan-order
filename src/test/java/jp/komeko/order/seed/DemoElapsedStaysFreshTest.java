package jp.komeko.order.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公開デモの厨房ボードが、全面赤にも「赤ゼロ」にもならないこと。
 *
 * <p><b>このテストが守っているもの＝遅延の赤い印が警告として成立すること。</b>
 * 失敗は両側にあります。
 *
 * <ul>
 *   <li><b>全面赤</b> … 2026-08-24 に実際に起きました。種データは起動時に一括で
 *       作られるので全部が同時に歳を取り、夕方には 6 枚とも「378 分経過」。
 *       見せたいのは「いま回っている厨房」なのに「6 時間放置された厨房」でした。</li>
 *   <li><b>赤ゼロ</b> … その対処として、古い数字を消すしきい値を遅延と同じ
 *       10 分にしました。すると<b>赤くなる瞬間に数字のほうが消える</b>ので、
 *       赤は構造的に出なくなりました。症状を隠しただけで原因は残っていました。</li>
 * </ul>
 *
 * <p>いまは原因のほうを直しています。
 * {@code DemoDataSeeder.refreshElapsedTimes()} が定期的に時刻を引き直すので、
 * 種データは歳を取りません。だからしきい値を 90 分へ戻せました。
 *
 * <p><b>Spring は起動しません。</b>{@code DemoDataSeeder} は
 * {@code @Profile({"dev","demo"})} で、test プロファイルでは Bean が作られないためです。
 * 見たいのは「どんな値を置いているか」と「引き直しが残っているか」なので、
 * ソースから読めば足ります。素の JUnit のほうが速く、落ちたとき原因もそのまま読めます。
 */
@DisplayName("公開デモの厨房ボードは全面赤にも赤ゼロにもならない")
class DemoElapsedStaysFreshTest {

    private static final Path SEEDER =
            Path.of("src/main/java/jp/komeko/order/seed/DemoDataSeeder.java");
    private static final Path CONTROLLER =
            Path.of("src/main/java/jp/komeko/order/web/kitchen/KitchenController.java");

    /** 遅延とみなす分数（{@code KitchenController.LATE_MINUTES} と同じ値）。 */
    private static final int LATE_MINUTES = 10;

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** シーダーが置いている経過時間の一覧。 */
    private List<Integer> waitedMinutes() throws Exception {
        Matcher m = Pattern.compile("WAITED_MINUTES\\s*=\\s*\\{([^}]*)\\}").matcher(read(SEEDER));
        assertThat(m.find()).as("★ WAITED_MINUTES が見つからない").isTrue();

        List<Integer> values = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            values.add(Integer.parseInt(part.trim()));
        }
        return values;
    }

    @Test
    @DisplayName("★★ 遅れている注文を混ぜる（赤が 1 枚も出ないのは直す前の状態）")
    void someOrdersAreLate() throws Exception {
        List<Integer> waits = waitedMinutes();

        assertThat(waits.stream().filter(w -> w >= LATE_MINUTES).count())
                .as("★★ 全部 %d 分未満。公開デモで遅延の赤い印が一度も見られない", LATE_MINUTES)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("★★ 赤を少数派に保つ（全面赤は警告になっていない）")
    void mostOrdersAreNotLate() throws Exception {
        List<Integer> waits = waitedMinutes();
        long late = waits.stream().filter(w -> w >= LATE_MINUTES).count();

        // 2 倍して比べる。小数を使わないほうが、境目の読み違いが起きない
        assertThat(late * 2)
                .as("★★ 半分以上が赤い（%d / %d）。2026-08-24 の"
                        + "『6 時間放置された厨房』に戻る", late, waits.size())
                .isLessThanOrEqualTo((long) waits.size());
    }

    /**
     * ★★ 引き直しが残っていること。
     *
     * <p>これが消えると、種データはまた一斉に歳を取り、
     * 18 分の 2 枚だけでなく全部が 10 分を超えます。
     * <b>そうなっても例外は出ませんし、画面も普通に表示されます。</b>
     * 気づけるのは「夕方に開いたら全面赤だった」ときなので、ここで留めます。
     */
    @Test
    @DisplayName("★★ 経過時間を引き直す定期処理が残っている")
    void theRefreshTaskSurvives() throws Exception {
        String seeder = read(SEEDER);

        assertThat(seeder)
                .as("★★ 引き直しが消えている。種データが歳を取り、やがて全面赤になる")
                .contains("void refreshElapsedTimes()");
        assertThat(seeder)
                .as("★ 定期実行になっていない。1 回呼ぶだけでは歳を取り続ける")
                .contains("@Scheduled");
        assertThat(seeder)
                .as("★ 提供済み・キャンセルまで引き直すと、本日の売上の集計がずれる")
                .contains("o.status in :live");
    }

    /**
     * ★ しきい値が遅延と同じ値に戻っていないこと。
     *
     * <p>同じ値にすると、赤くなる条件に達した瞬間に数字のほうが消えるので、
     * <b>赤は構造的に出なくなります</b>。直す前がまさにその状態でした。
     */
    @Test
    @DisplayName("★★ 数字を消すしきい値は、遅延の閾値より大きい")
    void theStaleThresholdIsAboveTheLateThreshold() throws Exception {
        String controller = read(CONTROLLER);

        Matcher m = Pattern.compile("DEMO_STALE_MINUTES\\s*=\\s*([A-Za-z_0-9]+)\\s*;")
                .matcher(controller);
        assertThat(m.find()).as("★ DEMO_STALE_MINUTES が見つからない").isTrue();

        String value = m.group(1);
        assertThat(value)
                .as("★★ LATE_MINUTES と同じ値。赤が構造的に出なくなる")
                .isNotEqualTo("LATE_MINUTES");

        assertThat(Integer.parseInt(value))
                .as("★★ 遅延の閾値より大きいこと")
                .isGreaterThan(LATE_MINUTES);
    }
}
