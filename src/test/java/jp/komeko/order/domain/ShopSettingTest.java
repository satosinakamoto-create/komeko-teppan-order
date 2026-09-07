package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShopSetting}（店舗設定）のテスト。
 *
 * <p><b>このテストが守っているもの＝「開いていない時間に注文が入らないこと」</b><br>
 * 営業時間外やラストオーダー後に注文が通ってしまうと、
 * 誰もいない厨房に伝票だけが溜まり、お客さんは待ちぼうけになります。
 * 逆に、判定が厳しすぎて営業中なのに注文できないのも売上機会の損失です。
 * 境目の 1 分をきちんと決めておくのがこのテストの役目です。
 *
 * <p><b>{@code LocalDateTime} を引数で受け取る設計のありがたさ</b><br>
 * {@code isOrderAcceptable()} が内部で {@code LocalDateTime.now()} を呼んでいたら、
 * 「営業前のふるまい」を確かめるにはテストを朝 10 時に走らせるしかありません。
 * 時刻を引数で受け取る形にしておくと、好きな時刻を渡して何度でも試せます。
 * これは「テストしやすさのための設計（テスタビリティ）」の代表例です。
 *
 * <p><b>この店は日付をまたいで営業する</b><br>
 * 実店舗「米粉と鉄板」は 17:30 開店・翌 2:00 閉店（ラストオーダー 翌 1:30）です。
 * 開店時刻より閉店時刻のほうが「時刻としては小さい」ため、
 * 素直に {@code open <= t && t <= lastOrder} と書くと<b>一日中 false</b> になります。
 * ここを間違えると「営業中なのに一切注文できない」という致命的な状態になるので、
 * 日をまたぐケースを重点的にテストしています。
 */
@DisplayName("店舗設定（営業時間・営業日）")
class ShopSettingTest {

    /** 判定の基準日。曜日や祝日は考慮していないので、どの日でも結果は同じ。 */
    private static final LocalDate DAY = LocalDate.of(2026, 8, 16);

    /** 既定値の店舗設定（開店 11:00 / ラストオーダー 18:30 / 閉店 19:00 / 切替 5時）。 */
    private ShopSetting defaultSetting() {
        return new ShopSetting();
    }

    /**
     * 実店舗「米粉と鉄板」の営業時間（17:30 開店・翌 1:30 ラストオーダー・翌 2:00 閉店）。
     *
     * <p>DataSeeder が初回起動時に入れている値と同じものを、テストでも組み立てています。
     */
    private ShopSetting lateNightShopSetting() {
        ShopSetting setting = new ShopSetting();
        setting.setOpenTime(LocalTime.of(17, 30));
        setting.setLastOrderTime(LocalTime.of(1, 30));   // 翌 1:30
        setting.setCloseTime(LocalTime.of(2, 0));        // 翌 2:00（＝26:00）
        setting.setBusinessDayCutoverHour(5);
        return setting;
    }

