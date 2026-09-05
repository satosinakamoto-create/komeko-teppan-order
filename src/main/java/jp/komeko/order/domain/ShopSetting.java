package jp.komeko.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 店舗設定。テーブルには常に 1 行だけ（id = 1）存在します。
 *
 * <p>「設定を DB に置く」と、店長が管理画面から営業中に変更できます。
 * application.yml に書いてしまうと再起動が必要になるため、
 * 現場で触りたいものは DB、環境ごとに違うもの（DB 接続先など）は yml、
 * という切り分けにしています。
 */
@Entity
@Table(name = "shop_setting")
public class ShopSetting {

    /** 単一行なので固定値 1。 */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @NotBlank(message = "店舗名を入力してください")
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String shopName = "米粉と鉄板";

    @Size(max = 60)
    @Column(length = 60)
    private String tagline = "グルテンフリーの焼きたてを、鉄板から。";

    /**
     * 注文受付中フラグ。
     * 混雑時に店長がワンタップで注文を止められるようにするための「非常ブレーキ」。
     */
    @Column(nullable = false)
    private boolean acceptingOrders = true;

    /** 受付停止中にお客さんの画面に出すメッセージ。 */
    @Size(max = 100)
    @Column(length = 100)
    private String closedMessage = "ただいま大変混み合っております。しばらくお待ちください。";

    /**
     * 時刻で受付を止めない（24 時間いつでも受け付ける）モード。
     *
     * <p>ON にすると開店時刻・ラストオーダーによる自動停止をしません。
     * 次のような場面のための逃げ道です。
     * <ul>
     *   <li><b>開発・動作確認</b>… 営業時間外に触ると人数選択すら押せず、
     *       確認のたびに営業時間をいじる羽目になる。ON にすれば常に通せる</li>
     *   <li><b>急な営業時間の変更</b>… 「今日は貸切で朝までやる」のような
     *       その場の判断に、時刻を計算し直さずワンタップで対応できる</li>
     *   <li><b>24 時間営業の店</b>… そもそも開店・閉店という区切りが無い</li>
     * </ul>
     *
     * <p><b>受付停止フラグ {@link #acceptingOrders} との違い</b><br>
     * あちらは「今すぐ止める」非常ブレーキ、こちらは「時計で止めるのをやめる」設定です。
     * 両方が独立して効くので、24 時間受付にしても非常ブレーキはいつでも踏めます
     * （＝ このモードにすると止められなくなる、ということはありません）。
     *
     * <p><b>{@code columnDefinition} に default を書いてある理由（実際に踏んだ話）</b><br>
     * すでに行が入っているテーブルに「NOT NULL の列」をあとから足すことはできません。
     * 既存の行に何を入れればいいか DB が決められないからです。
     * {@code ddl-auto: update} は黙って
     * {@code ALTER TABLE ... ADD COLUMN always_open BOOLEAN NOT NULL} を投げるので、
     * 実データの入った DB では起動時にこけます
     * （2026-08-18 に実際にこれで起動できなくなりました）。
     * <b>しかもテストでは絶対に再現しません。</b>テストは毎回まっさらな DB を作るので、
     * 「無いテーブルを作る」経路しか通らず、「既存テーブルに足す」経路を踏まないからです。
     * {@code default} を書いておくと、既存の行にはその値が入って ALTER が通ります。
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean alwaysOpen = false;

    @Column(nullable = false)
    private LocalTime openTime = LocalTime.of(11, 0);

    @Column(nullable = false)
    private LocalTime closeTime = LocalTime.of(19, 0);

    /** ラストオーダー時刻。これを過ぎると自動で受付停止になる。 */
    @Column(nullable = false)
    private LocalTime lastOrderTime = LocalTime.of(18, 30);

    /** 消費税率（%）。テイクアウトは軽減税率の 8。 */
    @Min(0)
    @Max(100)
    @Column(nullable = false)
    private int taxRatePercent = 8;

    /** その日の 1 件目に振る注文番号。101 始まりにすると 3 桁で見やすい。 */
    @Min(1)
    @Column(nullable = false)
    private int orderNumberStart = 101;

    /**
     * 営業日の切り替わり時刻（時）。
     * 5 なら、深夜 0〜5 時の注文は前日の営業日として集計される。
     */
    @Min(0)
    @Max(23)
    @Column(nullable = false)
    private int businessDayCutoverHour = 5;

