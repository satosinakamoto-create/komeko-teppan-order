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
 */
@DisplayName("店舗設定（営業時間・営業日）")
class ShopSettingTest {

    /** 判定の基準日。曜日や祝日は考慮していないので、どの日でも結果は同じ。 */
    private static final LocalDate DAY = LocalDate.of(2026, 8, 16);

    /** 既定値の店舗設定（開店 11:00 / ラストオーダー 18:30 / 閉店 19:00 / 切替 5時）。 */
    private ShopSetting defaultSetting() {
        return new ShopSetting();
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
        @DisplayName("テイクアウトなので税率は軽減税率の 8%")
        void taxRate() {
            assertThat(defaultSetting().getTaxRatePercent()).isEqualTo(8);
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
}
