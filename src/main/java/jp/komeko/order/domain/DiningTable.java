package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 卓（テーブル・カウンター席）。
 *
 * <p>イートインのモバイルオーダーでは、卓ごとに固定の QR コードを貼ります。
 * お客さんがその QR を読むと {@code /t/{accessToken}} が開き、
 * 「どの卓からの注文か」がサーバ側で分かる、という仕組みです。
 *
 * <p><b>なぜ卓番号ではなくトークンを URL に使うのか</b><br>
 * {@code /t/3}（3番テーブル）のような URL にすると、
 * 数字を変えるだけで他の卓の伝票を開けてしまいます。
 * 推測できないランダムな文字列にしておけば、
 * その卓の QR を実際に読んだ人だけがアクセスできます。
 *
 * <p>※ 店内に貼られた QR なので「同じ店内の別の人」には防げません。
 * それは店舗運用で許容する範囲、という整理です（docs/仕様.md 参照）。
 */
@Entity
@Table(name = "dining_table")
public class DiningTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 卓の呼び名（例: 1番テーブル / カウンター2 / 座敷A）。厨房やホールで使う表示名。 */
    @NotBlank(message = "卓名を入力してください")
    @Size(max = 20, message = "卓名は20文字以内で入力してください")
    @Column(nullable = false, length = 20)
    private String name;

    /** 席数。人数の初期値として使う。 */
    @Min(value = 1, message = "席数は1以上で入力してください")
    @Column(nullable = false)
    private int capacity = 4;

    /** QR に埋め込むランダム文字列。卓ごとに固定。 */
    @Column(nullable = false, unique = true, length = 36)
    private String accessToken;

    /**
     * エリア（カウンター・小上がり など）。空なら未設定。
     *
     * <p><b>enum にしない。</b>エリア名は店の言葉で店主が決めるもので、
     * コードが先に語彙を決めるとそれに合わせて店が呼び方を変えることになる。
     * ホール盤面はこの値ごとに見出しで区切る（未設定は「その他」にまとめ、
     * 全卓未設定なら見出し自体を出さない）。
     */
    @Size(max = 20, message = "エリアは20文字以内で入力してください")
    @Column(length = 20)
    private String area;

    /** false にすると QR を読んでも注文できない（席の一時撤去・貸切など）。 */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * 会計は済んだが、まだ片付いていない（＝次の組をご案内できない）。
     *
     * <p><b>「在席かどうか」はこれまでどおり伝票から導出します。</b>
     * ここに持つのは<b>伝票からは導けない現実の事実</b>——テーブルの上が
     * 片付いたかどうか——だけです（HallController の「卓に使用中フラグを
     * 持たせない」の方針と矛盾しません。あちらは伝票で分かることの話）。
     *
     * <p>立てるのは会計（closeSession・設定で ON のとき）、
     * 下ろすのはホールの「片付け完了」と、会計取消（reopen＝在席に戻るので
     * 片付け待ちではなくなる）。ズレても「片付け完了」を押せば直る。
     *
     * <p>DEFAULT FALSE を書くのは V12 と同じ理由（既存の行を埋めるため。
     * dev は ddl-auto: update がこの定義で列を足す）。
     */
    @Column(name = "needs_cleanup", nullable = false, columnDefinition = "boolean default false")
    private boolean needsCleanup = false;

    @Column(nullable = false)
    private int sortOrder = 0;

    protected DiningTable() {
    }

    public DiningTable(String name, int capacity, int sortOrder) {
        this.name = name;
        this.capacity = capacity;
        this.sortOrder = sortOrder;
        this.accessToken = UUID.randomUUID().toString();
    }

    /**
     * QR を作り直す（トークンを振り直す）。
     * 卓の QR が外部に出回ってしまったときの緊急手段。
     * 実行すると、それまでに配った QR はすべて無効になります。
     */
    public void regenerateAccessToken() {
        this.accessToken = UUID.randomUUID().toString();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public boolean isActive() {
        return active;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    /** エリアが設定されているか（空白だけも未設定扱い）。 */
    public boolean hasArea() {
        return area != null && !area.isBlank();
    }

    public boolean isNeedsCleanup() {
        return needsCleanup;
    }

    public void setNeedsCleanup(boolean needsCleanup) {
        this.needsCleanup = needsCleanup;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