    /**
     * 鉄板で同時に焼ける品数。待ち時間の目安計算に使う。
     * 鉄板が広い／複数台あるなら大きくする。
     */
    @Min(1)
    @Column(nullable = false)
    private int griddleCapacity = 4;

    /**
     * テーブルチャージ（お通し代・席料）。お一人様あたりの金額（税込・円）。
     * 0 にすると請求しません。
     */
    @Min(0)
    @Column(nullable = false)
    private int tableChargePerGuest = 450;

    /**
     * 月額の家賃（税込・円）。売上画面の「売上の配分」で賃貸の実績として使います。
     *
     * <p><b>0 のときは「記録していない」として扱います。</b>
     * 0 円と表示すると「家賃がかかっていない」という嘘になるためで、
     * 金額を入れて初めて配分の実績に載ります。
     *
     * <p>家賃は仕入れではなく毎月同じ額が出ていく固定費なので、
     * {@code PurchaseCategory} には入れず設定として 1 つ持ちます。
     * 仕入れに混ぜると、帳簿では雑費として扱われてしまいます。
     */
    /*
     * ★ columnDefinition に default を書くこと（84 行目の alwaysOpen と同じ理由）。
     *
     *   これを書かずに入れたところ、すでに店舗設定の行がある dev の DB が
     *   起動しなくなりました（2026-09-05）。
     *     NULL not allowed for column "MONTHLY_RENT"
     *     Column "SS1_0.MONTHLY_RENT" not found
     *   ddl-auto: update が ALTER TABLE ... ADD COLUMN monthly_rent INTEGER NOT NULL を
     *   投げ、既存の 1 行に何を入れるか決められずに失敗し、
     *   列が作られないまま SELECT に進んで 2 つ目のエラーになります。
     *
     *   テストでは絶対に再現しません。毎回まっさらな DB を作るので
     *   「無いテーブルを作る」経路しか通らないからです。
     *   本番（Flyway の V6）は DEFAULT 0 付きで足しているので影響はありません。
     */
    @Min(0)
    @Column(name = "monthly_rent", nullable = false,
            columnDefinition = "integer not null default 0")
    private int monthlyRent = 0;

    /**
     * 消費税を納める立場か。
     *
     * <p>税理士の画面が出す「控除できる税額」は課税事業者にしか意味がありません。
     * 詳しくは {@link TaxStatus} を読んでください。
     *
     * <p>既定を {@code UNSET} にしているのは、未設定の店に
     * 「免税事業者です」と表示させないためです（納税に関わる嘘になる）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_status", nullable = false, length = 20,
            columnDefinition = "varchar(20) default 'UNSET' not null")
    private TaxStatus taxStatus = TaxStatus.UNSET;

    /**
     * 自分の店のインボイス登録番号（{@code T} + 13 桁）。持っていなければ null。
     *
     * <p><b>仕入先の番号（{@code purchase.reg_number}）とは向きが逆です。</b>
     * あちらは「受け取ったレシートに番号があるか」＝こちらが控除できるか。
     * こちらは「こちらが出す領収書に番号を書けるか」＝相手が控除できるか。
     *
     * <p>桁数を 14 にそろえてあるのは、突き合わせるときに型が違うと困るためです。
     * 検算と整形は {@code RegistrationNumber} に置いてあります（実装は 1 か所）。
     */
    @Column(name = "invoice_registration_number", length = 14)
    private String invoiceRegistrationNumber;

    /** 深夜料金がかかり始める時刻。 */
    @Column(nullable = false)
    private LocalTime lateNightStartTime = LocalTime.of(23, 0);

    /**
     * 深夜料金が終わる時刻（この時刻ちょうどは、もう深夜ではない）。
     *
     * <p>「23:00 〜 翌 5:00」のように<b>日付をまたぐ指定</b>ができます。
     *
     * <p><b>なぜ「終わり」を持たせたのか</b><br>
     * 以前は終わりを持たず、代わりに営業日の切り替え時刻
     * （{@link #businessDayCutoverHour}）を深夜の終わりとして流用していました。
     * しかしこの 2 つは本来まったく別のもので、
     * 「売上をどの日に数えるか」を変えたいだけなのに深夜料金の範囲まで動いてしまう、
     * という分かりにくい連動が起きていました。
     * 深夜料金は深夜料金として、開始と終了を自分で持つようにしています。
     *
     * <p>{@code default} の意味は {@link #alwaysOpen} のコメントを参照してください
     * （既存の DB に列を足せるようにするため）。
     * 既存店のデータには、これまでの挙動と同じ 5:00 が入ります。
     */
    @Column(nullable = false, columnDefinition = "time not null default '05:00:00'")
    private LocalTime lateNightEndTime = LocalTime.of(5, 0);

