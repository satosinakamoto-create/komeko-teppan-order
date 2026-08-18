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

    /**
     * 大カテゴリ（お客さんのメニュー画面で、いちばん上に並ぶタブの名前）。
     *
     * <p>このお店はカテゴリが 14 個あります。そのままタブにすると横に長すぎて、
     * お客さんは端まで探しに行けません。そこで
     * <b>「鉄板おつまみ」「鉄板麺」「数量限定鉄板焼き」→ 鉄板料理</b> のように
     * まとめた名前をここに入れ、タブはこの名前で作ります。
     *
     * <pre>
     *   タブ         このカテゴリ（タブの中で見出しとして出る）
     *   ──────────────────────────────────────────
     *   お好み焼き    広島風お好み焼き
     *   たこ焼き      選べる米粉たこ焼き
     *   鉄板料理      鉄板おつまみ ／ 鉄板麺 ／ 数量限定鉄板焼き
     *   一品料理      一品料理 ／ 甘味
     *   ドリンク      ビール・サワー ／ クラフトジン ／ ウィスキー ／ …
     * </pre>
     *
     * <p><b>空にしておくと、そのカテゴリ名がそのままタブになります。</b>
     * 新しいカテゴリを足したときに設定を忘れても、メニューから消えたりせず
     * 「自分だけのタブ」として出るようにしてあります。
     */
    @Size(max = 20, message = "大カテゴリは20文字以内で入力してください")
    @Column(name = "group_name", length = 20)
    private String groupName;

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

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        // 空白だけの入力は「未設定」として扱う。
        // "" と null と "  " が混在すると、タブが分裂して原因が分からなくなる
        this.groupName = (groupName == null || groupName.isBlank()) ? null : groupName.trim();
    }

    /**
     * タブに出す名前。大カテゴリが未設定ならカテゴリ名そのものを使う。
     *
     * <p>画面から呼びやすいよう、この判断はここ 1 箇所にまとめてあります。
     * テンプレート側で {@code groupName != null ? groupName : name} と書くと、
     * 同じ判断が画面のあちこちに散らばってしまうためです。
     */
    public String getTabName() {
        return (groupName == null || groupName.isBlank()) ? name : groupName;
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
