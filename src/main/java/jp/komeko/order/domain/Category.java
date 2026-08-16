package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * メニューのカテゴリ（例: 米粉ガレット / 鉄板焼き / 甘いクレープ / ドリンク）。
 *
 * <p><b>エンティティとは</b><br>
 * {@code @Entity} を付けたクラスは DB のテーブル 1 つに対応します。
 * このクラスなら {@code category} テーブルが自動で作られ、
 * フィールドがそのまま列になります（JPA / Hibernate の機能）。
 * SQL を書かなくても、Java のオブジェクトを保存・取得できるようになります。
 */
@Entity
@Table(name = "category")
public class Category {

    /**
     * 主キー。
     * {@code GenerationType.IDENTITY} は「DB の AUTO_INCREMENT に採番を任せる」という意味。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "カテゴリ名を入力してください")
    @Size(max = 40, message = "カテゴリ名は40文字以内で入力してください")
    @Column(nullable = false, length = 40)
    private String name;

    /** 画面での並び順（小さいほど先頭）。 */
    @Column(nullable = false)
    private int sortOrder = 0;

    /** false にするとお客さんのメニューから隠れる（削除せず一時的に下げたいとき用）。 */
    @Column(nullable = false)
    private boolean visible = true;

    // ── コンストラクタ ────────────────────────────────────────────
    // JPA は「引数なしコンストラクタ」でインスタンスを作るので、必ず用意しておく。

    protected Category() {
    }

    public Category(String name, int sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