    /**
     * 深夜料金の割増率（%）。
     *
     * <p>{@link #lateNightStartTime}〜{@link #lateNightEndTime} に
     * <b>出された注文</b>の合計に対して、この割合を加算します
     * （テーブルチャージは、その時間帯に着席した卓のぶんだけ対象）。
     * 会計時刻ではなく<b>注文時刻</b>で決まる点に注意してください。
     * 計算の本体は {@code TableSession#recalculate} にあります。
     *
     * <p>0 にすると請求しません。
     */
    @Min(0)
    @Max(100)
    @Column(nullable = false)
    private int lateNightSurchargePercent = 10;

    /** お客さんの注文完了画面に出す案内文。 */
    @Size(max = 200)
    @Column(length = 200)
    private String pickupNotice = "番号をお呼びしましたらカウンターまでお越しください。お会計はお受け取り時に店頭でお願いします。";

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public ShopSetting() {
    }

    // ── ビジネスロジック ──────────────────────────────────────────

    /**
     * いま注文を受け付けられるか。
     * 手動の受付停止フラグと、営業時間・ラストオーダーの両方を見る。
     *
     * @param now 判定したい日時（テストしやすいよう引数で受け取る）
     */
    public boolean isOrderAcceptable(LocalDateTime now) {
        if (!acceptingOrders) {
            return false;
        }
        return isWithinOrderableHours(now.toLocalTime());
    }

    /**
     * ラストオーダーまでの時間帯に入っているか。
     * 24 時間受付モードならいつでも true。
     */
    private boolean isWithinOrderableHours(LocalTime t) {
        if (alwaysOpen) {
            return true;
        }
        // ラストオーダー「ちょうど」はまだ注文できる（だから終端を含む判定）
        return isBetween(t, openTime, lastOrderTime);
    }

    // ── 時刻の範囲判定（日をまたぐ営業のかなめ） ────────────────────

    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    /**
     * {@code from} から {@code t} まで、時計回りに何秒進んだか。
     *
     * <p><b>この 1 行がこのクラスの心臓部です。</b><br>
     * 時刻を「0 時から何秒」という直線ではなく、
     * <b>24 時間でひと回りする円</b>として扱うための計算です。
     *
     * <pre>
     *                 0時/24時
     *                    ↑
     *          18時 ←    ●    → 6時        時計回りに進む
     *                    ↓
     *                   12時
     * </pre>
     *
     * <p>{@code Math.floorMod} は「余り」を必ず 0 以上にしてくれる割り算です。
     * Java の {@code %} 演算子は負の数に対して負の余りを返してしまう
     * （{@code -3 % 24 == -3}）ので、円をぐるっと回す計算には
     * {@code floorMod}（{@code -3} → {@code 21}）を使います。
     *
     * <p>たとえば from=17:30、t=1:00 なら、17:30 から時計回りに 7 時間半で 1:00。
     * 日付をまたいでいるかどうかを場合分けしなくても、答えは自然に 7.5 時間になります。
     */
    private static int secondsFrom(LocalTime from, LocalTime t) {
        return Math.floorMod(t.toSecondOfDay() - from.toSecondOfDay(), SECONDS_PER_DAY);
    }

