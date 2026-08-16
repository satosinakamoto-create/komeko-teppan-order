package jp.komeko.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
     *
     * <p><b>深夜営業への対応</b><br>
     * 「17:30 開店・翌 1:30 ラストオーダー」のように<b>日付をまたぐ営業</b>があります。
     * このとき {@code openTime}(17:30) より {@code lastOrderTime}(01:30) のほうが
     * 「時刻としては小さい」ため、単純に
     * {@code open <= t && t <= lastOrder} と書くと一日中 false になってしまいます。
     *
     * <pre>
     *   日をまたがない例（11:00〜18:30）
     *      0        11:00 ████████ 18:30         24
     *      判定: open <= t かつ t <= lastOrder
     *
     *   日をまたぐ例（17:30〜翌01:30）
     *      0 ███ 01:30              17:30 ██████ 24
     *      判定: open <= t <b>または</b> t <= lastOrder
     * </pre>
     */
    private boolean isWithinOrderableHours(LocalTime t) {
        if (!lastOrderTime.isBefore(openTime)) {
            // 同じ日のうちに閉まる、ふつうの営業時間
            return !t.isBefore(openTime) && !t.isAfter(lastOrderTime);
        }
        // 日付をまたぐ営業時間
        return !t.isBefore(openTime) || !t.isAfter(lastOrderTime);
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
