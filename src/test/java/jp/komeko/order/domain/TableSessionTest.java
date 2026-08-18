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
 * {@link TableSession}（伝票）の金額計算のテスト。
 *
 * <p><b>このテストが守っているもの＝「お客さんに請求する金額」そのもの</b><br>
 * このアプリでいちばんお金に直結するのがここです。
 * 1 円でも多く請求すれば信用を失いますし、少なければ店が損をします。
 * しかも金額の間違いは、その場では誰も気づかず、
 * 数日後にレジ締めが合わないという形で表に出てきます。
 * だからこそ、境目の値まで含めてテストで固めておきます。
 *
 * <p><b>計算の全体像</b>
 * <pre>
 *   小計             = キャンセル以外の注文の合計（税込）
 *   テーブルチャージ  = 単価 × 人数
 *   深夜料金の対象額  = 深夜帯に出した注文の合計
 *                     ＋ 深夜帯に着席していればテーブルチャージ
 *   深夜料金         = 対象額 × 割増率 ÷ 100                     ← 1円未満は切り捨て
 *   ─────────────────────────────────────────
 *   ご請求額         = 小計 + テーブルチャージ + 深夜料金
 *   内消費税         = ご請求額に含まれる消費税（TaxCalculator が計算）
 * </pre>
 *
 * <p><b>深夜料金は「注文時刻」で決まります（会計時刻ではありません）。</b>
 * 22:00 の注文は通常料金、23:30 の注文は深夜料金、と同じ伝票で混在します。
 * 2026-08-18 まで会計時刻で伝票全体を判定していたので直しました。
 * 詳しくは下の「深夜料金は注文時刻で決まる」を参照。
 *
 * <p><b>なぜ Spring を起動しないのか</b><br>
 * {@link TableSession} の金額計算は DB もネットワークも使わない、
 * ただの Java の計算です。こういう部分は素の JUnit だけでテストできます。
 * {@code @SpringBootTest} を付けるとアプリ全体の起動に数秒かかりますが、
 * このクラスは数十ミリ秒で終わります。
 * <b>「重いテストは少なく、軽いテストは多く」</b>が基本方針です。
 *
 * <p><b>金額計算はここ 1 か所にしかない</b><br>
 * 画面やサービスの側で同じ計算を書き直すと、
 * 片方だけ直したときに表示金額と請求金額がずれます。
 * 金額が欲しいときは必ず {@code getTotalAmount()} などの getter を読むこと。
 */
@DisplayName("伝票（TableSession）の金額計算")
class TableSessionTest {

