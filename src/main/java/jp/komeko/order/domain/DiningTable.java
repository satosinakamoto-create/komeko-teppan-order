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

    /** false にすると QR を読んでも注文できない（席の一時撤去・貸切など）。 */
    @Column(nullable = false)
    private boolean active = true;

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