    /**
     * {@code t} が {@code from} 〜 {@code toInclusive} の範囲にあるか（終端を含む）。
     *
     * <p><b>日をまたぐ範囲もそのまま書けます。</b>
     * 「17:30 〜 翌 1:30」でも「11:00 〜 18:30」でも、呼び方は同じです。
     * 場合分けが要らないのは、上の {@link #secondsFrom} が
     * 「起点からどれだけ進んだか」に揃えてくれるからです。
     * <b>進んだ距離が範囲の長さ以内なら中にいる</b>、それだけの話になります。
     *
     * <pre>
     *   from ●━━━━━━━━━━━● toInclusive
     *          ↑ t         範囲の長さ = secondsFrom(from, to)
     *          進んだ距離  = secondsFrom(from, t)   これが範囲の長さ以下なら中
     * </pre>
     *
     * <p>{@code from} と {@code to} が同じ時刻のときは、範囲の長さが 0 なので
     * <b>その一瞬だけ</b>が対象になります（＝ ほぼ入力ミス）。
     */
    private static boolean isBetween(LocalTime t, LocalTime from, LocalTime toInclusive) {
        return secondsFrom(from, t) <= secondsFrom(from, toInclusive);
    }

    /**
     * {@code t} が {@code from} 〜 {@code toExclusive} の範囲にあるか（終端を含まない）。
     *
     * <p>深夜料金のように「5:00 になったら、もう深夜ではない」と言いたい範囲に使います。
     * 終端を含む {@link #isBetween} と、含むか含まないかだけが違います。
     */
    private static boolean isBetweenExcludingEnd(LocalTime t, LocalTime from, LocalTime toExclusive) {
        return secondsFrom(from, t) < secondsFrom(from, toExclusive);
    }

    /** 受付できない理由をお客さん向けの文章で返す。 */
    public String orderRejectReason(LocalDateTime now) {
        if (!acceptingOrders) {
            return closedMessage;
        }
        if (isWithinOrderableHours(now.toLocalTime())) {
            return "";
        }
        // 開店前なのか、ラストオーダー後なのかを、なるべく自然な言い方で出し分ける。
        // 日をまたぐ営業では「開店前」と「LO後」の境目があいまいなので、
        // 閉店からの時間が近いほうを理由として選ぶ。
        LocalTime t = now.toLocalTime();
        long minutesSinceLastOrder = Math.floorMod(
                t.toSecondOfDay() / 60 - lastOrderTime.toSecondOfDay() / 60, 24 * 60);
        long minutesUntilOpen = Math.floorMod(
                openTime.toSecondOfDay() / 60 - t.toSecondOfDay() / 60, 24 * 60);

        if (minutesSinceLastOrder < minutesUntilOpen) {
            return "本日のご注文受付は終了しました（ラストオーダー %s）。".formatted(lastOrderTime);
        }
        return "本日の営業は %s からです。".formatted(openTime);
    }