    private LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute));
    }

    @Nested
    @DisplayName("注文を受け付けられるかの判定")
    class OrderAcceptable {

        @Test
        @DisplayName("既定値は 開店11:00 / ラストオーダー18:30 / 閉店19:00")
        void defaults() {
            // 以下のテストが「何を前提にしているか」を明示しておく。
            // 既定値を変えたときにここが真っ先に落ちて気づける。
            ShopSetting setting = defaultSetting();

            assertThat(setting.getOpenTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(setting.getLastOrderTime()).isEqualTo(LocalTime.of(18, 30));
            assertThat(setting.getCloseTime()).isEqualTo(LocalTime.of(19, 0));
            assertThat(setting.isAcceptingOrders()).isTrue();
        }

        @ParameterizedTest(name = "{0}時{1}分 → 受付可能: {2}")
        @CsvSource({
                " 9,  0, false",  // 仕込み中。まだ開いていない
                "10, 59, false",  // 開店1分前（境界の直前）
                "11,  0, true",   // 開店ちょうど。ここから受け付ける
                "14, 30, true",   // ど真ん中の営業中
                "18, 30, true",   // ラストオーダーちょうどは「まだ間に合う」
                "18, 31, false",  // ラストオーダーを1分過ぎたら受け付けない
                "19,  0, false",  // 閉店時刻
                "22,  0, false"   // 完全に営業終了後
        })
        void byTimeOfDay(int hour, int minute, boolean expected) {
            ShopSetting setting = defaultSetting();

            assertThat(setting.isOrderAcceptable(at(hour, minute))).isEqualTo(expected);
        }

        @Test
        @DisplayName("手動で受付を止めていれば、営業時間内でも受け付けない")
        void manuallyStopped() {
            // 「行列がさばけない」ときに店長がワンタップで止めるための非常ブレーキ。
            // 時間の判定より優先されなければ意味がない。
            ShopSetting setting = defaultSetting();
            setting.setAcceptingOrders(false);

            assertThat(setting.isOrderAcceptable(at(14, 30))).isFalse();
        }

        @Test
        @DisplayName("営業時間を変更すると判定もそれに追従する")
        void customHours() {
            // 設定を DB に置いている意味＝店長が現場で変えられること。
            // 変えた値がちゃんと効くことを確認しておく。
            ShopSetting setting = defaultSetting();
            setting.setOpenTime(LocalTime.of(8, 0));
            setting.setLastOrderTime(LocalTime.of(9, 30));

            assertThat(setting.isOrderAcceptable(at(7, 59))).isFalse();
            assertThat(setting.isOrderAcceptable(at(8, 0))).isTrue();
            assertThat(setting.isOrderAcceptable(at(9, 30))).isTrue();
            assertThat(setting.isOrderAcceptable(at(9, 31))).isFalse();
        }
    }

    @Nested
    @DisplayName("日をまたぐ営業時間（17:30 開店・翌1:30 ラストオーダー）")
    class LateNightBusinessHours {

        @Test
        @DisplayName("前提：開店17:30 / ラストオーダー翌1:30 / 閉店翌2:00")
        void premise() {
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.getOpenTime()).isEqualTo(LocalTime.of(17, 30));
            assertThat(setting.getLastOrderTime()).isEqualTo(LocalTime.of(1, 30));
            assertThat(setting.getCloseTime()).isEqualTo(LocalTime.of(2, 0));
            // 開店より「時刻として小さい」ラストオーダー＝日をまたぐ営業、という関係
            assertThat(setting.getLastOrderTime()).isBefore(setting.getOpenTime());
        }

        /**
         * 判定に使うのは<b>時刻だけ</b>で、日付は見ていません。
         * そのため「翌 1:00」も {@code at(1, 0)} と書けば同じ意味になります。
         */
        @ParameterizedTest(name = "{0}時{1}分 → 受付可能: {2}")
        @CsvSource({
                "15,  0, false",  // 昼。まだ開いていない
                "17, 29, false",  // 開店1分前（境界の直前）
                "17, 30, true",   // 開店ちょうど
                "20,  0, true",   // ピークタイム
                "23,  0, true",   // 深夜料金が始まる時刻。まだ受付は続く
                " 0, 30, true",   // 日付をまたいだ直後。ここが false になる実装ミスが多い
                " 1,  0, true",   // 翌1時。まだラストオーダー前
                " 1, 30, true",   // ラストオーダーちょうどは「まだ間に合う」
                " 1, 31, false",  // 1分過ぎたら受け付けない
                " 2,  0, false",  // 閉店時刻
                " 5,  0, false",  // 早朝。完全に営業時間外
                "12,  0, false"   // 昼
        })
        void byTimeOfDay(int hour, int minute, boolean expected) {
            // ★このクラスでいちばん大事なテスト★
            // 「0:30 も 1:00 も受付可、1:31 は不可」が正しく出せるかどうか。
            // ここを単純な比較で書くと、営業のいちばん忙しい時間帯に
            // 全員が注文できなくなる（＝店が止まる）。
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.isOrderAcceptable(at(hour, minute))).isEqualTo(expected);
        }

        @Test
        @DisplayName("日をまたぐ営業でも、手動の受付停止のほうが優先される")
        void manualStopWinsEvenAtNight() {
            ShopSetting setting = lateNightShopSetting();
            setting.setAcceptingOrders(false);

            assertThat(setting.isOrderAcceptable(at(23, 0))).isFalse();
            assertThat(setting.isOrderAcceptable(at(0, 30))).isFalse();
        }

        @Test
        @DisplayName("開店前は開店時刻を、ラストオーダー後は受付終了を案内する")
        void rejectReasonTellsWhich() {
            // 日をまたぐ営業では「開店前」と「LO 後」の境目があいまいになる。
            // 15:00 は開店待ち、3:00 は店じまい、と人の感覚どおりに出し分けたい。
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.orderRejectReason(at(15, 0)))
                    .contains("17:30")
                    .contains("営業");
            assertThat(setting.orderRejectReason(at(3, 0)))
                    .contains("01:30")
                    .contains("終了");
        }

        @Test
        @DisplayName("深夜1時の注文は「前日の営業日」として集計される")
        void midnightOrdersBelongToPreviousBusinessDate() {
            // 深夜 1 時は店の感覚では「昨日の営業ぶん」。
            // 暦の日付で集計すると 1 日の売上が 2 日に割れて、日報が実感と合わなくなる。
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.businessDateOf(at(1, 0))).isEqualTo(DAY.minusDays(1));
            assertThat(setting.businessDateOf(at(23, 0))).isEqualTo(DAY);
        }
    }

    @Nested
    @DisplayName("深夜料金の判定")
    class LateNightSurcharge {

        @Test
        @DisplayName("既定は 23:00 から 10% 割増")
        void defaults() {
            ShopSetting setting = defaultSetting();

            assertThat(setting.getLateNightStartTime()).isEqualTo(LocalTime.of(23, 0));
            assertThat(setting.getLateNightSurchargePercent()).isEqualTo(10);
        }

        @ParameterizedTest(name = "{0}時{1}分 → 深夜料金: {2}")
        @CsvSource({
                "18,  0, false",  // 夕方。通常料金
                "22, 59, false",  // 開始1分前（境界の直前）
                "23,  0, true",   // 開始ちょうどから割増
                "23, 59, true",
                " 0,  0, true",   // 日付をまたいでも深夜のまま
                " 1, 30, true",   // ラストオーダーの時刻も深夜
                " 4, 59, true",   // 営業日の切り替え（5時）直前まで
                " 5,  0, false",  // 5時以降は「翌日の昼」として通常料金
                "10,  0, false"
        })
        void byTimeOfDay(int hour, int minute, boolean expected) {
            // 「23:00 以降」を単純に t >= 23:00 と書くと、深夜 1:00 が対象外になる。
            // 日をまたぐ側にこそ深夜料金がかかるので、そこを取りこぼさないことが要点。
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.isLateNight(at(hour, minute))).isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 割増率が 0% でも、判定は窓だけを見る（2026-09-07 に反転）")
        void rateDoesNotAffectTheWindowCheck() {
            // 以前はここで「率 0 なら深夜でも対象にならない」を固定していた。
            // だがこのメソッドは伝票の再計算に判定役として渡されるもので、
            // 現在の率を見ると、営業中に率を 10→0 に変えた瞬間、
            // 率 10 をコピーして開いている OPEN 伝票からも深夜料金が消えた
            // （全体点検 #3。LateNightRateSnapshotTest が経路ごと固定している）。
            // かけるかどうか・何%かは伝票側のスナップショットの仕事。
            // 率 0 の店では伝票の率も 0 なので、true を返しても金額は付かない。
            ShopSetting setting = lateNightShopSetting();
            setting.setLateNightSurchargePercent(0);

            assertThat(setting.isLateNight(at(23, 30))).isTrue();
            assertThat(setting.isLateNight(at(1, 0))).isTrue();
            // 窓の外は従来どおり対象外
            assertThat(setting.isLateNight(at(12, 0))).isFalse();
        }

        @Test
        @DisplayName("開始時刻を変えると判定もそれに追従する")
        void customStartTime() {
            ShopSetting setting = lateNightShopSetting();
            setting.setLateNightStartTime(LocalTime.of(22, 0));

            assertThat(setting.isLateNight(at(21, 59))).isFalse();
            assertThat(setting.isLateNight(at(22, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("受け付けられない理由の文言")
    class RejectReason {

        @Test
        @DisplayName("手動停止中は店長が設定したメッセージを返す")
        void whenManuallyStopped() {
            ShopSetting setting = defaultSetting();
            setting.setAcceptingOrders(false);
            setting.setClosedMessage("本日は完売しました");

            assertThat(setting.orderRejectReason(at(14, 30))).isEqualTo("本日は完売しました");
        }

        @Test
        @DisplayName("営業前は開店時刻を案内する")
        void beforeOpen() {
            ShopSetting setting = defaultSetting();

            assertThat(setting.orderRejectReason(at(9, 0)))
                    .contains("11:00")
                    .contains("営業");
        }

        @Test
        @DisplayName("ラストオーダー後は受付終了を案内する")
        void afterLastOrder() {
            ShopSetting setting = defaultSetting();

            assertThat(setting.orderRejectReason(at(18, 45)))
                    .contains("18:30")
                    .contains("終了");
        }

        @Test
        @DisplayName("受付可能なときは空文字（画面に何も出さない）")
        void whenAcceptable() {
            // null ではなく空文字を返すのがポイント。
            // 画面側で null チェックを書かなくても安全に th:text できる。
            ShopSetting setting = defaultSetting();

            assertThat(setting.orderRejectReason(at(14, 30))).isEmpty();
        }
    }

    @Nested
    @DisplayName("営業日の判定")
    class BusinessDate {

        /**
         * <b>なぜ「暦の日付」と「営業日」を分けるのか</b><br>
         * 深夜 1 時の注文は、店の感覚では「昨日の営業分」です。
         * 暦の日付で集計すると、1 日の売上が 2 日に分かれてしまい、
         * 日報の数字が現場の実感と合わなくなります。
         */
        @Test
        @DisplayName("既定の切り替え時刻は 5 時")
        void defaultCutover() {
            assertThat(defaultSetting().getBusinessDayCutoverHour()).isEqualTo(5);
        }

        @Test
        @DisplayName("切り替え時刻より前の注文は前日の営業日になる")
        void beforeCutoverBelongsToPreviousDay() {
            ShopSetting setting = defaultSetting();

            assertThat(setting.businessDateOf(at(0, 30))).isEqualTo(DAY.minusDays(1));
            assertThat(setting.businessDateOf(at(4, 59))).isEqualTo(DAY.minusDays(1));
        }

        @Test
        @DisplayName("切り替え時刻ちょうど以降はその日の営業日になる")
        void afterCutoverBelongsToSameDay() {
            ShopSetting setting = defaultSetting();

            assertThat(setting.businessDateOf(at(5, 0))).isEqualTo(DAY);
            assertThat(setting.businessDateOf(at(12, 0))).isEqualTo(DAY);
            assertThat(setting.businessDateOf(at(23, 59))).isEqualTo(DAY);
        }

        @Test
        @DisplayName("切り替え時刻を 0 にすると 暦の日付＝営業日 になる")
        void cutoverZeroMeansCalendarDate() {
            // 深夜営業をしない店ならこの設定にしておくと分かりやすい。
            ShopSetting setting = defaultSetting();
            setting.setBusinessDayCutoverHour(0);

            assertThat(setting.businessDateOf(at(0, 0))).isEqualTo(DAY);
            assertThat(setting.businessDateOf(at(4, 59))).isEqualTo(DAY);
        }
    }

    @Nested
    @DisplayName("会計まわりの既定値")
    class Defaults {

        @Test
        @DisplayName("クラスの既定値は 8%（実店舗の 10% は DataSeeder が上書きする）")
        void taxRate() {
            // ここは「新しい ShopSetting を作ったときの初期値」の確認です。
            // 実店舗は酒類を扱うため軽減税率の対象外（10%）で、
            // その値は初回起動時に DataSeeder が入れています。
            // 過去の伝票の税率は TableSession / Order 側にコピーされるので、
            // ここを変えても既存の会計は変わりません。
            assertThat(defaultSetting().getTaxRatePercent()).isEqualTo(8);
        }

        @Test
        @DisplayName("テーブルチャージは1名あたり ¥450")
        void tableCharge() {
            // 席についた時点で必ず発生する金額。
            // 0 になっていると、伝票から丸ごと抜け落ちて毎組ぶん取りっぱぐれる。
            assertThat(defaultSetting().getTableChargePerGuest()).isEqualTo(450);
        }

        @Test
        @DisplayName("注文番号は 101 から始まる（3桁で呼びやすい）")
        void orderNumberStart() {
            assertThat(defaultSetting().getOrderNumberStart()).isEqualTo(101);
        }

        @Test
        @DisplayName("鉄板の同時調理数は 1 以上（0 だと待ち時間計算がゼロ除算になる）")
        void griddleCapacity() {
            assertThat(defaultSetting().getGriddleCapacity()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("24 時間受付モード")
    class AlwaysOpen {

        /**
         * <b>なぜこのモードが要るのか</b><br>
         * 営業時間外は人数選択のボタンすら押せません。仕様としては正しいのですが、
         * 動作確認をしたいだけのときも毎回営業時間をいじる羽目になり、
         * 直したあと戻し忘れる（＝ 開店しても注文を受けられない）事故のもとでした。
         * 「今夜だけ朝までやる」のような急な変更にも、時刻を計算し直さず対応できます。
         */
        @Test
        @DisplayName("既定は OFF（従来どおり時刻で受付を止める）")
        void defaultIsOff() {
            assertThat(defaultSetting().isAlwaysOpen()).isFalse();
        }

        @ParameterizedTest(name = "{0}時{1}分でも受け付ける")
        @CsvSource({" 3, 0", " 9, 0", "12, 0", "16, 0", "23, 59"})
        @DisplayName("ON にすると、営業時間外でも一日中受け付ける")
        void acceptsAnyTime(int hour, int minute) {
            // 既定（11:00〜18:30）なら、このうち 12:00 と 16:00 以外は受け付けない時刻。
            ShopSetting setting = defaultSetting();
            setting.setAlwaysOpen(true);

            assertThat(setting.isOrderAcceptable(at(hour, minute))).isTrue();
        }

        @Test
        @DisplayName("24 時間受付でも「今すぐ止める」は効く（非常ブレーキは独立）")
        void manualStopStillWins() {
            // ここが崩れると、混雑時に注文を止める手段が無くなる。
            // 2 つのスイッチが別物であることを保証しておく。
            ShopSetting setting = defaultSetting();
            setting.setAlwaysOpen(true);
            setting.setAcceptingOrders(false);

            assertThat(setting.isOrderAcceptable(at(12, 0))).isFalse();
            assertThat(setting.orderRejectReason(at(12, 0))).isEqualTo(setting.getClosedMessage());
        }

        @Test
        @DisplayName("受け付けているあいだは、断り文句を出さない")
        void noRejectReasonWhileOpen() {
            ShopSetting setting = defaultSetting();
            setting.setAlwaysOpen(true);

            assertThat(setting.orderRejectReason(at(3, 0))).isEmpty();
        }

        @Test
        @DisplayName("OFF に戻すと、元の営業時間がそのまま復活する")
        void turningOffRestoresHours() {
            // 営業時間を消さずに「使わないだけ」にしてあるので、
            // 戻したときに入れ直す必要がない。
            ShopSetting setting = defaultSetting();
            setting.setAlwaysOpen(true);
            assertThat(setting.isOrderAcceptable(at(9, 0))).isTrue();

            setting.setAlwaysOpen(false);

            assertThat(setting.isOrderAcceptable(at(9, 0))).isFalse();
            assertThat(setting.isOrderAcceptable(at(12, 0))).isTrue();
        }

        @Test
        @DisplayName("24 時間受付にしても、深夜料金は時刻どおりにかかる")
        void lateNightStillApplies() {
            // 「止めない」と「割増しない」は別の話。
            // 24 時間営業でも深夜料金は取る、という運用があり得る。
            ShopSetting setting = defaultSetting();
            setting.setAlwaysOpen(true);

            assertThat(setting.isLateNight(at(23, 30))).isTrue();
            assertThat(setting.isLateNight(at(12, 0))).isFalse();
        }
    }

    @Nested
    @DisplayName("深夜料金の範囲（開始〜終了）")
    class LateNightRange {

        @Test
        @DisplayName("既定は 23:00 〜 5:00")
        void defaults() {
            assertThat(defaultSetting().getLateNightStartTime()).isEqualTo(LocalTime.of(23, 0));
            assertThat(defaultSetting().getLateNightEndTime()).isEqualTo(LocalTime.of(5, 0));
        }

        @Test
        @DisplayName("終了時刻を変えると、そこで深夜が終わる")
        void customEndTime() {
            // 「閉店の 2:00 までを深夜料金の対象にしたい」という設定ができる。
            ShopSetting setting = lateNightShopSetting();
            setting.setLateNightEndTime(LocalTime.of(2, 0));

            assertThat(setting.isLateNight(at(1, 59))).isTrue();
            assertThat(setting.isLateNight(at(2, 0))).isFalse();   // 終了ちょうどは対象外
            assertThat(setting.isLateNight(at(4, 0))).isFalse();
        }

        @Test
        @DisplayName("営業日の切り替え時刻を変えても、深夜料金の範囲は動かない")
        void independentFromBusinessDayCutover() {
            // 以前は切り替え時刻を深夜の終わりに流用していたため、
            // 売上の集計基準を直すだけのつもりが料金まで変わってしまった。
            // この 2 つが無関係であることを、テストで固定しておく。
            ShopSetting setting = lateNightShopSetting();
            setting.setBusinessDayCutoverHour(3);

            assertThat(setting.isLateNight(at(4, 0))).isTrue();            // 深夜の終わりは 5:00 のまま
            assertThat(setting.businessDateOf(at(4, 0))).isEqualTo(DAY);   // 集計だけが変わる
        }

        @Test
        @DisplayName("開店時刻を動かしても、深夜料金の境界は 1 秒も動かない")
        void independentFromOpenTime() {
            // 「時刻を円として扱い、起点から何秒進んだかで比較する」と説明すると、
            // 起点が 1 つしか無いように聞こえて
            // 「開店を早めたら深夜料金も早く始まるのでは？」と読めてしまう。
            // 実際は範囲ごとに自分の起点を持つ。深夜料金の起点は lateNightStartTime であって、
            // openTime は isLateNight の計算に一度も登場しない。
            // 開店時刻を大きく動かしても境界が動かないことを、ここで固定しておく。
            ShopSetting setting = lateNightShopSetting();

            for (LocalTime open : new LocalTime[]{
                    LocalTime.of(11, 0), LocalTime.of(17, 30),
                    LocalTime.of(0, 0), LocalTime.of(22, 59)}) {
                setting.setOpenTime(open);

                assertThat(setting.isLateNight(at(22, 59)))
                        .as("開店 %s のとき 22:59 は深夜ではない", open).isFalse();
                assertThat(setting.isLateNight(at(23, 0)))
                        .as("開店 %s のとき 23:00 は深夜", open).isTrue();
                assertThat(setting.isLateNight(at(4, 59)))
                        .as("開店 %s のとき 4:59 は深夜", open).isTrue();
                assertThat(setting.isLateNight(at(5, 0)))
                        .as("開店 %s のとき 5:00 は深夜ではない", open).isFalse();
            }
        }

        @Test
        @DisplayName("ラストオーダー・閉店・24時間受付を変えても、深夜料金の境界は動かない")
        void independentFromOtherHours() {
            ShopSetting setting = lateNightShopSetting();
            setting.setLastOrderTime(LocalTime.of(3, 0));
            setting.setCloseTime(LocalTime.of(4, 0));
            setting.setAlwaysOpen(true);

            assertThat(setting.isLateNight(at(22, 59))).isFalse();
            assertThat(setting.isLateNight(at(23, 0))).isTrue();
            assertThat(setting.isLateNight(at(5, 0))).isFalse();
        }

        @Test
        @DisplayName("深夜料金の境界は秒単位まで正確（分に丸められたりしない）")
        void boundaryIsExactToTheSecond() {
            // 「数分ずれる」ことがないのを固定する。
            // 22:59:59 はまだ通常料金、23:00:00 ちょうどから深夜。
            ShopSetting setting = lateNightShopSetting();

            assertThat(setting.isLateNight(LocalDateTime.of(DAY, LocalTime.of(22, 59, 59)))).isFalse();
            assertThat(setting.isLateNight(LocalDateTime.of(DAY, LocalTime.of(23, 0, 0)))).isTrue();
            assertThat(setting.isLateNight(LocalDateTime.of(DAY, LocalTime.of(4, 59, 59)))).isTrue();
            assertThat(setting.isLateNight(LocalDateTime.of(DAY, LocalTime.of(5, 0, 0)))).isFalse();
        }

        @Test
        @DisplayName("日をまたがない範囲（14:00〜17:00 のような昼の割増）も同じ欄で書ける")
        void nonWrappingRange() {
            // 「深夜」という名前だが、中身はただの時刻範囲。
            // 日をまたぐ・またがないを場合分けせずに扱えるのがこの実装の狙い。
            ShopSetting setting = defaultSetting();
            setting.setLateNightStartTime(LocalTime.of(14, 0));
            setting.setLateNightEndTime(LocalTime.of(17, 0));

            assertThat(setting.isLateNight(at(13, 59))).isFalse();
            assertThat(setting.isLateNight(at(14, 0))).isTrue();
            assertThat(setting.isLateNight(at(16, 59))).isTrue();
            assertThat(setting.isLateNight(at(17, 0))).isFalse();
            assertThat(setting.isLateNight(at(23, 0))).isFalse();   // 夜は対象外になる
        }
    }
}