    /** 判定に使う営業日。日付そのものは計算に影響しないので、どの日でもよい。 */
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);

    /** 会計した時刻（23:30）。{@code closedAt} に入るだけで、金額計算には使われません。 */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 16, 23, 30);

    /**
     * 「どの注文も深夜帯だった」とみなす判定役。
     *
     * <p>深夜料金を掛けるかどうかは、伝票が時計を見て決めるのではなく
     * <b>{@link LateNightPolicy} を渡して指示する</b>設計です。
     * 時刻から判定するのは {@code ShopSetting#isLateNight} の仕事で、
     * 伝票側は渡された方針に従うだけ、と役割を分けています。
     * おかげでテストは実時刻に左右されず、
     * 「深夜だけどスタッフの判断で外す」も {@link LateNightPolicy#NONE} で素直に書けます。
     *
     * <p>注文ごとに掛かる／掛からないが分かれるケースは、
     * 下の「深夜料金は注文時刻で決まる」のテストを見てください。
     */
    private static final LateNightPolicy ALWAYS_LATE_NIGHT = at -> true;

    // ========================================================================
    //  テスト用の材料を組み立てるヘルパー
    // ========================================================================

    /** 店舗設定を作る。伝票は<b>開いた時点の設定をコピー</b>して持つ。 */
    private ShopSetting settingOf(int taxRatePercent, int tableChargePerGuest, int lateNightPercent) {
        ShopSetting setting = new ShopSetting();
        setting.setTaxRatePercent(taxRatePercent);
        setting.setTableChargePerGuest(tableChargePerGuest);
        setting.setLateNightSurchargePercent(lateNightPercent);
        return setting;
    }

    /** 実店舗「米粉と鉄板」の条件（税率10% / チャージ¥450 / 深夜10%）。 */
    private ShopSetting realShopSetting() {
        return settingOf(10, 450, 10);
    }

    private DiningTable table() {
        return new DiningTable("3番テーブル", 4, 10);
    }

    /** 伝票を開く。 */
    private TableSession openBill(int guestCount, ShopSetting setting) {
        return new TableSession(table(), BUSINESS_DATE, guestCount, setting);
    }

    private TableSession openBill(int guestCount) {
        return openBill(guestCount, realShopSetting());
    }

    /**
     * 伝票に注文を 1 件足す。
     *
     * <p>本番（{@code OrderService#placeOrder}）と同じ手順を踏んでいます。
     * <ol>
     *   <li>{@code new Order(営業日, 注文番号, 税率)} で作る</li>
     *   <li>{@code setSession()} で伝票に結び付ける</li>
     *   <li>{@code addLine()} → {@code recalculate()} で注文自身の合計を出す</li>
     *   <li>{@code bill.getOrders().add()} で伝票側のリストにも入れる</li>
     * </ol>
     * 4 の「両側そろえる」を忘れると、伝票の合計に新しい注文が数えられません。
     * 双方向の関連はいつでも両側そろえる、が鉄則です。
     */
    private Order addOrder(TableSession bill, int orderNumber, String name, int unitPrice, int quantity) {
        Order order = new Order(BUSINESS_DATE, orderNumber, bill.getTaxRatePercent());
        order.setSession(bill);
        order.addLine(new OrderLine(1L, name, unitPrice, quantity, 12));
        order.recalculate();
        bill.getOrders().add(order);
        return order;
    }

    // ========================================================================
    //  テスト
    // ========================================================================

    @Nested
    @DisplayName("テーブルチャージ")
    class TableCharge {

        @Test
        @DisplayName("注文が0件でも、人数ぶんのテーブルチャージはかかる")
        void chargedEvenWithoutOrders() {
            // 「席についただけ・まだ何も頼んでいない」状態。
            // お通し代（席料）は着席の時点で発生するので、小計 0 でも請求は 0 にならない。
            // ここが 0 円になっていると、会計画面で見落として取りっぱぐれる。
            TableSession bill = openBill(2);

            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getSubtotalAmount()).isZero();
            assertThat(bill.getTableChargeAmount()).isEqualTo(900);   // 450 × 2名
            assertThat(bill.getTotalAmount()).isEqualTo(900);
        }

        @ParameterizedTest(name = "{0}名 → テーブルチャージ {1}円")
        @CsvSource({
                "1,  450",
                "2,  900",
                "4, 1800",
                "8, 3600"
        })
        @DisplayName("テーブルチャージ = 単価 × 人数")
        void chargeIsUnitPriceTimesGuests(int guestCount, int expected) {
            TableSession bill = openBill(guestCount);

            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getTableChargeAmount()).isEqualTo(expected);
        }

        @Test
        @DisplayName("人数に0以下を指定しても1名として扱う（チャージが消えない）")
        void guestCountIsAtLeastOne() {
            // 人数はお客さんの自己申告なので、0 や負の数が飛んでくる可能性がある。
            // そのまま受けるとチャージが 0 円になり、無料で席を使えてしまう。
            TableSession bill = openBill(0);

            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getGuestCount()).isEqualTo(1);
            assertThat(bill.getTableChargeAmount()).isEqualTo(450);
        }

        @Test
        @DisplayName("人数を変えるとテーブルチャージも合計も変わる")
        void changingGuestCountUpdatesCharge() {
            // 「2名で入店 → あとから2名合流」は日常的に起きる。
            // ホール画面から人数を直したとき、金額が追従しないと請求漏れになる。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);
            bill.recalculate(LateNightPolicy.NONE);
            assertThat(bill.getTotalAmount()).isEqualTo(1180 + 900);

            bill.setGuestCount(4);
            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getTableChargeAmount()).isEqualTo(1800);
            assertThat(bill.getTotalAmount()).isEqualTo(1180 + 1800);
        }
    }

    @Nested
    @DisplayName("深夜料金")
    class LateNight {

        @Test
        @DisplayName("深夜料金 = (小計 + テーブルチャージ) × 率 ÷ 100（1円未満は切り捨て）")
        void surchargeIsPercentOfSubtotalPlusCharge() {
            // 1名・チャージ450円・注文1,635円 → 基準額 2,085円
            //   2,085 × 10% = 208.5 → 208円（切り捨て）
            // 切り上げにすると 209 円になり、1円だけ多く請求してしまう。
            // 端数はお客さんに有利な方向（切り捨て）に丸める、というのが店の方針。
            TableSession bill = openBill(1);
            addOrder(bill, 101, "国産豚ロースステーキ", 1635, 1);

            bill.recalculate(ALWAYS_LATE_NIGHT);

            assertThat(bill.getSubtotalAmount()).isEqualTo(1635);
            assertThat(bill.getTableChargeAmount()).isEqualTo(450);
            assertThat(bill.getLateNightAmount()).isEqualTo(208);          // 208.5 の切り捨て
            assertThat(bill.isLateNightApplied()).isTrue();
            assertThat(bill.getTotalAmount()).isEqualTo(1635 + 450 + 208); // 2,293円
        }

        @Test
        @DisplayName("深夜料金はテーブルチャージにもかかる（小計だけが対象ではない）")
        void surchargeAppliesToTableChargeToo() {
            // 「小計だけに掛ける」実装にすると金額が変わってしまうので、
            // 基準額に何を含めるかをテストで明文化しておく。
            TableSession bill = openBill(2);   // チャージ 900円
            addOrder(bill, 101, "自家製レモンサワー", 850, 2);  // 小計 1,700円

            bill.recalculate(ALWAYS_LATE_NIGHT);

            // 基準額 = 1,700 + 900 = 2,600 → 10% = 260円
            assertThat(bill.getLateNightAmount()).isEqualTo(260);
            assertThat(bill.getTotalAmount()).isEqualTo(2600 + 260);
        }

        @Test
        @DisplayName("applyLateNight が false なら深夜料金は 0 になる")
        void notAppliedWhenFlagIsFalse() {
            // 23時を1分過ぎただけ、常連さんへの配慮、など
            // スタッフの判断で外せることが現場では必要になる。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);

            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
            assertThat(bill.getTotalAmount()).isEqualTo(1180 + 900);
        }

        @Test
        @DisplayName("割増率 0% の店では、深夜扱いにしても料金は増えない")
        void zeroPercentMeansNoSurcharge() {
            // 深夜料金をとらない運用に切り替えたときに、
            // うっかり 0 円ではなく 0% で計算してしまう事故を防ぐ。
            TableSession bill = openBill(2, settingOf(10, 450, 0));
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);

            bill.recalculate(ALWAYS_LATE_NIGHT);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
        }
    }

    @Nested
    @DisplayName("キャンセルの扱い")
    class Canceled {

        @Test
        @DisplayName("キャンセルされた注文は小計に含まれない")
        void canceledOrderIsNotBilled() {
            // ★このクラスでいちばん大事なテスト★
            // 「品切れで作れなかった」「お客さんが取り消した」注文まで請求してしまうと、
            // 出していない品のお金をもらうことになる。クレームでは済まない事故。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 2);              // 2,360円（生きている）
            Order canceled = addOrder(bill, 102, "牡蠣と豚肉米粉そば", 1680, 1);  // 1,680円

            canceled.cancel("材料切れ", "店長");
            bill.recalculate(LateNightPolicy.NONE);

            assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(bill.getSubtotalAmount()).isEqualTo(2360);              // 1,680円は入らない
            assertThat(bill.getTotalAmount()).isEqualTo(2360 + 900);
        }

        @Test
        @DisplayName("キャンセルした注文は伝票の明細にも点数にも出てこない")
        void canceledOrderIsHiddenFromBillDetails() {
            // 金額だけ合っていても、明細に「キャンセル済み」が並んでいると
            // レジで数え直したときに合わなくなる。表示側もそろえておく。
            TableSession bill = openBill(2);
            Order alive = addOrder(bill, 101, "たこ焼 8個", 1190, 1);
            Order canceled = addOrder(bill, 102, "冷やしトマト", 600, 3);

            canceled.cancel("お客様都合", "customer");
            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getBillableOrders()).containsExactly(alive);
            assertThat(bill.getTotalQuantity()).isEqualTo(1);   // 3点は数えない
        }

        @Test
        @DisplayName("全部キャンセルされても、テーブルチャージだけは残る")
        void tableChargeSurvivesFullCancellation() {
            TableSession bill = openBill(3);
            Order order = addOrder(bill, 101, "肉玉米粉そば", 1180, 1);

            order.cancel("材料切れ", "店長");
            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getSubtotalAmount()).isZero();
            assertThat(bill.getTotalAmount()).isEqualTo(1350);   // 450 × 3名
        }
    }

    @Nested
    @DisplayName("消費税")
    class Tax {

        @Test
        @DisplayName("内消費税は TaxCalculator の計算と一致する")
        void taxMatchesTaxCalculator() {
            // 税額の計算式を伝票側で書き直すと、税率が変わったときに直し漏れる。
            // 「TaxCalculator に任せている」ことをテストで固定しておく。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);

            bill.recalculate(ALWAYS_LATE_NIGHT);

            int expectedTax = TaxCalculator.includedTax(bill.getTotalAmount(), 10);
            assertThat(bill.getTaxAmount()).isEqualTo(expectedTax);
            // 税抜相当額（レシートの内訳表示に使う）
            assertThat(bill.getNetAmount()).isEqualTo(bill.getTotalAmount() - expectedTax);
        }

        @Test
        @DisplayName("消費税はご請求額の全体（チャージ・深夜料金込み）にかかる")
        void taxIsOnTheWholeAmount() {
            // 小計だけを対象に税を出すと、レシートの内訳が合わなくなる。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "自家製レモンサワー", 850, 2);

            bill.recalculate(ALWAYS_LATE_NIGHT);

            // 1,700 + 900 = 2,600 → 深夜料金 260 → ご請求額 2,860円
            assertThat(bill.getTotalAmount()).isEqualTo(2860);
            // 2,860 × 10 ÷ 110 = 260円
            assertThat(bill.getTaxAmount()).isEqualTo(260);
        }
    }

    @Nested
    @DisplayName("設定のスナップショット")
    class Snapshot {

        @Test
        @DisplayName("伝票を開いたあとに店舗設定を変えても、その伝票の金額条件は変わらない")
        void settingsAreCopiedWhenOpened() {
            // 営業中に店長がテーブルチャージを変更することはあり得る。
            // そのとき「いま座っているお客さんの請求額が突然変わる」のは許されない。
            // だから伝票は開いた時点の条件をコピーして持っている。
            ShopSetting setting = realShopSetting();
            TableSession bill = openBill(2, setting);

            setting.setTableChargePerGuest(1000);
            setting.setTaxRatePercent(8);
            setting.setLateNightSurchargePercent(30);
            bill.recalculate(ALWAYS_LATE_NIGHT);

            assertThat(bill.getTableChargePerGuest()).isEqualTo(450);
            assertThat(bill.getTaxRatePercent()).isEqualTo(10);
            assertThat(bill.getLateNightSurchargePercent()).isEqualTo(10);
            assertThat(bill.getTableChargeAmount()).isEqualTo(900);       // 1,000円ではない
        }
    }

    @Nested
    @DisplayName("会計（伝票を締める・開け直す）")
    class CloseAndReopen {

        @Test
        @DisplayName("close すると CLOSED になり、会計時刻と担当者が記録される")
        void closeRecordsWhoAndWhen() {
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);

            bill.close(AT, LateNightPolicy.NONE, "店長", "常連さん");

            assertThat(bill.getStatus()).isEqualTo(SessionStatus.CLOSED);
            assertThat(bill.isOpen()).isFalse();
            assertThat(bill.getClosedAt()).isEqualTo(AT);
            assertThat(bill.getClosedBy()).isEqualTo("店長");
            assertThat(bill.getNote()).isEqualTo("常連さん");
        }

        @Test
        @DisplayName("close の中で金額が計算し直される（締めた瞬間の金額が正となる）")
        void closeRecalculatesAmounts() {
            // 「最後の1杯」を入れてすぐ会計、という流れは普通に起きる。
            // close が recalculate を呼んでいないと、その1杯が請求から抜け落ちる。
            TableSession bill = openBill(2);
            addOrder(bill, 101, "肉玉米粉そば", 1180, 1);
            bill.recalculate(LateNightPolicy.NONE);

            addOrder(bill, 102, "自家製レモンサワー", 850, 1);   // recalculate はあえて呼ばない
            bill.close(AT, LateNightPolicy.NONE, "店長", null);

            assertThat(bill.getSubtotalAmount()).isEqualTo(2030);
            assertThat(bill.getTotalAmount()).isEqualTo(2030 + 900);
        }

        @Test
        @DisplayName("会計済みの伝票は追加注文を受け付けない")
        void closedBillIsNotOrderable() {
            // この判定を見て OrderService が追加注文を弾いている。
            TableSession bill = openBill(2);
            assertThat(bill.isOrderable()).isTrue();

            bill.close(AT, LateNightPolicy.NONE, "店長", null);

            assertThat(bill.isOrderable()).isFalse();
        }

        @Test
        @DisplayName("reopen すると OPEN に戻り、会計の記録が消える")
        void reopenClearsClosingRecord() {
            // 誤って別の卓を会計してしまったときのリカバリ手段。
            // 記録が残ったままだと「会計済みなのに OPEN」というちぐはぐな状態になる。
            TableSession bill = openBill(2);
            bill.close(AT, LateNightPolicy.NONE, "店長", null);

            bill.reopen();

            assertThat(bill.getStatus()).isEqualTo(SessionStatus.OPEN);
            assertThat(bill.isOpen()).isTrue();
            assertThat(bill.isOrderable()).isTrue();
            assertThat(bill.getClosedAt()).isNull();
            assertThat(bill.getClosedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("提供状況の把握")
    class PendingOrders {

        @Test
        @DisplayName("まだ厨房で作っている注文があるかどうかが分かる（会計前の確認用）")
        void hasPendingOrders() {
            // 「焼き上がっていない品があるのに会計してしまった」を防ぐための判定。
            TableSession bill = openBill(2);
            Order order = addOrder(bill, 101, "肉玉米粉そば", 1180, 1);
            assertThat(bill.hasPendingOrders()).isTrue();       // RECEIVED は作業中

            order.changeStatus(OrderStatus.READY, "厨房スタッフ");
            assertThat(bill.hasPendingOrders()).isFalse();      // 焼き上がり＝作業は終わり

            order.changeStatus(OrderStatus.COMPLETED, "ホール");
            assertThat(bill.hasPendingOrders()).isFalse();
        }
    }

    @Nested
    @DisplayName("深夜料金は注文時刻で決まる")
    class LateNightByOrderTime {

        /** 23:00〜5:00 を深夜とみなす、実店舗と同じ判定役。 */
        private static final LateNightPolicy AFTER_23 =
                at -> at.getHour() >= 23 || at.getHour() < 5;

        /** 指定した時刻に出された注文を足す（営業日の当日）。 */
        private Order addOrderAt(TableSession bill, int orderNumber, String name,
                                 int unitPrice, int quantity, int hour, int minute) {
            return addOrderAt(bill, orderNumber, name, unitPrice, quantity, 0, hour, minute);
        }

        /**
         * 指定した時刻に出された注文を足す。
         *
         * @param dayOffset 営業日から何日ずらすか。深夜営業で日付をまたいだ注文は 1
         */
        private Order addOrderAt(TableSession bill, int orderNumber, String name,
                                 int unitPrice, int quantity, int dayOffset, int hour, int minute) {
            Order order = addOrder(bill, orderNumber, name, unitPrice, quantity);
            order.setCreatedAtForTest(
                    LocalDateTime.of(BUSINESS_DATE.plusDays(dayOffset), LocalTime.of(hour, minute)));
            return order;
        }

        /** 指定した時刻に着席した伝票にする。 */
        private void seatAt(TableSession bill, int hour, int minute) {
            bill.setOpenedAtForTest(LocalDateTime.of(BUSINESS_DATE, LocalTime.of(hour, minute)));
        }

        @Test
        @DisplayName("深夜に出した注文だけが割増の対象になる（通常料金の注文とは混在する）")
        void onlyLateNightOrdersAreSurcharged() {
            // 実店舗に確認した運用そのもの。
            // 2026-08-18 まで「会計時刻で伝票全体」を判定していたので直した。
            //
            //   20:00 着席 4名 → チャージ ¥450 × 4 = ¥1,800（着席が 20:00 なので対象外）
            //   22:00 注文 ¥5,000 → 通常料金
            //   23:30 注文 ¥3,000 → 深夜料金の対象
            TableSession bill = openBill(4);
            seatAt(bill, 20, 0);
            addOrderAt(bill, 101, "肉玉米粉そば", 5000, 1, 22, 0);
            addOrderAt(bill, 102, "追加のハイボール", 3000, 1, 23, 30);

            bill.recalculate(AFTER_23);

            assertThat(bill.getSubtotalAmount()).isEqualTo(8000);
            assertThat(bill.getTableChargeAmount()).isEqualTo(1800);
            // 対象は 23:30 の ¥3,000 だけ
            assertThat(bill.getLateNightAmount()).isEqualTo(300);
            assertThat(bill.getTotalAmount()).isEqualTo(10_100);
            assertThat(bill.isLateNightApplied()).isTrue();
        }

        @Test
        @DisplayName("深夜の注文が 1 件も無ければ、いくら長居しても割増ゼロ")
        void noLateNightOrderMeansNoSurcharge() {
            // 会計時刻で判定していた頃は、22 時に注文を終えて 23 時過ぎまで
            // 話し込んだだけで全額に割増がかかっていた。それを直したことの確認。
            TableSession bill = openBill(2);
            seatAt(bill, 19, 0);
            addOrderAt(bill, 101, "お好み焼き", 1200, 2, 21, 0);

            bill.recalculate(AFTER_23);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
            assertThat(bill.getTotalAmount()).isEqualTo(2400 + 900);
        }

        @Test
        @DisplayName("テーブルチャージは着席時刻で決まる（深夜に案内した卓だけ割増）")
        void tableChargeFollowsSeatingTime() {
            // 23:30 に案内した 2 名。チャージ ¥450 × 2 = ¥900 も対象になる。
            TableSession bill = openBill(2);
            seatAt(bill, 23, 30);
            addOrderAt(bill, 101, "締めの焼きそば", 1100, 1, 23, 45);

            bill.recalculate(AFTER_23);

            // 対象 = 注文 ¥1,100 + チャージ ¥900 = ¥2,000 → 10% で ¥200
            assertThat(bill.getLateNightAmount()).isEqualTo(200);
            assertThat(bill.getTotalAmount()).isEqualTo(1100 + 900 + 200);
        }

        @Test
        @DisplayName("日をまたいだ注文も深夜のまま（翌 0:30 の追加注文）")
        void afterMidnightIsStillLateNight() {
            // dayOffset=1 を渡して、本当に「翌日の 0:30」にしている点が要。
            // 同じ日付の 0:30 にしてしまうと 22:30 より前になり、
            // 時系列が逆転した伝票をテストしていることになる（判定は時刻しか見ないので
            // アサーションは通ってしまい、間違いに気づけない）。
            TableSession bill = openBill(2);
            seatAt(bill, 22, 0);
            addOrderAt(bill, 101, "生ビール", 600, 2, 0, 22, 30);
            addOrderAt(bill, 102, "ハイボール", 500, 2, 1, 0, 30);

            assertThat(bill.getOrders().get(1).getCreatedAt())
                    .isAfter(bill.getOrders().get(0).getCreatedAt());

            bill.recalculate(AFTER_23);

            // 対象は 0:30 の ¥1,000 だけ（22:30 の ¥1,200 と 22:00 着席のチャージは対象外）
            assertThat(bill.getLateNightAmount()).isEqualTo(100);
        }

        @Test
        @DisplayName("割増が 1 円未満に切り捨てられたら「深夜料金あり」にはしない")
        void zeroYenSurchargeIsNotMarkedApplied() {
            // 対象額 ¥9 × 10% = 0.9 → 切り捨てで ¥0。
            // ここで「適用あり」にしてしまうと、伝票に「深夜料金（10%） ¥0」の行が出る。
            // チャージ 0 円の設定にして、対象額を注文ぶんだけにする
            TableSession bill = openBill(1, settingOf(10, 0, 10));
            seatAt(bill, 20, 0);
            addOrderAt(bill, 101, "駄菓子", 9, 1, 23, 30);

            bill.recalculate(AFTER_23);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
        }

        @Test
        @DisplayName("スタッフが免除した伝票は、会計を取り消して開け直しても免除のまま")
        void waiverSurvivesReopen() {
            // ここが崩れると実害が出る。
            // 常連さんに「深夜料金は結構です」と伝えて締めたあと、
            // 人数の入力ミスに気づいて開け直しただけで割増が復活し、
            // 伝えた金額より高い額を請求してしまう。
            TableSession bill = openBill(2);
            seatAt(bill, 23, 0);
            addOrderAt(bill, 101, "締めの一品", 1500, 1, 23, 30);

            bill.setLateNightWaived(true);
            bill.close(AT, AFTER_23, "店長", "常連さんのためサービス");
            assertThat(bill.getLateNightAmount()).isZero();
            int closedTotal = bill.getTotalAmount();

            // 会計を取り消して、ふつうに再計算する（TableService#reopenSession がやること）
            bill.reopen();
            bill.recalculate(AFTER_23);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.getTotalAmount()).isEqualTo(closedTotal);
        }

        @Test
        @DisplayName("打ち直し救済：対象外にした注文には、深夜帯でも割増がかからない")
        void exemptOrderIsNotSurcharged() {
            // 22:55 に受けた注文を 23:05 に誤って取り消し、23:06 に入れ直した状況。
            // 入れ直した注文は時刻が新しいので、そのままだと 10% が乗る。
            // お客さまから見れば同じ品を同じ時間に頼んだだけなので説明がつかない。
            TableSession bill = openBill(2);
            seatAt(bill, 20, 0);
            Order reentered = addOrderAt(bill, 102, "打ち直した注文", 2000, 1, 23, 6);
            addOrderAt(bill, 103, "本当に深夜の注文", 1000, 1, 23, 30);

            bill.recalculate(AFTER_23);
            assertThat(bill.getLateNightAmount()).isEqualTo(300);   // 3,000 の 10%

            reentered.setLateNightExempt(true);
            bill.recalculate(AFTER_23);

            // 対象は本当に深夜の ¥1,000 だけになる
            assertThat(bill.getLateNightAmount()).isEqualTo(100);
            // 小計は変わらない（請求から消えるわけではない）
            assertThat(bill.getSubtotalAmount()).isEqualTo(3000);
        }

        @Test
        @DisplayName("対象外を解除すれば、また深夜料金がかかる（何度でも切り替えられる）")
        void exemptionCanBeToggledBack() {
            // 押し間違えても戻せること。片道だと現場で困る。
            TableSession bill = openBill(2);
            seatAt(bill, 20, 0);
            Order order = addOrderAt(bill, 101, "締めの一品", 1000, 1, 23, 30);

            order.setLateNightExempt(true);
            bill.recalculate(AFTER_23);
            assertThat(bill.getLateNightAmount()).isZero();

            order.setLateNightExempt(false);
            bill.recalculate(AFTER_23);

            assertThat(bill.getLateNightAmount()).isEqualTo(100);
        }

        @Test
        @DisplayName("対象外にしても、深夜帯でない注文の扱いは変わらない")
        void exemptionDoesNotAffectNormalOrders() {
            // 「対象外にする」は割増を外すだけ。もともと対象でない注文には何の影響もない。
            TableSession bill = openBill(2);
            seatAt(bill, 20, 0);
            Order daytime = addOrderAt(bill, 101, "夕方の注文", 5000, 1, 20, 30);

            daytime.setLateNightExempt(true);
            bill.recalculate(AFTER_23);

            assertThat(bill.getSubtotalAmount()).isEqualTo(5000);
            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.getTotalAmount()).isEqualTo(5000 + 900);
        }

        @Test
        @DisplayName("免除を解除すれば、通常どおり深夜料金がかかる")
        void waiverCanBeCleared() {
            // スタッフが会計画面でチェックを入れ直したときの経路。
            TableSession bill = openBill(2);
            seatAt(bill, 23, 0);
            addOrderAt(bill, 101, "締めの一品", 1500, 1, 23, 30);

            bill.setLateNightWaived(true);
            bill.recalculate(AFTER_23);
            assertThat(bill.getLateNightAmount()).isZero();

            bill.setLateNightWaived(false);
            bill.recalculate(AFTER_23);

            // 対象 = 注文 ¥1,500 + チャージ ¥900（23:00 着席）= ¥2,400 → ¥240
            assertThat(bill.getLateNightAmount()).isEqualTo(240);
        }

        @Test
        @DisplayName("キャンセルした深夜の注文には割増がかからない")
        void canceledLateNightOrderIsNotSurcharged() {
            // 請求から消える注文に割増だけ残ると、伝票の内訳が合わなくなる。
            TableSession bill = openBill(2);
            seatAt(bill, 20, 0);
            Order canceled = addOrderAt(bill, 101, "作り間違い", 2000, 1, 23, 30);
            canceled.changeStatus(OrderStatus.CANCELED, "ホール");

            bill.recalculate(AFTER_23);

            assertThat(bill.getSubtotalAmount()).isZero();
            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
        }

        @Test
        @DisplayName("スタッフが免除すると、深夜の注文があっても割増ゼロ")
        void staffCanWaive() {
            TableSession bill = openBill(2);
            seatAt(bill, 23, 0);
            addOrderAt(bill, 101, "締めの一品", 1500, 1, 23, 30);

            bill.recalculate(LateNightPolicy.NONE);

            assertThat(bill.getLateNightAmount()).isZero();
            assertThat(bill.isLateNightApplied()).isFalse();
        }

        @Test
        @DisplayName("同じ品を 1 回で頼んでも 2 回に分けても、深夜料金は変わらない")
        void splittingOrdersDoesNotChangeTheSurcharge() {
            // 対象額を合計してから 1 回だけ割増を計算している理由。
            // 注文ごとに割増して足すと、切り捨てが注文の件数だけ効いて
            // 「分けて頼んだほうが安い」という抜け道ができてしまう。
            TableSession oneOrder = openBill(2);
            seatAt(oneOrder, 20, 0);
            addOrderAt(oneOrder, 101, "まとめて", 105, 2, 23, 30);   // ¥210

            TableSession twoOrders = openBill(2);
            seatAt(twoOrders, 20, 0);
            addOrderAt(twoOrders, 101, "1 回目", 105, 1, 23, 30);    // ¥105
            addOrderAt(twoOrders, 102, "2 回目", 105, 1, 23, 40);    // ¥105

            oneOrder.recalculate(AFTER_23);
            twoOrders.recalculate(AFTER_23);

            // ¥210 × 10% = ¥21。注文ごとに切り捨てると 10 + 10 = ¥20 になってしまう
            assertThat(oneOrder.getLateNightAmount()).isEqualTo(21);
            assertThat(twoOrders.getLateNightAmount()).isEqualTo(21);
        }

        @Test
        @DisplayName("会計画面の「付けた場合／付けなかった場合」も注文時刻の計算に従う")
        void previewAmountsFollowTheSameRule() {
            // ホール画面はこの 2 つを並べてスタッフに見せる。
            // ここが伝票本体と別の式で計算されていると、表示と請求がずれる。
            TableSession bill = openBill(4);
            seatAt(bill, 20, 0);
            addOrderAt(bill, 101, "通常料金の注文", 5000, 1, 22, 0);
            addOrderAt(bill, 102, "深夜の注文", 3000, 1, 23, 30);

            bill.recalculate(AFTER_23);

            assertThat(bill.getTotalWithoutLateNight()).isEqualTo(9800);
            assertThat(bill.getTotalWithLateNight()).isEqualTo(10_100);
            assertThat(bill.getTotalWithLateNight()).isEqualTo(bill.getTotalAmount());
        }
    }
}