    /**
     * 指定日時が属する営業日を返す。
     * 切り替え時刻より前なら前日扱いにする。
     */
    public LocalDate businessDateOf(LocalDateTime now) {
        if (now.getHour() < businessDayCutoverHour) {
            return now.toLocalDate().minusDays(1);
        }
        return now.toLocalDate();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public boolean isAcceptingOrders() {
        return acceptingOrders;
    }

    public void setAcceptingOrders(boolean acceptingOrders) {
        this.acceptingOrders = acceptingOrders;
    }

    public String getClosedMessage() {
        return closedMessage;
    }

    public void setClosedMessage(String closedMessage) {
        this.closedMessage = closedMessage;
    }

    public boolean isAlwaysOpen() {
        return alwaysOpen;
    }

    public void setAlwaysOpen(boolean alwaysOpen) {
        this.alwaysOpen = alwaysOpen;
    }

    public LocalTime getOpenTime() {
        return openTime;
    }

    public void setOpenTime(LocalTime openTime) {
        this.openTime = openTime;
    }

    public LocalTime getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(LocalTime closeTime) {
        this.closeTime = closeTime;
    }

    public LocalTime getLastOrderTime() {
        return lastOrderTime;
    }

    public void setLastOrderTime(LocalTime lastOrderTime) {
        this.lastOrderTime = lastOrderTime;
    }

    public int getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(int taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public int getOrderNumberStart() {
        return orderNumberStart;
    }

    public void setOrderNumberStart(int orderNumberStart) {
        this.orderNumberStart = orderNumberStart;
    }

    public int getBusinessDayCutoverHour() {
        return businessDayCutoverHour;
    }

    public void setBusinessDayCutoverHour(int businessDayCutoverHour) {
        this.businessDayCutoverHour = businessDayCutoverHour;
    }

    public int getGriddleCapacity() {
        return griddleCapacity;
    }

    public void setGriddleCapacity(int griddleCapacity) {
        this.griddleCapacity = griddleCapacity;
    }

    public int getTableChargePerGuest() {
        return tableChargePerGuest;
    }

    public void setTableChargePerGuest(int tableChargePerGuest) {
        this.tableChargePerGuest = tableChargePerGuest;
    }

    public int getMonthlyRent() {
        return monthlyRent;
    }

    public void setMonthlyRent(int monthlyRent) {
        this.monthlyRent = monthlyRent;
    }

    public TaxStatus getTaxStatus() {
        return taxStatus;
    }

    /** null が来たら未設定に倒す（「免税」に倒すと納税に関わる嘘になる）。 */
    public void setTaxStatus(TaxStatus taxStatus) {
        this.taxStatus = taxStatus == null ? TaxStatus.UNSET : taxStatus;
    }

    public String getInvoiceRegistrationNumber() {
        return invoiceRegistrationNumber;
    }

    /**
     * 登録番号を入れる。空白だけの入力は「持っていない」として null に揃える。
     *
     * <p>形の検査はここではしません。画面側で検査してエラーを出す
     * （{@code AdminSettingController}）ほうが、どこが悪いか伝えられるためです。
     */
    public void setInvoiceRegistrationNumber(String invoiceRegistrationNumber) {
        this.invoiceRegistrationNumber =
                (invoiceRegistrationNumber == null || invoiceRegistrationNumber.isBlank())
                        ? null : invoiceRegistrationNumber.trim();
    }

    /**
     * 適格請求書（インボイス）を出せる店か。
     *
     * <p>お客さまが経費で落とすときに、こちらの登録番号が要ります。
     * 番号を持っていなければ、相手はその支払いで仕入税額控除ができません。
     */
    public boolean isInvoiceRegistered() {
        return invoiceRegistrationNumber != null && !invoiceRegistrationNumber.isBlank();
    }

    /**
     * 税理士の画面で「控除できる税額」に意味があるか。
     *
     * <p>免税事業者は消費税の申告をしないので、仕入税額控除もしません。
     * 数字を出しても読む意味が無いので、画面はこれを見て断りを出します。
     */
    public boolean usesTaxDeduction() {
        return taxStatus.filesTaxReturn();
    }

    public LocalTime getLateNightStartTime() {
        return lateNightStartTime;
    }

    public void setLateNightStartTime(LocalTime lateNightStartTime) {
        this.lateNightStartTime = lateNightStartTime;
    }

    public LocalTime getLateNightEndTime() {
        return lateNightEndTime;
    }

    public void setLateNightEndTime(LocalTime lateNightEndTime) {
        this.lateNightEndTime = lateNightEndTime;
    }

    public int getLateNightSurchargePercent() {
        return lateNightSurchargePercent;
    }

    public void setLateNightSurchargePercent(int lateNightSurchargePercent) {
        this.lateNightSurchargePercent = lateNightSurchargePercent;
    }

    /**
     * 指定時刻に深夜料金がかかるか。
     *
     * <p>深夜料金は「23:00 〜 翌 5:00」のように<b>日付をまたいだ側</b>にかかります。
     * 単純に {@code t >= 23:00} と書くと深夜 1:00 が対象外になってしまいますが、
     * {@link #isBetweenExcludingEnd} が円としての時刻を扱うので、
     * ここでは日をまたぐかどうかを気にせずそのまま書けます。
     *
     * <p>営業時間の判定とまったく同じ道具を使っている点に注目してください。
     * 「営業時間」と「深夜料金」は別の話ですが、
     * <b>どちらも「日をまたぐかもしれない時刻の範囲」</b>という同じ形をしています。
     * 同じ形のものを同じ道具で扱えるようにしておくと、
     * 営業時間を 24 時間に変えても深夜料金の実装は 1 行も変わりません。
     */
    public boolean isLateNight(LocalDateTime at) {
        if (lateNightSurchargePercent <= 0) {
            return false;
        }
        // 開始ちょうど（23:00）から深夜。終了ちょうど（5:00）は、もう深夜ではない
        return isBetweenExcludingEnd(at.toLocalTime(), lateNightStartTime, lateNightEndTime);
    }

    public String getPickupNotice() {
        return pickupNotice;
    }

    public void setPickupNotice(String pickupNotice) {
        this.pickupNotice = pickupNotice;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
